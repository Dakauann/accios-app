package com.example.accios.services

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

data class FaceDetectionResult(
    val rect: Rect,
    val isFrontFacing: Boolean,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val trackingId: Int?,
    val landmarks: FaceLandmarks?
)

data class FaceLandmarks(
    val leftEye: PointF?,
    val rightEye: PointF?,
    val nose: PointF?,
    val mouthLeft: PointF?,
    val mouthRight: PointF?
)

class FaceRecognitionService {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build()
    )

    fun processFrame(imageProxy: ImageProxy): List<FaceDetectionResult> {
        val mediaImage = imageProxy.image ?: return emptyList()
        return try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotation)
            val faces = Tasks.await(detector.process(image))
            faces.mapNotNull { face ->
                val boundingBox = face.boundingBox
                FaceDetectionResult(
                    rect = boundingBox,
                    isFrontFacing = isFacingFront(face.headEulerAngleY, face.headEulerAngleX, face.headEulerAngleZ),
                    yaw = face.headEulerAngleY,
                    pitch = face.headEulerAngleX,
                    roll = face.headEulerAngleZ,
                    trackingId = face.trackingId,
                    landmarks = extractLandmarks(face)
                )
            }
        } catch (ex: Exception) {
            if (ENABLE_DEBUG_LOGS) {
                Log.w(TAG, "Falha na detecção de faces: ${ex.message}")
            }
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
            if (ENABLE_DEBUG_LOGS) {
                Log.w(TAG, "Falha ao encerrar detector: ${ex.message}")
            }
        }
    }

    companion object {
        private const val TAG = "FaceRecognitionService"
        private const val MIN_FACE_SIZE = 0.15f
        private const val MAX_YAW_DEGREES = 18f
        private const val MAX_PITCH_DEGREES = 15f
        private const val MAX_ROLL_DEGREES = 18f
        private const val ENABLE_DEBUG_LOGS = false

        init {
            silenceMlKitLogs()
        }

        private fun isFacingFront(yaw: Float, pitch: Float, roll: Float): Boolean {
            val yawOk = kotlin.math.abs(yaw) <= MAX_YAW_DEGREES
            val pitchOk = kotlin.math.abs(pitch) <= MAX_PITCH_DEGREES
            val rollOk = kotlin.math.abs(roll) <= MAX_ROLL_DEGREES
            return yawOk && pitchOk && rollOk
        }

        private fun extractLandmarks(face: com.google.mlkit.vision.face.Face): FaceLandmarks? {
            val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
            val nose = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position
            val mouthLeft = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
            val mouthRight = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position

            if (leftEye == null || rightEye == null || nose == null) {
                return null
            }

            return FaceLandmarks(
                leftEye = PointF(leftEye.x, leftEye.y),
                rightEye = PointF(rightEye.x, rightEye.y),
                nose = PointF(nose.x, nose.y),
                mouthLeft = mouthLeft?.let { PointF(it.x, it.y) },
                mouthRight = mouthRight?.let { PointF(it.x, it.y) }
            )
        }

        private fun silenceMlKitLogs() {
            // Ajusta loggers conhecidos do ML Kit para evitar spam em VERBOSE.
            runCatching {
                val clazz = Class.forName("com.google.mlkit.common.MlKitLogger")
                val method = clazz.getDeclaredMethod("setMinLogLevel", Int::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(null, Log.ERROR)
            }

            runCatching {
                val clazz = Class.forName("com.google.mlkit.common.sdkinternal.LogUtils")
                val method = clazz.getDeclaredMethod("setLogLevel", Int::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(null, Log.ASSERT)
            }

            runCatching {
                val systemProps = Class.forName("android.os.SystemProperties")
                val setMethod = systemProps.getDeclaredMethod("set", String::class.java, String::class.java)
                setMethod.isAccessible = true
                setMethod.invoke(null, "log.tag.FaceDetectorV2Jni", "ASSERT")
                setMethod.invoke(null, "log.tag.ThickFaceDetector", "ASSERT")
                setMethod.invoke(null, "log.tag.StreamingFormatChecker", "ASSERT")
            }
        }
    }
}
