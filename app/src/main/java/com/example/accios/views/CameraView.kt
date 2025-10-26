package com.example.accios.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
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
import com.example.accios.services.FaceRecognitionService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraView : ViewModel() {
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var appContext: Context? = null
    // Tornar observável para Compose
    var detectedFaces = mutableStateListOf<Rect>()
    private var faceService: FaceRecognitionService? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var recognitionCallback: ((Bitmap) -> Unit)? = null
    private val recognitionInFlight = AtomicBoolean(false)
    private var lastRecognitionTimestamp = 0L
    private val recognitionCooldownMillis = 3_000L

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<Rect>) -> Unit = {},
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

    private fun processImageProxy(imageProxy: ImageProxy, onFacesDetected: (List<Rect>) -> Unit) {
        val service = faceService
        if (service == null) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        val overlays = service.processFrame(bitmap)

        detectedFaces.clear()
        if (overlays.isEmpty()) {
            Log.d(TAG, "Nenhuma face detectada.")
            onFacesDetected(emptyList())
        } else {
            Log.d(TAG, "${overlays.size} face(s) detectada(s).")
            overlays.forEach { result ->
                detectedFaces.add(result.rect)
            }
            onFacesDetected(detectedFaces)
        }

        maybeTriggerRecognition(bitmap, overlays.firstOrNull()?.rect)

        imageProxy.close()
    }

    private fun maybeTriggerRecognition(frameBitmap: Bitmap, rect: Rect?) {
        val callback = recognitionCallback ?: return
        if (rect == null) return
        if (recognitionInFlight.get()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRecognitionTimestamp < recognitionCooldownMillis) return

        val bounded = Rect(
            rect.left.coerceAtLeast(0),
            rect.top.coerceAtLeast(0),
            rect.right.coerceAtMost(frameBitmap.width),
            rect.bottom.coerceAtMost(frameBitmap.height)
        )

        if (bounded.width() <= 0 || bounded.height() <= 0) return

        try {
            val faceBitmap = Bitmap.createBitmap(
                frameBitmap,
                bounded.left,
                bounded.top,
                bounded.width(),
                bounded.height()
            )
            recognitionInFlight.set(true)
            callback.invoke(faceBitmap)
        } catch (ex: Exception) {
            Log.w("CameraView", "Falha ao recortar face: ${ex.message}")
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

    override fun onCleared() {
        super.onCleared()
        appContext = null
        faceService?.shutdown()
        faceService = null
        cameraExecutor.shutdown()
    }
}

// Extensão para converter ImageProxy em Bitmap já rotacionado para a orientação do display
private fun ImageProxy.toBitmap(): Bitmap {
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

private const val TAG = "CameraView"
