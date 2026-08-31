package com.gdreducacional.totemapp.services

import android.util.Log
import android.graphics.Bitmap
import com.gdreducacional.totemapp.data.EncodingRepository
import kotlin.math.sqrt

class RecognitionEngine(
    private val embeddingModel: FaceEmbeddingModel,
    private val encodingRepository: EncodingRepository
) {

    data class RecognitionResult(
        val personId: String,
        val displayName: String?,
        val entityType: String?,
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
            Log.w(TAG, "RECOG_FAIL reason=dataset_not_ready people=${encodingRepository.getPeopleCount()}")
            return null
        }

        if (!embeddingModel.isReady()) {
            Log.w(TAG, "RECOG_FAIL reason=model_not_ready")
            return null
        }

        val t0 = System.nanoTime()
        val aggregatedEmbedding = aggregateEmbeddings(batch.frames)
        if (aggregatedEmbedding == null) {
            Log.w(
                TAG,
                "RECOG_FAIL reason=embed_failed frames=${batch.frames.size} " +
                    "trackId=${batch.trackId} modelDim=${embeddingModel.embeddingDimension()}"
            )
            return null
        }

        val embedMs = (System.nanoTime() - t0) / 1_000_000L
        val queryNorm = vectorNorm(aggregatedEmbedding)
        val thresholdL2 = encodingRepository.estimateThresholdL2().also { cachedThresholdL2 = it }
        val topK = encodingRepository.findTopK(aggregatedEmbedding, k = TOP_K_LOG)
        if (topK.isEmpty()) {
            Log.w(TAG, "RECOG_FAIL reason=empty_topk ${encodingRepository.getThresholdDiagnostics()}")
            return null
        }

        val topSummary = topK.joinToString(" | ") { c ->
            val cos = cosineFromL2(c.distance)
            "#${c.personId.take(8)} name=${c.displayName ?: "?"} " +
                "type=${c.entityType ?: "?"} distL2=${"%.4f".format(c.distance)} cos=${"%.4f".format(cos)}"
        }

        val candidate = topK.first()
        val distanceL2 = candidate.distance
        val cosineApprox = cosineFromL2(distanceL2)
        val margin = if (topK.size >= 2) topK[1].distance - distanceL2 else Float.MAX_VALUE
        val accepted = distanceL2 <= thresholdL2 && margin >= MIN_MATCH_MARGIN_L2
        val totalMs = (System.nanoTime() - t0) / 1_000_000L

        Log.i(
            TAG,
            "RECOG_${if (accepted) "HIT" else "MISS"} " +
                "trackId=${batch.trackId} frames=${batch.frames.size} " +
                "people=${encodingRepository.getPeopleCount()} " +
                "dimQ=${aggregatedEmbedding.size} dimBase=${encodingRepository.getEmbeddingDimension()} " +
                "qNorm=${"%.4f".format(queryNorm)} " +
                "bestId=${candidate.personId} bestName=${candidate.displayName} " +
                "bestType=${candidate.entityType} " +
                "distL2=${"%.4f".format(distanceL2)} cos=${"%.4f".format(cosineApprox)} " +
                "thresholdL2=${"%.4f".format(thresholdL2)} " +
                "gapBest=${"%.4f".format(thresholdL2 - distanceL2)} " +
                "secondGap=${"%.4f".format(margin)} " +
                "embedMs=$embedMs totalMs=$totalMs " +
                "topK=[$topSummary]"
        )

        if (!accepted) {
            // Segundo colocado ajuda a ver se o modelo está "quase" ou completamente perdido
            if (topK.size >= 2) {
                val second = topK[1]
                Log.i(
                    TAG,
                    "RECOG_MISS_GAP best=${"%.4f".format(distanceL2)} " +
                        "second=${"%.4f".format(second.distance)} " +
                        "gap=${"%.4f".format(second.distance - distanceL2)} " +
                        "needBelow=${"%.4f".format(thresholdL2)}"
                )
            }
            return null
        }

        val confidence = cosineApprox.coerceIn(0f, 1f)
        return RecognitionResult(
            personId = candidate.personId,
            displayName = candidate.displayName,
            entityType = candidate.entityType,
            distance = distanceL2,
            confidence = confidence
        )
    }

    private fun aggregateEmbeddings(frames: List<Bitmap>): FloatArray? {
        if (frames.isEmpty()) {
            Log.w(TAG, "aggregateEmbeddings: frames vazio")
            return null
        }
        val dimension = embeddingModel.embeddingDimension()
        val accumulator = FloatArray(dimension)
        var samples = 0

        for ((frameIndex, frame) in frames.withIndex()) {
            val t0 = System.nanoTime()
            val embedding = embeddingModel.embed(frame)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (embedding == null) {
                Log.w(
                    TAG,
                    "embed null frame=$frameIndex size=${frame.width}x${frame.height} " +
                        "recycled=${frame.isRecycled} ms=$ms"
                )
                return null
            }
            if (embedding.size != dimension) {
                Log.w(TAG, "Embedding dimension mismatch: expected=$dimension received=${embedding.size}")
                return null
            }
            val norm = vectorNorm(embedding)
            Log.d(
                TAG,
                "embed ok frame=$frameIndex size=${frame.width}x${frame.height} " +
                    "norm=${"%.4f".format(norm)} ms=$ms " +
                    "head=[${embedding.take(4).joinToString { "%.3f".format(it) }}]"
            )
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

    private fun cosineFromL2(distanceL2: Float): Float {
        // Para vetores L2-normalizados: ||a-b||^2 = 2 - 2·cos → cos = 1 - d²/2
        return 1f - (distanceL2 * distanceL2) / 2f
    }

    private fun vectorNorm(vector: FloatArray): Float {
        var sum = 0f
        for (value in vector) {
            sum += value * value
        }
        return sqrt(sum.toDouble()).toFloat()
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
        private const val DEFAULT_MATCH_THRESHOLD_L2 = 1.05f
        private const val MIN_MATCH_MARGIN_L2 = 0.08f
        private const val TOP_K_LOG = 3
    }
}
