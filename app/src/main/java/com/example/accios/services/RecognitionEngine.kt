package com.example.accios.services

import android.graphics.Bitmap
import com.example.accios.data.EncodingRepository
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

        val distanceL2 = candidate.distance
        val cosineApprox = 1f - (distanceL2 * distanceL2) / 2f
        Log.d(
            "RecognitionEngine",
            "Candidate found: ${candidate.personId}, distL2=$distanceL2, cos≈$cosineApprox"
        )

        if (distanceL2 > MATCH_THRESHOLD_L2) {
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

    companion object {
        private const val MATCH_THRESHOLD_L2 = 1.15f
    }
}
