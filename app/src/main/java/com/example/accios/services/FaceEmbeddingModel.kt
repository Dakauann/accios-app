package com.example.accios.services

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.accios.data.EncodingRepository
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.sqrt

class FaceEmbeddingModel(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionLock = Any()

    @Volatile
    private var session: OrtSession
    @Volatile
    private var embeddingDim: Int
    private var inputName: String

    init {
        val descriptor = createSession()
        session = descriptor.session
        embeddingDim = descriptor.embeddingDim
        inputName = descriptor.inputName
    }

    fun embed(source: Bitmap): FloatArray? {
        val tensor = bitmapToTensor(source) ?: return null
        return try {
            synchronized(sessionLock) {
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val value = outputs[0].value
                    val rawEmbedding = when (value) {
                        is Array<*> -> (value.firstOrNull() as? FloatArray)?.copyOf()
                        is FloatArray -> value.copyOf()
                        else -> null
                    } ?: return null
                    normalize(rawEmbedding)
                    rawEmbedding
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Falha ao executar modelo ONNX: ${ex.message}", ex)
            null
        } finally {
            tensor.close()
        }
    }

    fun embeddingDimension(): Int = embeddingDim

    fun reloadInterpreter() {
        synchronized(sessionLock) {
            try {
                session.close()
            } catch (_: Exception) {
            }
            val descriptor = createSession()
            session = descriptor.session
            embeddingDim = descriptor.embeddingDim
            inputName = descriptor.inputName
        }
    }

    override fun close() {
        synchronized(sessionLock) {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun createSession(): SessionDescriptor {
        val modelBytes = loadModelBytes()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(DEFAULT_THREADS)
        }
        val newSession = environment.createSession(modelBytes, options)
        val inputName = newSession.inputNames.firstOrNull()
            ?: throw IllegalStateException("Modelo ONNX sem entradas configuradas")
        val outputInfo = newSession.outputInfo.values.firstOrNull()?.info as? TensorInfo
            ?: throw IllegalStateException("Modelo ONNX sem informações de saída")
        val dimension = outputInfo.shape
            ?.lastOrNull { it > 0 }
            ?.toInt()
            ?: DEFAULT_EMBEDDING_DIM
        return SessionDescriptor(newSession, inputName, dimension)
    }

    private fun loadModelBytes(): ByteArray {
        val customModel = File(appContext.filesDir, "${CUSTOM_MODEL_DIR}/${EncodingRepository.EMBEDDING_MODEL_FILENAME}")
        return if (customModel.exists()) {
            runCatching { customModel.readBytes() }
                .onFailure { Log.e(TAG, "Falha ao ler modelo customizado: ${it.message}") }
                .getOrNull()
                ?: loadAssetModel()
        } else {
            loadAssetModel()
        }
    }

    private fun loadAssetModel(): ByteArray {
        return try {
            appContext.assets.open(DEFAULT_MODEL_ASSET).use { it.readBytes() }
        } catch (ex: IOException) {
            Log.e(TAG, "Modelo padrão '${DEFAULT_MODEL_ASSET}' não encontrado: ${ex.message}")
            throw ex
        }
    }

    private fun bitmapToTensor(source: Bitmap): OnnxTensor? {
        val prepared = ensureSizeAndFormat(source)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        prepared.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val channelStride = INPUT_SIZE * INPUT_SIZE
        val buffer = FloatArray(channelStride * INPUT_CHANNELS)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)
            buffer[index] = normalizePixel(b)
            buffer[channelStride + index] = normalizePixel(g)
            buffer[channelStride * 2 + index] = normalizePixel(r)
        }

        if (prepared !== source) {
            prepared.recycle()
        }

        val shape = longArrayOf(1, INPUT_CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(buffer), shape)
    }

    private fun ensureSizeAndFormat(source: Bitmap): Bitmap {
        val resized = if (source.width != INPUT_SIZE || source.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        } else {
            source
        }

        if (resized.config == Bitmap.Config.ARGB_8888) {
            return resized
        }

        return resized.copy(Bitmap.Config.ARGB_8888, false).also {
            if (resized !== source) resized.recycle()
        }
    }

    private fun normalize(vector: FloatArray) {
        var sum = 0f
        for (value in vector) {
            sum += value * value
        }
        val norm = sqrt(sum.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }

    private fun normalizePixel(value: Int): Float {
        return ((value.toFloat() - PIXEL_MEAN) / PIXEL_STD)
    }

    private data class SessionDescriptor(
        val session: OrtSession,
        val inputName: String,
        val embeddingDim: Int
    )

    companion object {
        private const val TAG = "FaceEmbeddingModel"
        private const val INPUT_SIZE = 112
        private const val INPUT_CHANNELS = 3
        private const val DEFAULT_THREADS = 2
        private const val PIXEL_MEAN = 127.5f
        private const val PIXEL_STD = 128f
        private const val CUSTOM_MODEL_DIR = "encodings"
        private const val DEFAULT_MODEL_ASSET = "models/${EncodingRepository.EMBEDDING_MODEL_FILENAME}"
        private const val DEFAULT_EMBEDDING_DIM = 512
    }
}
