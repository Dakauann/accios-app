package com.example.accios.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import android.graphics.Paint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.example.accios.services.FaceDetectionResult
import com.example.accios.services.FaceRecognitionService
import java.io.ByteArrayOutputStream
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class CameraView : ViewModel() {
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var appContext: Context? = null
    var detectedFaces = mutableStateListOf<DetectedFace>()
    private var faceService: FaceRecognitionService? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var recognitionCallback: ((Bitmap) -> Unit)? = null
    private val recognitionInFlight = AtomicBoolean(false)
    private var lastRecognitionTimestamp = 0L
    private val recognitionHoldMillis = 10_000L
    private val faceTrackStates = mutableMapOf<Int, FaceTrackState>()
    private var facesClearedSinceLastRecognition = true

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<FaceDetectionResult>) -> Unit = {},
        onRecognitionCandidate: (Bitmap) -> Unit = {}
    ) {
        appContext = context.applicationContext
        faceService = FaceRecognitionService()
        recognitionCallback = onRecognitionCandidate

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy, onFacesDetected)
                    }
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraView", "Camera bind failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(imageProxy: ImageProxy, onFacesDetected: (List<FaceDetectionResult>) -> Unit) {
        val service = faceService
        if (service == null) {
            imageProxy.close()
            return
        }

        val overlays = service.processFrame(imageProxy)

        detectedFaces.clear()
        if (overlays.isEmpty()) {
            facesClearedSinceLastRecognition = true
            onFacesDetected(emptyList())
        } else {
            detectedFaces.addAll(
                overlays.map { result ->
                    DetectedFace(
                        rect = Rect(result.rect),
                        isFrontFacing = result.isFrontFacing
                    )
                }
            )
            onFacesDetected(overlays)
        }

        updateFaceTrackStates(overlays)

        val recognitionTarget = overlays.firstOrNull { it.isFrontFacing }
        var frameBitmap: Bitmap? = null
        if (recognitionTarget != null) {
            frameBitmap = imageProxy.toFrameBitmap()
            maybeTriggerRecognition(frameBitmap, recognitionTarget)
        }

        imageProxy.close()
        frameBitmap?.recycleSafely()
    }

    private fun maybeTriggerRecognition(frameBitmap: Bitmap, detection: FaceDetectionResult?) {
        val callback = recognitionCallback ?: return
        val candidate = detection ?: return
        if (!candidate.isFrontFacing) return
        val trackingId = candidate.trackingId ?: return
        val trackState = faceTrackStates[trackingId] ?: return
        if (trackState.consecutiveFrontFrames < STABLE_FRAME_THRESHOLD) return
        if (recognitionInFlight.get()) return

        val now = SystemClock.elapsedRealtime()
        val respectCooldown = now - lastRecognitionTimestamp < recognitionHoldMillis
        if (respectCooldown && !facesClearedSinceLastRecognition) {
            return
        }

        val baseRect = trackState.lastBoundingRect ?: candidate.rect
        val bounded = expandRect(baseRect, frameBitmap.width, frameBitmap.height)
        val minSize = min(frameBitmap.width, frameBitmap.height) * MIN_FACE_SIZE_RATIO
        if (bounded.width() < minSize || bounded.height() < minSize) return

        val stableDuration = now - trackState.firstFrontFacingMillis
        if (trackState.firstFrontFacingMillis == 0L || stableDuration < STABLE_DURATION_MILLIS) return
        if (!isCentered(bounded, frameBitmap.width, frameBitmap.height)) return

        val faceRegion = try {
            Bitmap.createBitmap(
                frameBitmap,
                bounded.left,
                bounded.top,
                bounded.width(),
                bounded.height()
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Falha ao recortar face: ${ex.message}")
            return
        }

        val aligned = alignFaceBitmap(faceRegion, candidate, bounded) ?: run {
            var scaled = Bitmap.createScaledBitmap(faceRegion, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true)
            if (scaled === faceRegion) {
                scaled = faceRegion.copy(Bitmap.Config.ARGB_8888, false)
                    ?: Bitmap.createBitmap(faceRegion)
            }
            scaled
        }

        recognitionInFlight.set(true)
        facesClearedSinceLastRecognition = false
        callback.invoke(aligned)

        if (aligned !== faceRegion) {
            faceRegion.recycleSafely()
        }
    }

    fun unbindCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    fun onRecognitionProcessed(success: Boolean) {
        lastRecognitionTimestamp = SystemClock.elapsedRealtime()
        recognitionInFlight.set(false)
        if (success) {
            faceService?.resetTracker()
        }
    }

    private fun updateFaceTrackStates(detections: List<FaceDetectionResult>) {
        if (detections.isEmpty()) {
            faceTrackStates.clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val activeIds = HashSet<Int>()
        detections.forEach { result ->
            val id = result.trackingId ?: return@forEach
            activeIds += id
            val state = faceTrackStates.getOrPut(id) { FaceTrackState() }
            state.lastSeenMillis = now
            state.lastBoundingRect = Rect(result.rect)
            if (result.isFrontFacing) {
                if (state.consecutiveFrontFrames == 0) {
                    state.firstFrontFacingMillis = now
                }
                state.consecutiveFrontFrames = (state.consecutiveFrontFrames + 1).coerceAtMost(MAX_FRONT_FRAMES_TRACKED)
            } else {
                state.consecutiveFrontFrames = 0
                state.firstFrontFacingMillis = 0L
            }
        }

        val iterator = faceTrackStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val isStale = now - entry.value.lastSeenMillis > TRACK_STALE_TIMEOUT_MILLIS
            if (isStale || entry.value.consecutiveFrontFrames == 0 && entry.key !in activeIds) {
                iterator.remove()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        appContext = null
        faceService?.shutdown()
        faceService = null
        cameraExecutor.shutdown()
    }
}

// Converte ImageProxy em Bitmap já rotacionado para a orientação do display
private fun ImageProxy.toFrameBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    val rotationDegrees = imageInfo.rotationDegrees.toFloat()
    if (rotationDegrees != 0f) {
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    return bitmap
}

private fun Bitmap.recycleSafely() {
    try {
        if (!isRecycled) recycle()
    } catch (_: Exception) {
    }
}

private fun expandRect(source: Rect, frameWidth: Int, frameHeight: Int): Rect {
    val paddingX = (source.width() * FACE_PADDING_RATIO).roundToInt()
    val paddingY = (source.height() * FACE_PADDING_RATIO).roundToInt()
    val expanded = Rect(
        (source.left - paddingX).coerceAtLeast(0),
        (source.top - paddingY).coerceAtLeast(0),
        (source.right + paddingX).coerceAtMost(frameWidth),
        (source.bottom + paddingY).coerceAtMost(frameHeight)
    )
    return expanded
}

private fun alignFaceBitmap(region: Bitmap, detection: FaceDetectionResult, regionBounds: Rect): Bitmap? {
    val landmarks = detection.landmarks ?: return null
    val leftEye = landmarks.leftEye ?: return null
    val rightEye = landmarks.rightEye ?: return null
    val nose = landmarks.nose ?: return null

    val offsetX = regionBounds.left.toFloat()
    val offsetY = regionBounds.top.toFloat()
    val src = floatArrayOf(
        leftEye.x - offsetX,
        leftEye.y - offsetY,
        rightEye.x - offsetX,
        rightEye.y - offsetY,
        nose.x - offsetX,
        nose.y - offsetY
    )

    val matrix = Matrix()
    val success = matrix.setPolyToPoly(src, 0, ARC_FACE_REFERENCE_POINTS, 0, 3)
    if (!success) {
        return null
    }

    val output = Bitmap.createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(region, matrix, paint)
    return output
}

data class DetectedFace(
    val rect: Rect,
    val isFrontFacing: Boolean
)

private class FaceTrackState {
    var consecutiveFrontFrames: Int = 0
    var lastSeenMillis: Long = 0L
    var lastBoundingRect: Rect? = null
    var firstFrontFacingMillis: Long = 0L
}

private const val FACE_PADDING_RATIO = 0.25f
private const val MIN_FACE_SIZE_RATIO = 0.18f
private const val STABLE_FRAME_THRESHOLD = 8
private const val STABLE_DURATION_MILLIS = 800L
private const val MAX_FRONT_FRAMES_TRACKED = 30
private const val TRACK_STALE_TIMEOUT_MILLIS = 1_200L
private const val FACE_INPUT_SIZE = 112
private const val CENTER_TOLERANCE_RATIO = 0.2f
private val ARC_FACE_REFERENCE_POINTS = floatArrayOf(
    38.2946f, 51.6963f,
    73.5318f, 51.5014f,
    56.0252f, 71.7366f
)

private const val TAG = "CameraView"

private fun isCentered(rect: Rect, frameWidth: Int, frameHeight: Int): Boolean {
    val centerX = rect.exactCenterX()
    val centerY = rect.exactCenterY()
    val frameCenterX = frameWidth / 2f
    val frameCenterY = frameHeight / 2f
    val toleranceX = frameWidth * CENTER_TOLERANCE_RATIO / 2f
    val toleranceY = frameHeight * CENTER_TOLERANCE_RATIO / 2f
    return kotlin.math.abs(centerX - frameCenterX) <= toleranceX &&
        kotlin.math.abs(centerY - frameCenterY) <= toleranceY
}
