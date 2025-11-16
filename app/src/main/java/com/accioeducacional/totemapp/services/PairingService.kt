package com.accioeducacional.totemapp.services

import android.content.Context
import android.util.Base64
import android.util.Log
import com.accioeducacional.totemapp.crypto.ECCCryptoManager
import com.accioeducacional.totemapp.device.DeviceInfoProvider
import com.accioeducacional.totemapp.exceptions.NetworkException
import org.json.JSONObject
import kotlin.text.Charsets

class PairingService(
    private val context: Context,
    private val defaultServerUrl: String
) {

    private val cryptoManager = ECCCryptoManager(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPaired(): Boolean = cryptoManager.isPaired()

    fun getCryptoManager(): ECCCryptoManager = cryptoManager

    fun getLastServerUrl(): String = prefs.getString(KEY_SERVER_URL, defaultServerUrl) ?: defaultServerUrl

    fun pairFromJwt(jwt: String): PairingOutcome {
        val payload = decodeJwt(jwt)
        val accessToken = payload.accessToken ?: jwt
        val serverUrl = payload.serverUrl ?: getLastServerUrl()

        val api = ApiService(cryptoManager, serverUrl, accessToken)
        val deviceId = DeviceInfoProvider.getStableDeviceId(context)

        return try {
            val (success, _) = api.pairWithServer(deviceId)
            if (success) {
                cryptoManager.saveAccessToken(accessToken)
                persistServerUrl(serverUrl)
                PairingOutcome.Success(
                    deviceId = deviceId,
                    accessToken = accessToken,
                    serverUrl = serverUrl,
                    expiresAt = payload.expiresAt
                )
            } else {

                PairingOutcome.Failure("Pairing request rejected")
            }
        } catch (ex: NetworkException) {
            Log.e(TAG, "Network error during pairing: ${ex.message}")
            PairingOutcome.Failure(ex.message ?: "Network error")
        } catch (ex: Exception) {
            Log.e(TAG, "Unexpected error during pairing: ${ex.message}")
            PairingOutcome.Failure(ex.message ?: "Unexpected error")
        }
    }

    fun getPairingStatus(): Map<String, Any?> = cryptoManager.getPairingStatus()

    data class JwtPayload(
        val accessToken: String?,
        val serverUrl: String?,
        val expiresAt: Long?
    )

    sealed class PairingOutcome {
        data class Success(
            val deviceId: String,
            val accessToken: String,
            val serverUrl: String,
            val expiresAt: Long?
        ) : PairingOutcome()

        data class Failure(val reason: String) : PairingOutcome()
    }

    private fun decodeJwt(jwt: String): JwtPayload {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) {
                return JwtPayload(jwt, null, null)
            }
            val payloadSegment = parts[1]
            val decoded = Base64.decode(padBase64(payloadSegment), Base64.URL_SAFE or Base64.NO_WRAP)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            val token = json.optString("access_token").ifBlank {
                json.optString("token").ifBlank { null }
            }
            val server = json.optString("server_url").ifBlank {
                json.optString("server").ifBlank { null }
            }
            val expiresAt = when {
                json.has("expires_at") -> json.optLong("expires_at")
                json.has("exp") -> json.optLong("exp")
                else -> null
            }
            JwtPayload(token, server, expiresAt?.takeIf { it != 0L })
        } catch (ex: Exception) {
            Log.w(TAG, "Unable to decode JWT payload: ${ex.message}")
            JwtPayload(jwt, null, null)
        }
    }

    private fun padBase64(value: String): String {
        var padded = value
        val remainder = value.length % 4
        if (remainder != 0) {
            padded += "=".repeat(4 - remainder)
        }
        return padded
    }

    companion object {
        private const val TAG = "PairingService"
        private const val PREFS_NAME = "pairing_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    private fun persistServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }
}
