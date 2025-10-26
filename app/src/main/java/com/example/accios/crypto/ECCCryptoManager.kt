package com.example.accios.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.accios.util.hexToByteArray
import com.example.accios.util.toHexString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Kotlin port of the ECCCryptoManager used by the Python totem.
 * Implements P-256 ECDH with AES-256-GCM payload encryption and HMAC-SHA256 integrity, 
 * matching the bundle structure expected by the backend.
 */
class ECCCryptoManager(context: Context) {

    private val keysDir: File = File(context.filesDir, "keys").apply { if (!exists()) mkdirs() }
    private val privateKeyFile = File(keysDir, "device_private.pem")
    private val publicKeyFile = File(keysDir, "device_public.pem")
    private val serverPublicKeyFile = File(keysDir, "server_public.pem")
    private val accessTokenFile = File(keysDir, "access_token.txt")

    private val curveName = "secp256r1"
    private val secureRandom = SecureRandom()
    private val hmacSuffix = "HMAC_KEY".toByteArray(Charsets.UTF_8)

    @Volatile private var privateKeyCache: PrivateKey? = null
    @Volatile private var publicKeyHexCache: String? = null
    @Volatile private var serverPublicKeyCache: String? = null

    private val ecParameters: ECParameterSpec by lazy { resolveCurveParameters() }

    private fun resolveCurveParameters(): ECParameterSpec {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName))
        val params = (generator.generateKeyPair().public as ECPublicKey).params
        Log.d(TAG, "Resolved EC parameters for $curveName")
        return params
    }

    fun getOrGenerateKeys(): String {
        val cachedPublic = loadPublicKey()
        val cachedPrivate = loadPrivateKey()
        if (cachedPublic != null && cachedPrivate != null) {
            return cachedPublic
        }

        val (privateKey, publicHex) = generateKeyPair()
        savePrivateKey(privateKey)
        savePublicKey(publicHex)
        privateKeyCache = privateKey
        publicKeyHexCache = publicHex
        return publicHex
    }

    fun loadPublicKey(): String? {
        publicKeyHexCache?.let { return it }
        if (!publicKeyFile.exists()) return null
        return try {
            publicKeyFile.readText().trim().also { publicKeyHexCache = it }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to read public key: ${ex.message}")
            null
        }
    }

    fun loadPrivateKey(): PrivateKey? {
        privateKeyCache?.let { return it }
        if (!privateKeyFile.exists()) return null
        return try {
            val pem = privateKeyFile.readText()
            val clean = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            val spec = PKCS8EncodedKeySpec(decoded)
            val factory = KeyFactory.getInstance("EC")
            factory.generatePrivate(spec).also { privateKeyCache = it }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load private key: ${ex.message}")
            null
        }
    }

    fun saveServerPublicKey(serverPublicKeyHex: String) {
        try {
            serverPublicKeyFile.writeText(serverPublicKeyHex)
            serverPublicKeyCache = serverPublicKeyHex
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to persist server public key: ${ex.message}")
        }
    }

    fun loadServerPublicKey(): String? {
        serverPublicKeyCache?.let { return it }
        if (!serverPublicKeyFile.exists()) return null
        return try {
            serverPublicKeyFile.readText().trim().also { serverPublicKeyCache = it }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load server public key: ${ex.message}")
            null
        }
    }

    fun saveAccessToken(token: String) {
        try {
            accessTokenFile.writeText(token)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to save access token: ${ex.message}")
        }
    }

    fun loadAccessToken(): String? {
        if (!accessTokenFile.exists()) return null
        return try {
            accessTokenFile.readText().trim().ifEmpty { null }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load access token: ${ex.message}")
            null
        }
    }

    fun isPaired(): Boolean {
        val serverKey = loadServerPublicKey()
        val privateKey = loadPrivateKey()
        val publicKey = loadPublicKey()
        if (serverKey.isNullOrBlank() || privateKey == null || publicKey.isNullOrBlank()) {
            return false
        }
        return try {
            serverKey.hexToByteArray()
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Server key is not valid hex: ${ex.message}")
            false
        }
    }

    fun getPairingStatus(): Map<String, Any?> {
        val status = mutableMapOf<String, Any?>(
            "is_paired" to isPaired(),
            "has_device_private_key" to privateKeyFile.exists(),
            "has_device_public_key" to publicKeyFile.exists(),
            "has_server_public_key" to serverPublicKeyFile.exists(),
            "private_key_path" to privateKeyFile.absolutePath,
            "public_key_path" to publicKeyFile.absolutePath,
            "server_key_path" to serverPublicKeyFile.absolutePath,
            "keys_directory" to keysDir.absolutePath
        )
        return status
    }

    fun encrypt(data: Any, serverPublicKeyHexOverride: String? = null): String {
        val privateKey = loadPrivateKey() ?: throw IllegalStateException("Device private key not found")
        val payload = data.toJsonString()
        val serverPublicKeyHex = serverPublicKeyHexOverride ?: loadServerPublicKey()
            ?: throw IllegalStateException("Server public key not available")

        val serverKey = rebuildServerPublicKey(serverPublicKeyHex)
        val ephemeralPair = generateEphemeralPair()
        val sharedSecret = deriveSharedSecret(ephemeralPair.private, serverKey)
        val digest = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val aesKey = digest.copyOfRange(0, 32)
        val hmacKey = digest + hmacSuffix

        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(aesKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
        val cipherResult = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val encryptedData = cipherResult.copyOf(cipherResult.size - AUTH_TAG_BYTES)
        val authTag = cipherResult.copyOfRange(cipherResult.size - AUTH_TAG_BYTES, cipherResult.size)

        val clientPublicHex = loadPublicKey() ?: throw IllegalStateException("Device public key missing")
        val clientId = MessageDigest.getInstance("SHA-256")
            .digest(clientPublicHex.toByteArray(Charsets.UTF_8))
            .toHexString()
            .substring(0, 8)

        val nonceB64 = nonce.toBase64()
        val encryptedB64 = encryptedData.toBase64()
        val authTagB64 = authTag.toBase64()
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val authData = listOf(
            clientId,
            ephemeralPair.publicHex,
            nonceB64,
            encryptedB64,
            authTagB64,
            timestamp
        ).joinToString(":")

        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
        }.doFinal(authData.toByteArray(Charsets.UTF_8)).toBase64()

        val finalPayload = "$authData:$hmac"
        return finalPayload.toByteArray(Charsets.UTF_8).toBase64()
    }

    fun decryptToString(encryptedBundle: String): String {
        val decoded = String(Base64.decode(encryptedBundle, Base64.DEFAULT), Charsets.UTF_8)
        val parts = decoded.split(':')
        require(parts.size == 7) { "Encrypted payload has invalid structure" }

        val ephemeralPublicHex = parts[1]
        val nonceB64 = parts[2]
        val encryptedB64 = parts[3]
        val authTagB64 = parts[4]
        val timestamp = parts[5]
        val hmacB64 = parts[6]
        validateTimestamp(timestamp)

        val privateKey = loadPrivateKey() ?: throw IllegalStateException("Device private key not found")
        val serverSharedSecret = deriveSharedSecret(privateKey, rebuildPublicKeyFromHex(ephemeralPublicHex))
        val digest = MessageDigest.getInstance("SHA-256").digest(serverSharedSecret)
        val aesKey = digest.copyOfRange(0, 32)
        val hmacKey = digest + hmacSuffix

        val expectedAuthData = parts.take(6).joinToString(":")
        val expectedHmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
        }.doFinal(expectedAuthData.toByteArray(Charsets.UTF_8))

        val receivedHmac = Base64.decode(hmacB64, Base64.DEFAULT)
        if (!MessageDigest.isEqual(expectedHmac, receivedHmac)) {
            throw SecurityException("Invalid HMAC signature")
        }

        val nonce = Base64.decode(nonceB64, Base64.DEFAULT)
        val encryptedBytes = Base64.decode(encryptedB64, Base64.DEFAULT)
        val authTag = Base64.decode(authTagB64, Base64.DEFAULT)
        val cipherData = encryptedBytes + authTag

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, nonce)
        )

        val decrypted = cipher.doFinal(cipherData)
        return String(decrypted, Charsets.UTF_8)
    }

    fun decryptToJson(encryptedBundle: String): JSONObject? {
        return try {
            val text = decryptToString(encryptedBundle)
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun generateKeyPair(): Pair<PrivateKey, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName), secureRandom)
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as ECPublicKey
        val uncompressed = ByteArray(1 + COORDINATE_BYTES * 2)
        uncompressed[0] = 0x04
        val x = publicKey.w.affineX.toFixedLength(COORDINATE_BYTES)
        val y = publicKey.w.affineY.toFixedLength(COORDINATE_BYTES)
        System.arraycopy(x, 0, uncompressed, 1, COORDINATE_BYTES)
        System.arraycopy(y, 0, uncompressed, 1 + COORDINATE_BYTES, COORDINATE_BYTES)
        return pair.private to uncompressed.toHexString().also {
            Log.d(TAG, "Generated new ECC key pair")
        }
    }

    private fun generateEphemeralPair(): EphemeralPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName), secureRandom)
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as ECPublicKey
        val uncompressed = ByteArray(1 + COORDINATE_BYTES * 2)
        uncompressed[0] = 0x04
        val x = publicKey.w.affineX.toFixedLength(COORDINATE_BYTES)
        val y = publicKey.w.affineY.toFixedLength(COORDINATE_BYTES)
        System.arraycopy(x, 0, uncompressed, 1, COORDINATE_BYTES)
        System.arraycopy(y, 0, uncompressed, 1 + COORDINATE_BYTES, COORDINATE_BYTES)
        return EphemeralPair(pair.private, pair.public, uncompressed.toHexString())
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKeyHex: String): ByteArray {
        val publicKey = rebuildPublicKeyFromHex(publicKeyHex)
        return deriveSharedSecret(privateKey, publicKey)
    }

    private fun rebuildServerPublicKey(serverPublicKeyHex: String): PublicKey {
        return rebuildPublicKeyFromHex(serverPublicKeyHex)
    }

    private fun rebuildPublicKeyFromHex(hex: String): PublicKey {
        val bytes = hex.hexToByteArray()
        require(bytes.isNotEmpty() && bytes[0] == 0x04.toByte()) { "Only uncompressed EC keys are supported" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_BYTES, bytes.size))
        val point = java.security.spec.ECPoint(x, y)
        val keySpec = ECPublicKeySpec(point, ecParameters)
        val factory = KeyFactory.getInstance("EC")
        return factory.generatePublic(keySpec)
    }

    private fun savePrivateKey(privateKey: PrivateKey) {
        try {
            val base64 = Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP)
            val pemBody = base64.chunked(64).joinToString(separator = "\n")
            val pem = buildString {
                append("-----BEGIN PRIVATE KEY-----\n")
                append(pemBody)
                append("\n-----END PRIVATE KEY-----\n")
            }
            privateKeyFile.writeText(pem)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to persist private key: ${ex.message}")
        }
    }

    private fun savePublicKey(publicHex: String) {
        try {
            publicKeyFile.writeText(publicHex)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to persist public key: ${ex.message}")
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun ByteArray.copyOf(newSize: Int): ByteArray {
        val copy = ByteArray(newSize)
        System.arraycopy(this, 0, copy, 0, min(size, newSize))
        return copy
    }

    private fun BigInteger.toFixedLength(targetSize: Int): ByteArray {
        val raw = toByteArray()
        if (raw.size == targetSize) return raw
        if (raw.size == targetSize + 1 && raw[0] == 0.toByte()) {
            return raw.copyOfRange(1, raw.size)
        }
        return if (raw.size < targetSize) {
            ByteArray(targetSize - raw.size) + raw
        } else {
            raw.copyOfRange(raw.size - targetSize, raw.size)
        }
    }

    private fun validateTimestamp(timestamp: String) {
        val now = System.currentTimeMillis() / 1000L
        val messageTime = timestamp.toLongOrNull() ?: throw SecurityException("Invalid timestamp")
        if (now - messageTime > MAX_MESSAGE_AGE_SECONDS) {
            throw SecurityException("Encrypted payload expired")
        }
    }

    private fun Any.toJsonString(): String {
        return when (this) {
            is String -> this
            is JSONObject -> toString()
            is JSONArray -> toString()
            is Map<*, *> -> JSONObject(this).toString()
            is Collection<*> -> JSONArray(this).toString()
            else -> JSONObject.wrap(this)?.toString() ?: toString()
        }
    }

    private data class EphemeralPair(
        val private: PrivateKey,
        val public: PublicKey,
        val publicHex: String
    )

    companion object {
        private const val TAG = "ECCCryptoManager"
        private const val AUTH_TAG_BYTES = 16
        private const val COORDINATE_BYTES = 32
        private const val MAX_MESSAGE_AGE_SECONDS = 300L
    }
}
