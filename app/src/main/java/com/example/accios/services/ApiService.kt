package com.example.accios.services

import android.util.Log
import com.example.accios.crypto.ECCCryptoManager
import com.example.accios.exceptions.NetworkException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kotlin translation of the Python ApiService, using OkHttp under the hood.
 */
class ApiService(
    private val cryptoManager: ECCCryptoManager,
    serverUrl: String,
    private var token: String? = null,
    private val timeoutSeconds: Long = 10
) {

    private val baseUrl = serverUrl.removeSuffix("/")
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateToken(newToken: String?) {
        token = newToken
    }

    @Throws(NetworkException::class)
    fun pairWithServer(deviceId: String): Pair<Boolean, JSONObject?> {
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("publicKey", cryptoManager.getOrGenerateKeys())
        }

        val request = Request.Builder()
            .url("$baseUrl/api/equipment/auth/pair")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .apply { applyHeaders(this) }
            .build()

        execute(request).use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Pairing failed with HTTP ${response.code}")
                return false to null
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e(TAG, "Pairing response body is empty")
                return false to null
            }

            return try {
                val json = JSONObject(body)
                if (json.has("serverPublicKey")) {
                    cryptoManager.saveServerPublicKey(json.getString("serverPublicKey"))
                    true to json
                } else {
                    Log.e(TAG, "serverPublicKey missing in response")
                    false to json
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to parse pairing response: ${ex.message}")
                false to null
            }
        }
    }

    @Throws(NetworkException::class)
    fun postEncrypted(endpoint: String, payload: Any? = null): ApiResult {
        ensurePaired()
        val encrypted = cryptoManager.encrypt(payload ?: emptyMap<String, Any>())
        val body = JSONObject().put("ENC", encrypted).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl$endpoint")
            .post(body)
            .apply { applyHeaders(this) }
            .build()
        return executeEncrypted(request)
    }

    @Throws(NetworkException::class)
    fun getEncrypted(endpoint: String): ApiResult {
        ensurePaired()
        val encrypted = cryptoManager.encrypt(emptyMap<String, Any>())
        val httpUrl = ("$baseUrl$endpoint").toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ENC", encrypted)
            ?.build()
            ?: throw NetworkException("Invalid URL: $baseUrl$endpoint")
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .apply { applyHeaders(this) }
            .build()
        return executeEncrypted(request)
    }

    @Throws(NetworkException::class)
    fun heartbeat(): ApiResult {
        val payload = JSONObject().apply {
            put("timestamp", System.currentTimeMillis() / 1000L)
            put("status", "online")
        }
        return postEncrypted("/api/equipment/heartbeat", payload)
    }

    @Throws(NetworkException::class)
    fun sendLogs(logs: List<Any>): ApiResult {
        val payload = JSONObject().apply {
            put("logs", logs)
            put("timestamp", System.currentTimeMillis() / 1000L)
        }
        return postEncrypted("/api/equipment/logs", payload)
    }

    @Throws(NetworkException::class)
    fun syncEncodings(): ApiResult {
        val payload = JSONObject().apply {
            put("type", "sync_request")
            put("timestamp", System.currentTimeMillis() / 1000L)
        }
        return postEncrypted("/api/equipment/sync/base", payload)
    }

    private fun ensurePaired() {
        if (!cryptoManager.isPaired()) {
            throw NetworkException("Device is not paired. Run pairing first.")
        }
    }

    private fun execute(request: Request): Response {
        try {
            return client.newCall(request).execute()
        } catch (ex: IOException) {
            Log.e(TAG, "Network error calling ${request.url}: ${ex.message}")
            throw NetworkException("Network error: ${ex.message}", ex)
        }
    }

    private fun executeEncrypted(request: Request): ApiResult {
        execute(request).use { response ->
            val body = response.body
            val contentType = body?.contentType()?.toString()
            val rawBytes = body?.bytes()
            val rawBody = if (rawBytes != null && isTextContent(contentType)) {
                try {
                    String(rawBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            if (!response.isSuccessful || rawBytes == null) {
                return ApiResult(response.code, rawBytes, rawBody, null)
            }

            if (!rawBody.isNullOrEmpty() && isJsonContent(contentType)) {
                return try {
                    val json = JSONObject(rawBody)
                    if (json.has("ENC")) {
                        val decrypted = cryptoManager.decryptToString(json.getString("ENC"))
                        ApiResult(response.code, rawBytes, rawBody, decrypted)
                    } else {
                        ApiResult(response.code, rawBytes, rawBody, rawBody)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to decrypt response: ${ex.message}")
                    ApiResult(response.code, rawBytes, rawBody, rawBody)
                }
            }

            return ApiResult(response.code, rawBytes, rawBody, null)
        }
    }

    private fun applyHeaders(builder: Request.Builder) {
        builder.header("Content-Type", "application/json")
        builder.header("User-Agent", USER_AGENT)
        token?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
    }

    data class ApiResult(
        val statusCode: Int,
        val rawBytes: ByteArray?,
        val rawBody: String?,
        val decryptedBody: String?
    ) {
        fun asJson(): JSONObject? {
            return try {
                when {
                    !decryptedBody.isNullOrBlank() -> JSONObject(decryptedBody)
                    !rawBody.isNullOrBlank() -> JSONObject(rawBody)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "ApiService"
        private const val USER_AGENT = "SmartPresenceTotem/1.0.0"
    }

    private fun isJsonContent(contentType: String?): Boolean {
        return contentType?.startsWith("application/json", ignoreCase = true) == true
    }

    private fun isTextContent(contentType: String?): Boolean {
        return isJsonContent(contentType) || contentType?.startsWith("text/", ignoreCase = true) == true
    }
}
