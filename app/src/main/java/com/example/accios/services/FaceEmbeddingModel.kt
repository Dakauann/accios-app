package com.example.accios.services

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.accios.data.EncodingRepository
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class FaceEmbeddingModel(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val modelLock = Any()
    private var interpreter: Interpreter
    @Volatile
    private var embeddingDim: Int

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(PIXEL_MEAN, PIXEL_STD))
        .build()

    init {
        val (engine, dimension) = createInterpreter()
        interpreter = engine
        embeddingDim = dimension
    }

    fun embed(source: Bitmap): FloatArray? {
        val inputImage = TensorImage.fromBitmap(source)
        val processed = imageProcessor.process(inputImage)
        val output = Array(1) { FloatArray(embeddingDim) }
        return try {
            synchronized(modelLock) {
                interpreter.run(processed.buffer, output)
            }
            normalize(output[0])
            output[0]
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to run embedding model: ${ex.message}", ex)
            null
        }
    }

    fun embeddingDimension(): Int = embeddingDim

    fun reloadInterpreter() {
        synchronized(modelLock) {
            try {
                interpreter.close()
            } catch (_: Exception) {
            }
            val (engine, dimension) = createInterpreter()
            interpreter = engine
            embeddingDim = dimension
        }
    }

    override fun close() {
        synchronized(modelLock) {
            try {
                interpreter.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun createInterpreter(): Pair<Interpreter, Int> {
        val modelBuffer = loadModelBuffer()
        val options = Interpreter.Options().apply {
            setNumThreads(DEFAULT_THREADS)
        }
        val engine = Interpreter(modelBuffer, options)
        val outputShape = engine.getOutputTensor(0).shape()
        val dimension = when {
            outputShape.isEmpty() -> throw IllegalStateException("Embedding tensor shape is empty")
            outputShape.size == 1 -> outputShape[0]
            else -> outputShape[outputShape.size - 1]
        }
        return engine to dimension
    }

    private fun loadModelBuffer(): MappedByteBuffer {
        val customModel = File(appContext.filesDir, "${CUSTOM_MODEL_DIR}/${EncodingRepository.EMBEDDING_MODEL_FILENAME}")
        return if (customModel.exists()) {
            mapFile(customModel)
        } else {
            loadAssetModel()
        }
    }

    private fun loadAssetModel(): MappedByteBuffer {
        val assetName = DEFAULT_MODEL_ASSET
        return try {
            appContext.assets.openFd(assetName).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Embedding model asset '$assetName' not found: ${ex.message}")
            throw ex
        }
    }

    private fun mapFile(file: File): MappedByteBuffer {
        return FileInputStream(file).use { fis ->
            val channel = fis.channel
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
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

    companion object {
        private const val TAG = "FaceEmbeddingModel"
        private const val INPUT_SIZE = 160
        private const val DEFAULT_THREADS = 2
        private const val PIXEL_MEAN = 127.5f
        private const val PIXEL_STD = 128f
        private const val DEFAULT_MODEL_ASSET = "models/${EncodingRepository.EMBEDDING_MODEL_FILENAME}"
        private const val CUSTOM_MODEL_DIR = "encodings"
    }
}
