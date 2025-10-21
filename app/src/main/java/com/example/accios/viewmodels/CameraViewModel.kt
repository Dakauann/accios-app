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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect as OpenCVRect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.io.FileOutputStream
import android.util.Log

class CameraViewModel : ViewModel() {
    private var imageCapture: ImageCapture? = null  // Optional: For photo capture later
    private var camera: androidx.camera.core.Camera? = null
    private var faceCascade: CascadeClassifier? = null
    var detectedFaces = mutableListOf<Rect>()  // Public for UI updates
    private var appContext: Context? = null    // Store context here

    init {
        // Cascade loading will be triggered when context is provided
    }

    private fun loadCascadeClassifier(context: Context) {
        appContext = context.applicationContext // Store for future use
        try {
            val inputStream = context.assets.open("haarcascade_frontalface_default.xml")
            val cascadeFile = File.createTempFile("haarcascade_frontalface_default", ".xml", context.cacheDir)
            FileOutputStream(cascadeFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            faceCascade = CascadeClassifier(cascadeFile.absolutePath).apply {
                if (empty()) {
                    Log.e("CameraView", "Failed to load cascade classifier")
                }
            }
        } catch (e: Exception) {
            Log.e("CameraView", "Error loading cascade: ${e.message}")
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        context: Context,
        onFacesDetected: (List<Rect>) -> Unit = {} // Callback for UI updates
    ) {
        // Load cascade when context is available
        if (faceCascade == null) {
            loadCascadeClassifier(context)
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Image analysis use case for OpenCV processing
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                        processImageProxy(imageProxy, onFacesDetected)
                    }
                }

            imageCapture = ImageCapture.Builder().build()  // Optional

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA  // Use back camera; change to FRONT if needed

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
        if (faceCascade == null || appContext == null) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val faces = MatOfRect()
        faceCascade?.detectMultiScale(grayMat, faces, 1.1, 4) // Adjust parameters for sensitivity

        detectedFaces.clear()
        for (i in 0 until faces.rows()) {
            val face = faces.get(i, 0)[0] as OpenCVRect
            detectedFaces.add(Rect(face.x.toInt(), face.y.toInt(), face.width.toInt(), face.height.toInt()))
            Imgproc.rectangle(mat, face.tl(), face.br(), Scalar(0.0, 255.0, 0.0), 3) // Green box
        }

        Utils.matToBitmap(mat, bitmap)
        onFacesDetected(detectedFaces) // Notify UI of detected faces

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
        appContext = null // Cleanup context
    }

    // Extension function for ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}