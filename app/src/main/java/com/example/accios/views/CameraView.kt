package com.example.accios.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
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
    private var recognitionCallback: ((RecognitionCandidate) -> Unit)? = null
    private var luminanceCallback: ((Float) -> Unit)? = null
    private val recognitionInFlight = AtomicBoolean(false)
    private var lastRecognitionTimestamp = 0L
    private val recognitionHoldMillis = 3_000L
    private val faceTrackStates = mutableMapOf<Int, FaceTrackState>()
    private var facesClearedSinceLastRecognition = true
    private var lastLuminanceNotified: Float? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<FaceDetectionResult>) -> Unit = {},
        onRecognitionCandidate: (RecognitionCandidate) -> Unit = {},
        onAmbientLuminance: (Float) -> Unit = {}
    ) {
        appContext = context.applicationContext
        faceService = FaceRecognitionService()
        recognitionCallback = onRecognitionCandidate
        luminanceCallback = onAmbientLuminance
    lastLuminanceNotified = null

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

        luminanceCallback?.let { callback ->
            val luminance = estimateLuminance(imageProxy)
            val last = lastLuminanceNotified
            if (last == null || abs(last - luminance) > 1.5f) {
                lastLuminanceNotified = luminance
                callback.invoke(luminance)
            }
        }

        val overlays = service.processFrame(imageProxy)

        detectedFaces.clear()
        if (overlays.isEmpty()) {
            facesClearedSinceLastRecognition = true
            clearTrackStates()
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

        val aligned = alignFaceBitmap(faceRegion, candidate, bounded)
        if (aligned == null) {
            faceRegion.recycleSafely()
            return
        }

        if (aligned !== faceRegion) {
            faceRegion.recycleSafely()
        }

        val batch = trackState.accumulator.offer(aligned, now)
        if (batch == null) {
            return
        }

        recognitionInFlight.set(true)
        facesClearedSinceLastRecognition = false
        callback.invoke(RecognitionCandidate(trackingId, batch))
    }

    fun unbindCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
        luminanceCallback = null
        lastLuminanceNotified = null
    }

    fun onRecognitionProcessed(success: Boolean) {
        lastRecognitionTimestamp = SystemClock.elapsedRealtime()
        recognitionInFlight.set(false)
        resetAccumulations()
        if (success) {
            faceService?.resetTracker()
        }
    }

    private fun updateFaceTrackStates(detections: List<FaceDetectionResult>) {
        if (detections.isEmpty()) {
            clearTrackStates()
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
                state.clearFrames()
            }
        }

        val iterator = faceTrackStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val isStale = now - entry.value.lastSeenMillis > TRACK_STALE_TIMEOUT_MILLIS
            if (isStale || entry.value.consecutiveFrontFrames == 0 && entry.key !in activeIds) {
                entry.value.clearFrames()
                iterator.remove()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        appContext = null
        faceService?.shutdown()
        faceService = null
        clearTrackStates()
        cameraExecutor.shutdown()
    }

    private fun clearTrackStates() {
        faceTrackStates.values.forEach { it.clearFrames() }
        faceTrackStates.clear()
    }

    private fun resetAccumulations() {
        faceTrackStates.values.forEach { it.clearFrames() }
    }

    private fun estimateLuminance(imageProxy: ImageProxy): Float {
        val yPlane = imageProxy.planes.firstOrNull() ?: return 0f
        val buffer = yPlane.buffer.duplicate()
        buffer.rewind()
        val rowStride = yPlane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height
        val sampleStep = 20
        var sum = 0L
        var count = 0

        for (row in 0 until height step sampleStep) {
            val rowBase = row * rowStride
            for (col in 0 until width step sampleStep) {
                val index = rowBase + col
                if (index >= buffer.limit()) continue
                val value = buffer.get(index).toInt() and 0xFF
                sum += value
                count++
            }
        }

        if (count == 0) return 0f
        return sum.toFloat() / count.toFloat()
    }
}

// Converte ImageProxy em Bitmap já rotacionado para a orientação do display
private fun ImageProxy.toFrameBitmap(): Bitmap {
    val width = width
    val height = height
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer.asReadOnlyBuffer()
    val uBuffer = uPlane.buffer.asReadOnlyBuffer()
    val vBuffer = vPlane.buffer.asReadOnlyBuffer()

    val ySize = yBuffer.remaining()
    val nv21 = ByteArray(width * height * 3 / 2)
    yBuffer.get(nv21, 0, ySize)

    val uvPlaneSize = width * height / 2
    val vuPlane = ByteArray(uvPlaneSize)
    val vRowStride = vPlane.rowStride
    val uRowStride = uPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val uPixelStride = uPlane.pixelStride

    var vuIndex = 0
    for (row in 0 until height / 2) {
        val vRowOffset = row * vRowStride
        val uRowOffset = row * uRowStride
        for (col in 0 until width / 2) {
            val vIdx = vRowOffset + col * vPixelStride
            val uIdx = uRowOffset + col * uPixelStride
            vuPlane[vuIndex++] = vBuffer[vIdx]
            vuPlane[vuIndex++] = uBuffer[uIdx]
        }
    }

    System.arraycopy(vuPlane, 0, nv21, ySize, vuPlane.size)

    val argb = IntArray(width * height)
    yuvNv21ToArgb(nv21, width, height, argb)

    var bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

    val rotationDegrees = imageInfo.rotationDegrees.toFloat()
    if (rotationDegrees != 0f) {
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotatedBitmap !== bitmap) {
            bitmap.recycle()
        }
        bitmap = rotatedBitmap
    }

    return bitmap
}

// Converte NV21 para ARGB_8888 sem usar compressão.
private fun yuvNv21ToArgb(nv21: ByteArray, width: Int, height: Int, out: IntArray) {
    val frameSize = width * height
    var yp = 0
    for (j in 0 until height) {
        var uvp = frameSize + (j shr 1) * width
        var u = 0
        var v = 0
        for (i in 0 until width) {
            var y = (0xFF and nv21[yp].toInt()) - 16
            if (y < 0) y = 0
            if ((i and 1) == 0) {
                v = (0xFF and nv21[uvp++].toInt()) - 128
                u = (0xFF and nv21[uvp++].toInt()) - 128
            }

            val y1192 = 1192 * y
            var r = y1192 + 1634 * v
            var g = y1192 - 833 * v - 400 * u
            var b = y1192 + 2066 * u

            if (r < 0) r = 0 else if (r > 262143) r = 262143
            if (g < 0) g = 0 else if (g > 262143) g = 262143
            if (b < 0) b = 0 else if (b > 262143) b = 262143

            out[yp] = -0x1000000 or
                (r shl 6 and 0x00FF0000) or
                (g shr 2 and 0x0000FF00) or
                (b shr 10 and 0x000000FF)

            yp++
        }
    }
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

    val offsetX = regionBounds.left.toFloat()
    val offsetY = regionBounds.top.toFloat()

    val sourcePoints = ArrayList<PointF>(5)
    val targetPoints = ArrayList<PointF>(5)

    fun addPair(source: PointF?, target: PointF) {
        if (source != null) {
            sourcePoints += PointF(source.x - offsetX, source.y - offsetY)
            targetPoints += target
        }
    }

    addPair(landmarks.leftEye, ARC_FACE_REFERENCE_POINTS[0])
    addPair(landmarks.rightEye, ARC_FACE_REFERENCE_POINTS[1])
    addPair(landmarks.nose, ARC_FACE_REFERENCE_POINTS[2])

    val hasMouth = landmarks.mouthLeft != null && landmarks.mouthRight != null
    if (hasMouth) {
        addPair(landmarks.mouthLeft, ARC_FACE_REFERENCE_POINTS[3])
        addPair(landmarks.mouthRight, ARC_FACE_REFERENCE_POINTS[4])
    }

    if (sourcePoints.size < 3) {
        return null
    }

    val matrix = estimateSimilarityTransform(sourcePoints, targetPoints)
        ?: return null

    val output = Bitmap.createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(region, matrix, paint)
    return output
}

// Resolve uma transformação de similaridade (escala + rotação + translação) mínima em LS.
private fun estimateSimilarityTransform(source: List<PointF>, target: List<PointF>): Matrix? {
    val count = min(source.size, target.size)
    if (count < 2) {
        return null
    }

    val ata = Array(4) { DoubleArray(4) }
    val atb = DoubleArray(4)

    for (index in 0 until count) {
        val sx = source[index].x.toDouble()
        val sy = source[index].y.toDouble()
        val tx = target[index].x.toDouble()
        val ty = target[index].y.toDouble()

        val row1 = doubleArrayOf(sx, -sy, 1.0, 0.0)
        val row2 = doubleArrayOf(sy, sx, 0.0, 1.0)

        accumulateNormalEquation(ata, row1)
        accumulateNormalEquation(ata, row2)

        for (i in 0 until 4) {
            atb[i] += row1[i] * tx + row2[i] * ty
        }
    }

    val params = solveLinearSystem(ata, atb) ?: return null
    val a = params[0]
    val b = params[1]
    val transX = params[2]
    val transY = params[3]

    val matrix = Matrix()
    val values = floatArrayOf(
        a.toFloat(), (-b).toFloat(), transX.toFloat(),
        b.toFloat(), a.toFloat(), transY.toFloat(),
        0f, 0f, 1f
    )
    matrix.setValues(values)
    return matrix
}

private fun accumulateNormalEquation(ata: Array<DoubleArray>, row: DoubleArray) {
    for (i in 0 until 4) {
        for (j in i until 4) {
            val value = row[i] * row[j]
            ata[i][j] += value
            if (i != j) {
                ata[j][i] += value
            }
        }
    }
}

private fun solveLinearSystem(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
    val size = vector.size
    val augmented = Array(size) { DoubleArray(size + 1) }

    for (row in 0 until size) {
        for (col in 0 until size) {
            augmented[row][col] = matrix[row][col]
        }
        augmented[row][size] = vector[row]
    }

    for (pivot in 0 until size) {
        var maxRow = pivot
        var maxVal = kotlin.math.abs(augmented[pivot][pivot])
        for (row in pivot + 1 until size) {
            val value = kotlin.math.abs(augmented[row][pivot])
            if (value > maxVal) {
                maxVal = value
                maxRow = row
            }
        }

        if (maxVal < LINEAR_SOLVER_EPS) {
            return null
        }

        if (maxRow != pivot) {
            val temp = augmented[pivot]
            augmented[pivot] = augmented[maxRow]
            augmented[maxRow] = temp
        }

        val pivotVal = augmented[pivot][pivot]
        for (col in pivot until size + 1) {
            augmented[pivot][col] /= pivotVal
        }

        for (row in 0 until size) {
            if (row == pivot) continue
            val factor = augmented[row][pivot]
            if (factor == 0.0) continue
            for (col in pivot until size + 1) {
                augmented[row][col] -= factor * augmented[pivot][col]
            }
        }
    }

    return DoubleArray(size) { augmented[it][size] }
}

data class DetectedFace(
    val rect: Rect,
    val isFrontFacing: Boolean
)

data class RecognitionCandidate(
    val trackId: Int,
    val frames: List<Bitmap>
)

private class FaceTrackState {
    var consecutiveFrontFrames: Int = 0
    var lastSeenMillis: Long = 0L
    var lastBoundingRect: Rect? = null
    var firstFrontFacingMillis: Long = 0L
    val accumulator = FrameAccumulator(FRAME_AGGREGATION_COUNT, FRAME_AGGREGATION_WINDOW_MILLIS)

    fun clearFrames() {
        accumulator.reset()
    }
}

private class FrameAccumulator(
    private val maxSamples: Int,
    private val windowMillis: Long
) {
    private val frames = ArrayDeque<Bitmap>()
    private var windowStartMillis = 0L

    fun offer(frame: Bitmap, timestamp: Long): List<Bitmap>? {
        if (frames.isEmpty()) {
            windowStartMillis = timestamp
        } else if (timestamp - windowStartMillis > windowMillis) {
            reset()
            windowStartMillis = timestamp
        }

        frames += frame
        if (frames.size < maxSamples) {
            return null
        }

        val batch = ArrayList<Bitmap>(frames)
        frames.clear()
        windowStartMillis = 0L
        return batch
    }

    fun reset() {
        frames.forEach { it.recycleSafely() }
        frames.clear()
        windowStartMillis = 0L
    }
}

private const val FACE_PADDING_RATIO = 0.25f
private const val MIN_FACE_SIZE_RATIO = 0.25f
private const val STABLE_FRAME_THRESHOLD = 5
private const val STABLE_DURATION_MILLIS = 500L
private const val MAX_FRONT_FRAMES_TRACKED = 30
private const val TRACK_STALE_TIMEOUT_MILLIS = 1_200L
private const val FACE_INPUT_SIZE = 112
private const val CENTER_TOLERANCE_RATIO = 0.2f
private const val FRAME_AGGREGATION_COUNT = 3
private const val FRAME_AGGREGATION_WINDOW_MILLIS = 650L
private val ARC_FACE_REFERENCE_POINTS = arrayOf(
    PointF(38.2946f, 51.6963f),
    PointF(73.5318f, 51.5014f),
    PointF(56.0252f, 71.7366f),
    PointF(41.5493f, 92.3655f),
    PointF(70.7299f, 92.2041f)
)

private const val LINEAR_SOLVER_EPS = 1e-8

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
