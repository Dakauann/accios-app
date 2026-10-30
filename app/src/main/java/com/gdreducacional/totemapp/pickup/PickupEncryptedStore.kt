package com.gdreducacional.totemapp.pickup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-only, authenticated encryption for the offline roster and pending
 * exits. Atomic writes prevent a crash from replacing a good queue with a
 * partially written one. Decryption failures are surfaced to the caller.
 */
class PickupEncryptedStore(context: Context, name: String) {
    private val directory = File(context.noBackupFilesDir, "pickup").apply {
        check(exists() || mkdirs()) { "Não foi possível preparar o armazenamento da portaria." }
    }
    private val file = AtomicFile(File(directory, "$name.enc"))

    @Synchronized
    fun read(): String? {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return null }
        check(bytes.size > 14 && bytes[0] == VERSION) { "Dados locais da portaria inválidos." }
        val ivLength = bytes[1].toInt() and 0xff
        check(ivLength == 12 && bytes.size > 2 + ivLength + 16) { "Dados locais da portaria inválidos." }
        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(bytes.copyOfRange(2 + ivLength, bytes.size)).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == 12)
        val output = ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(VERSION).put(iv.size.toByte()).put(iv).put(encrypted).array()
        val stream = file.startWrite()
        try {
            stream.write(output)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun delete() = file.delete()

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@synchronized existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "gdredu_pickup_offline_v1"
        private const val VERSION: Byte = 1
        private val KEY_LOCK = Any()
    }
}
