package com.example.accios.services

import android.util.Log
import android.graphics.Bitmap
import com.example.accios.data.EncodingRepository
import kotlin.math.sqrt

class RecognitionEngine(
    private val embeddingModel: FaceEmbeddingModel,
    private val encodingRepository: EncodingRepository
) {

    data class RecognitionResult(
        val personId: String,
        val displayName: String?,
        val distance: Float,
        val confidence: Float
    )

    data class RecognitionBatch(
        val frames: List<Bitmap>,
        val trackId: Int?
    )

    @Volatile
    private var cachedThresholdL2: Float = DEFAULT_MATCH_THRESHOLD_L2

    fun recognize(batch: RecognitionBatch): RecognitionResult? {
        if (!encodingRepository.isReady()) {
            return null
        }

        val aggregatedEmbedding = aggregateEmbeddings(batch.frames) ?: return null
        val thresholdL2 = encodingRepository.estimateThresholdL2().also { cachedThresholdL2 = it }
        val candidate = encodingRepository.findNearest(aggregatedEmbedding) ?: return null

        val distanceL2 = candidate.distance
        val cosineApprox = 1f - (distanceL2 * distanceL2) / 2f
        Log.d(
            TAG,
            "Candidate=${candidate.personId}, distL2=${"%.4f".format(distanceL2)}, cos=${"%.4f".format(cosineApprox)}, thresholdL2=${"%.4f".format(thresholdL2)}"
        )

        if (distanceL2 > thresholdL2) {
            return null
        }

        val confidence = cosineApprox.coerceIn(0f, 1f)
        return RecognitionResult(
            personId = candidate.personId,
            displayName = candidate.displayName,
            distance = distanceL2,
            confidence = confidence
        )
    }

    private fun aggregateEmbeddings(frames: List<Bitmap>): FloatArray? {
        if (frames.isEmpty()) return null
        val dimension = embeddingModel.embeddingDimension()
        val accumulator = FloatArray(dimension)
        var samples = 0

        for (frame in frames) {
            val embedding = embeddingModel.embed(frame) ?: return null
            if (embedding.size != dimension) {
                Log.w(TAG, "Embedding dimension mismatch: expected=$dimension received=${embedding.size}")
                return null
            }
            for (index in embedding.indices) {
                accumulator[index] += embedding[index]
            }
            samples++
        }

        if (samples == 0) return null
        val inv = 1f / samples
        for (index in accumulator.indices) {
            accumulator[index] *= inv
        }
        normalize(accumulator)
        return accumulator
    }

    private fun normalize(vector: FloatArray) {
        var sum = 0f
        for (value in vector) {
            sum += value * value
        }
        val norm = sqrt(sum.toDouble()).toFloat()
        if (norm <= 0f) return
        for (index in vector.indices) {
            vector[index] /= norm
        }
    }

    companion object {
        private const val TAG = "RecognitionEngine"
        private const val DEFAULT_MATCH_THRESHOLD_L2 = 1.15f
    }
}
