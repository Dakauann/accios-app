package com.example.accios.services

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

data class FaceDetectionResult(val rect: Rect, val confidence: Double)

class FaceRecognitionService {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .enableTracking()
            .build()
    )

    fun processFrame(bitmap: Bitmap): List<FaceDetectionResult> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(image))
            faces.map { face ->
                FaceDetectionResult(face.boundingBox, face.trackingId?.toDouble() ?: 1.0)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Falha na detecção de faces: ${ex.message}")
            emptyList()
        }
    }

    fun resetTracker() {
        // O detector do ML Kit já realiza tracking interno; não há estado extra para limpar.
    }

    fun shutdown() {
        try {
            detector.close()
        } catch (ex: Exception) {
            Log.w(TAG, "Falha ao encerrar detector: ${ex.message}")
        }
    }

    companion object {
        private const val TAG = "FaceRecognitionService"
    }
}