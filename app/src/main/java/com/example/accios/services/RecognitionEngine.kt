package com.example.accios.services

import android.graphics.Bitmap
import com.example.accios.data.EncodingRepository
import kotlin.math.max
import kotlin.math.min
import android.util.Log

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

    fun recognize(bitmap: Bitmap): RecognitionResult? {
        Log.d("RecognitionEngine", "Recognizing face...")
        if (!encodingRepository.isReady()) {
            return null
        }
        val embedding = embeddingModel.embed(bitmap) ?: return null
        Log.d("RecognitionEngine", "Embedding generated successfully.")
        val candidate = encodingRepository.findNearest(embedding) ?: return null
        Log.d("RecognitionEngine", "Candidate found: ${candidate}")
        if (candidate.distance > MATCH_THRESHOLD) {
            return null
        }
        val clampedConfidence = (1f - candidate.distance).let { value ->
            min(1f, max(0f, value))
        }
        return RecognitionResult(
            personId = candidate.personId,
            displayName = candidate.displayName,
            distance = candidate.distance,
            confidence = clampedConfidence
        )
    }

    companion object {
        private const val MATCH_THRESHOLD = 0.70f
    }
}
