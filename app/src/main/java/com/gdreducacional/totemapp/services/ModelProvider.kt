package com.gdreducacional.totemapp.services

import android.content.Context
import android.util.Log
import com.gdreducacional.totemapp.data.EncodingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelProvider(context: Context) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, MODEL_DIR)
    private val modelFile = File(modelDir, EncodingRepository.EMBEDDING_MODEL_FILENAME)

    fun isAvailable(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    suspend fun ensure(onProgress: (Float) -> Unit = {}): Result<File> {
        if (isAvailable()) return Result.success(modelFile)
        return download(onProgress)
    }

    private suspend fun download(onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        val tempFile = File(modelDir, "model.download.tmp")

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()

            val request = Request.Builder().url(CDN_URL).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }

                val body = response.body
                    ?: return@withContext Result.failure(IOException("Resposta vazia"))

                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalRead = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                onProgress(totalRead.toFloat() / contentLength)
                            }
                        }
                    }
                }

                if (tempFile.length() < MIN_MODEL_SIZE) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        IOException("Arquivo corrompido: ${tempFile.length()} bytes")
                    )
                }

                if (!tempFile.renameTo(modelFile)) {
                    tempFile.copyTo(modelFile, overwrite = true)
                    tempFile.delete()
                }

                Log.i(TAG, "Modelo baixado: ${modelFile.length()} bytes")
                Result.success(modelFile)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Falha no download: ${ex.message}", ex)
            tempFile.delete()
            Result.failure(ex)
        }
    }

    companion object {
        private const val TAG = "ModelProvider"
        private const val CDN_URL = "https://cdn.gdredu.com/assets/onnx/buffalo_m.onnx"
        private const val MODEL_DIR = "encodings"
        private const val MIN_MODEL_SIZE = 50_000_000L
        private const val BUFFER_SIZE = 65_536
    }
}
