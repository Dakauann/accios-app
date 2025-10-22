package com.example.accios.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import android.util.Log
import org.opencv.android.Utils
import androidx.compose.runtime.mutableStateListOf
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import com.example.accios.services.FaceRecognitionService

class CameraView : ViewModel() {
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var appContext: Context? = null
    // Tornar observável para Compose
    var detectedFaces = mutableStateListOf<Rect>()
    private var faceService: FaceRecognitionService? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<Rect>) -> Unit = {}
    ) {
        appContext = context.applicationContext
        faceService = FaceRecognitionService(context)

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
                    it.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
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
        if (faceService == null) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert RGBA->BGR for processing if needed
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)

        val overlays = faceService!!.processFrame(bgr)

        detectedFaces.clear()
        for (r in overlays) {
            detectedFaces.add(r)
            // desenha retângulo sobre o frame original (mat é RGBA)
            Imgproc.rectangle(
                mat,
                Point(r.left.toDouble(), r.top.toDouble()),
                Point(r.right.toDouble(), r.bottom.toDouble()),
                Scalar(0.0, 255.0, 0.0),
                3
            )
        }

        Utils.matToBitmap(mat, bitmap)
        onFacesDetected(detectedFaces)

        // liberar mats
        try { bgr.release() } catch (_: Exception) {}
        try { mat.release() } catch (_: Exception) {}

        imageProxy.close()
    }

    fun unbindCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onCleared() {
        super.onCleared()
        appContext = null
        faceService = null
    }
}