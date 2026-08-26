package com.gdreducacional.totemapp.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.graphics.Paint
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.gdreducacional.totemapp.services.FaceDetectionResult
import com.gdreducacional.totemapp.services.FaceRecognitionService
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class CameraView : ViewModel() {
    private var camera: androidx.camera.core.Camera? = null
    private var appContext: Context? = null
    var detectedFaces = mutableStateListOf<DetectedFace>()
    private var faceService: FaceRecognitionService? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var recognitionCallback: ((RecognitionCandidate) -> Unit)? = null
    private var luminanceCallback: ((Float) -> Unit)? = null
    private val recognitionInFlight = AtomicBoolean(false)
    private val faceTrackStates = mutableMapOf<Int, FaceTrackState>()
    private var lastLuminanceNotified: Float? = null
    private var currentLuminance = 128f
    private var lastExposureIndex: Int? = null
    private var lastGateReason: String? = null
    private var readyLocked = false
    @Volatile private var lastCaptureStatus = FaceCaptureStatus.NONE
    private var fpsWindowStart = 0L
    private var fpsFrameCount = 0
    private var globalHitCooldownUntil = 0L

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<FaceDetectionResult>) -> Unit = {},
        onCaptureStatus: (FaceCaptureStatus) -> Unit = {},
        onRecognitionCandidate: (RecognitionCandidate) -> Unit = {},
        onAmbientLuminance: (Float) -> Unit = {}
    ) {
        appContext = context.applicationContext
        faceService = FaceRecognitionService()
        recognitionCallback = onRecognitionCandidate
        luminanceCallback = onAmbientLuminance
        lastLuminanceNotified = null
        lastExposureIndex = null
        lastGateReason = null
        readyLocked = false
        lastCaptureStatus = FaceCaptureStatus.NONE
        fpsWindowStart = 0L
        fpsFrameCount = 0
        globalHitCooldownUntil = 0L

        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(ANALYSIS_TARGET_WIDTH, ANALYSIS_TARGET_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(
                            imageProxy,
                            onFacesDetected,
                            { status -> mainExecutor.execute { onCaptureStatus(status) } }
                        )
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraView", "Camera bind failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(
        imageProxy: ImageProxy,
        onFacesDetected: (List<FaceDetectionResult>) -> Unit,
        onCaptureStatus: (FaceCaptureStatus) -> Unit
    ) {
        val service = faceService
        if (service == null) {
            imageProxy.close()
            return
        }

        val frameStart = SystemClock.elapsedRealtime()
        val luminance = estimateLuminance(imageProxy)
        currentLuminance = luminance
        applyExposureForLuminance(luminance)
        luminanceCallback?.let { callback ->
            val last = lastLuminanceNotified
            if (last == null || abs(last - luminance) > 1.5f) {
                lastLuminanceNotified = luminance
                callback.invoke(luminance)
            }
        }

        val detectStart = SystemClock.elapsedRealtime()
        val overlays = service.processFrame(imageProxy)
        val detectMs = SystemClock.elapsedRealtime() - detectStart
        val (frameWidth, frameHeight) = rotatedFrameSize(imageProxy)

        detectedFaces.clear()
        if (overlays.isEmpty()) {
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

        val recognitionTarget = pickPrimaryDetection(overlays)
        val captureStatus = if (recognitionTarget == null) {
            FaceCaptureStatus.NONE
        } else {
            FaceCaptureGate.evaluate(
                recognitionTarget.rect.left,
                recognitionTarget.rect.top,
                recognitionTarget.rect.right,
                recognitionTarget.rect.bottom,
                frameWidth,
                frameHeight,
                previouslyReady = readyLocked
            )
        }
        readyLocked = captureStatus == FaceCaptureStatus.READY
        lastCaptureStatus = captureStatus
        onCaptureStatus(captureStatus)

        if (recognitionTarget != null &&
            captureStatus == FaceCaptureStatus.READY &&
            !recognitionInFlight.get()
        ) {
            collectWalkUpSample(imageProxy, recognitionTarget, frameWidth, frameHeight)
        } else if (recognitionTarget != null) {
            logGate(
                if (recognitionInFlight.get()) "in_flight" else captureStatus.name.lowercase(),
                recognitionTarget.rect,
                frameWidth,
                frameHeight
            )
        } else {
            lastGateReason = "none"
        }

        tickAnalysisFps(frameStart, detectMs, frameWidth, frameHeight)
        imageProxy.close()
    }

    private fun collectWalkUpSample(
        imageProxy: ImageProxy,
        detection: FaceDetectionResult,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val callback = recognitionCallback ?: return
        if (!detection.isFrontFacing) {
            logGate("no_front", detection.rect, frameWidth, frameHeight)
            return
        }
        val trackingId = detection.trackingId ?: FALLBACK_TRACK_ID
        val trackState = faceTrackStates[trackingId] ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < globalHitCooldownUntil || now < trackState.cooldownUntilMillis) {
            logGate("cooldown", detection.rect, frameWidth, frameHeight)
            return
        }

        val cropStart = now
        val aligned = cropAndAlignFace(imageProxy, detection) ?: return
        val cropMs = SystemClock.elapsedRealtime() - cropStart

        val sharpness = estimateSharpness(aligned)
        val visible = FaceCaptureGate.clampVisible(
            detection.rect.left,
            detection.rect.top,
            detection.rect.right,
            detection.rect.bottom,
            frameWidth,
            frameHeight
        )
        val sizeRatio = min(visible.width, visible.height).toFloat() /
            min(frameWidth, frameHeight).toFloat()
        val pose = poseFactor(detection.yaw, detection.pitch)
        val sharpNorm = (sharpness / SHARPNESS_REFERENCE).coerceIn(0.2f, 1f)
        val score = sizeRatio * pose * sharpNorm
        trackState.buffer.offer(aligned, score, sizeRatio)

        val wellFramed = visible.visibleRatio >= WELL_FRAMED_VISIBLE_RATIO
        val commit = wellFramed &&
            trackState.buffer.readyForFastCommit(FaceCaptureGate.COMMIT_SIZE_RATIO)
        val frameCount = if (!wellFramed) {
            if (trackState.buffer.hasPair()) FRAME_AGGREGATION_COUNT else 0
        } else {
            trackState.buffer.matchFrameCount(FaceCaptureGate.COMMIT_SIZE_RATIO)
        }
        val minInterval = if (commit) COMMIT_ATTEMPT_INTERVAL_MILLIS else SILENT_ATTEMPT_INTERVAL_MILLIS
        if (frameCount == 0 || now - trackState.lastAttemptMillis < minInterval) {
            Log.d(
                TAG,
                "sample track=$trackingId score=${"%.3f".format(score)} size=${"%.3f".format(sizeRatio)} " +
                    "pose=${"%.2f".format(pose)} sharp=${"%.1f".format(sharpness)} lum=${"%.0f".format(currentLuminance)} " +
                    "cropMs=$cropMs buf=${trackState.buffer.size}"
            )
            return
        }

        val top = trackState.buffer.top(frameCount)
        if (top.isEmpty()) return
        val copies = top.map { sample ->
            sample.payload.copy(Bitmap.Config.ARGB_8888, false)
        }
        trackState.lastAttemptMillis = now
        val forceAnnounce = commit && (trackState.commitMisses + 1) >= MAX_COMMIT_MISSES
        recognitionInFlight.set(true)
        logGate(if (commit) "commit" else "silent", detection.rect, frameWidth, frameHeight)
        Log.i(
            TAG,
            "match-attempt track=$trackingId commit=$commit frames=${top.size} force=$forceAnnounce " +
                "score=${"%.3f".format(top.first().score)} bestSize=${"%.3f".format(trackState.buffer.bestSizeRatio)} " +
                "cropMs=$cropMs lum=${"%.0f".format(currentLuminance)} misses=${trackState.commitMisses}"
        )
        callback.invoke(
            RecognitionCandidate(
                trackId = trackingId,
                frames = copies,
                commitQuality = commit,
                forceAnnounce = forceAnnounce,
                qualityScore = top.first().score
            )
        )
    }

    private fun cropAndAlignFace(imageProxy: ImageProxy, detection: FaceDetectionResult): Bitmap? {
        val full = try {
            imageProxy.toBitmap()
        } catch (ex: Exception) {
            Log.w(TAG, "toBitmap falhou: ${ex.message}")
            return null
        }
        var working = full
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
        if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
            if (rotated !== working) {
                working.recycleSafely()
            }
            working = rotated
        }

        val bounded = expandRect(detection.rect, working.width, working.height)
        val region = try {
            Bitmap.createBitmap(working, bounded.left, bounded.top, bounded.width(), bounded.height())
        } catch (ex: Exception) {
            Log.w(TAG, "Falha ao recortar face: ${ex.message}")
            working.recycleSafely()
            return null
        }
        if (region !== working) {
            working.recycleSafely()
        }

        val aligned = alignFaceBitmap(region, detection, bounded) ?: scaleToFaceInput(region)
        if (aligned !== region) {
            region.recycleSafely()
        }
        return aligned
    }

    private fun applyExposureForLuminance(luminance: Float) {
        val cam = camera ?: return
        val exposure = cam.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return
        val range = exposure.exposureCompensationRange
        val target = when {
            luminance < 45f -> range.upper
            luminance < LOW_LIGHT_LUMINANCE -> (range.upper * 2 / 3).coerceAtLeast(range.lower)
            else -> 0
        }.coerceIn(range.lower, range.upper)
        if (lastExposureIndex == target) return
        lastExposureIndex = target
        cam.cameraControl.setExposureCompensationIndex(target)
    }

    private fun logGate(reason: String, rect: Rect, frameWidth: Int, frameHeight: Int) {
        if (reason == lastGateReason) return
        lastGateReason = reason
        Log.i(
            TAG,
            "gate reason=$reason rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}] " +
                "frame=${frameWidth}x${frameHeight}"
        )
    }

    private fun pickPrimaryDetection(detections: List<FaceDetectionResult>): FaceDetectionResult? {
        val primary = FaceCaptureGate.selectPrimary(
            detections.map { detection ->
                FaceBox(
                    left = detection.rect.left,
                    top = detection.rect.top,
                    right = detection.rect.right,
                    bottom = detection.rect.bottom,
                    isFrontFacing = detection.isFrontFacing
                )
            }
        ) ?: return null
        return detections.firstOrNull { detection ->
            detection.rect.left == primary.left &&
                detection.rect.top == primary.top &&
                detection.rect.right == primary.right &&
                detection.rect.bottom == primary.bottom &&
                detection.isFrontFacing == primary.isFrontFacing
        }
    }

    fun unbindCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
        luminanceCallback = null
        lastLuminanceNotified = null
        lastExposureIndex = null
        lastGateReason = null
        readyLocked = false
        lastCaptureStatus = FaceCaptureStatus.NONE
        recognitionInFlight.set(false)
        clearTrackStates()
    }

    fun hasLivePrimaryFace(): Boolean = lastCaptureStatus == FaceCaptureStatus.READY

    fun onRecognitionProcessed(success: Boolean, trackId: Int? = null) {
        val now = SystemClock.elapsedRealtime()
        recognitionInFlight.set(false)
        val state = trackId?.let { faceTrackStates[it] }
        if (success) {
            globalHitCooldownUntil = now + TRACK_HIT_COOLDOWN_MILLIS
            if (state != null) {
                state.cooldownUntilMillis = now + TRACK_HIT_COOLDOWN_MILLIS
                state.commitMisses = 0
                state.buffer.clear()
            } else {
                faceTrackStates.values.forEach { it.buffer.clear() }
            }
            faceService?.resetTracker()
        } else if (state != null) {
            if (state.buffer.readyForFastCommit(FaceCaptureGate.COMMIT_SIZE_RATIO)) {
                state.commitMisses += 1
            }
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
            val id = result.trackingId ?: FALLBACK_TRACK_ID
            activeIds += id
            val state = faceTrackStates.getOrPut(id) { FaceTrackState() }
            state.lastSeenMillis = now
            state.lastBoundingRect = Rect(result.rect)
            if (result.isFrontFacing) {
                state.consecutiveFrontFrames = (state.consecutiveFrontFrames + 1).coerceAtMost(MAX_FRONT_FRAMES_TRACKED)
            } else {
                state.consecutiveFrontFrames = 0
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

    private fun tickAnalysisFps(frameStart: Long, detectMs: Long, frameWidth: Int, frameHeight: Int) {
        fpsFrameCount += 1
        if (fpsWindowStart == 0L) {
            fpsWindowStart = frameStart
            return
        }
        val elapsed = frameStart - fpsWindowStart
        if (elapsed >= 1_000L) {
            val fps = fpsFrameCount * 1000f / elapsed
            Log.i(
                TAG,
                "pipeline fps=${"%.1f".format(fps)} detectMs=$detectMs " +
                    "frame=${frameWidth}x${frameHeight} inFlight=${recognitionInFlight.get()}"
            )
            fpsWindowStart = frameStart
            fpsFrameCount = 0
        }
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

private fun scaleToFaceInput(region: Bitmap): Bitmap {
    if (region.width == FACE_INPUT_SIZE && region.height == FACE_INPUT_SIZE) {
        return region
    }
    return Bitmap.createScaledBitmap(region, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true)
}

private fun alignFaceBitmap(region: Bitmap, detection: FaceDetectionResult, regionBounds: Rect): Bitmap? {
    val landmarks = detection.landmarks
        ?: FaceRecognitionService.syntheticLandmarks(regionBounds)

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
        return scaleToFaceInput(region)
    }

    val matrix = estimateSimilarityTransform(sourcePoints, targetPoints)
        ?: return scaleToFaceInput(region)

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
    val frames: List<Bitmap>,
    val commitQuality: Boolean = false,
    val forceAnnounce: Boolean = false,
    val qualityScore: Float = 0f
)

private class FaceTrackState {
    var consecutiveFrontFrames: Int = 0
    var lastSeenMillis: Long = 0L
    var lastBoundingRect: Rect? = null
    var lastAttemptMillis: Long = 0L
    var cooldownUntilMillis: Long = 0L
    var commitMisses: Int = 0
    val buffer = QualitySampleBuffer<Bitmap>(
        maxSamples = QUALITY_BUFFER_SIZE,
        matchCount = FRAME_AGGREGATION_COUNT
    ) { it.recycleSafely() }

    fun clearFrames() {
        buffer.clear()
        commitMisses = 0
    }
}

private fun poseFactor(yaw: Float, pitch: Float): Float {
    val yawTerm = (1f - (abs(yaw) / 35f)).coerceIn(0.2f, 1f)
    val pitchTerm = (1f - (abs(pitch) / 40f)).coerceIn(0.2f, 1f)
    return yawTerm * pitchTerm
}

private fun estimateSharpness(bitmap: Bitmap): Float {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 3 || height < 3) return 0f
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    var sum = 0.0
    var count = 0
    var index = width + 1
    val last = width * height - width - 1
    while (index < last) {
        val center = luminance(pixels[index])
        val lap = abs(4 * center - luminance(pixels[index - 1]) - luminance(pixels[index + 1]) -
            luminance(pixels[index - width]) - luminance(pixels[index + width]))
        sum += lap
        count++
        index += 2
    }
    return if (count == 0) 0f else (sum / count).toFloat()
}

private fun luminance(pixel: Int): Int {
    val r = pixel shr 16 and 0xFF
    val g = pixel shr 8 and 0xFF
    val b = pixel and 0xFF
    return (r * 30 + g * 59 + b * 11) / 100
}

private const val FACE_PADDING_RATIO = 0.25f
private const val MAX_FRONT_FRAMES_TRACKED = 30
private const val TRACK_STALE_TIMEOUT_MILLIS = 1_200L
private const val FACE_INPUT_SIZE = 112
private const val FRAME_AGGREGATION_COUNT = 2
private const val QUALITY_BUFFER_SIZE = 5
private const val FALLBACK_TRACK_ID = -1
private const val ANALYSIS_TARGET_WIDTH = 640
private const val ANALYSIS_TARGET_HEIGHT = 480
private const val LOW_LIGHT_LUMINANCE = 70f
private const val SHARPNESS_REFERENCE = 40f
private const val SILENT_ATTEMPT_INTERVAL_MILLIS = 400L
private const val COMMIT_ATTEMPT_INTERVAL_MILLIS = 280L
private const val TRACK_HIT_COOLDOWN_MILLIS = 2_800L
private const val WELL_FRAMED_VISIBLE_RATIO = 0.80f
private const val MAX_COMMIT_MISSES = 3
private val ARC_FACE_REFERENCE_POINTS = arrayOf(
    PointF(38.2946f, 51.6963f),
    PointF(73.5318f, 51.5014f),
    PointF(56.0252f, 71.7366f),
    PointF(41.5493f, 92.3655f),
    PointF(70.7299f, 92.2041f)
)

private const val LINEAR_SOLVER_EPS = 1e-8

private const val TAG = "CameraView"

private fun rotatedFrameSize(imageProxy: ImageProxy): Pair<Int, Int> {
    val rotation = imageProxy.imageInfo.rotationDegrees
    return if (rotation == 90 || rotation == 270) {
        imageProxy.height to imageProxy.width
    } else {
        imageProxy.width to imageProxy.height
    }
}
