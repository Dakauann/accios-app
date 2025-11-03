package com.accioeducacional.totemapp.views

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat

@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    isEnabled: Boolean,
    onQrDetected: (String) -> Unit,
    onScannerError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzer = remember(isEnabled) { QrAnalyzer(onQrDetected, onScannerError) }

    DisposableEffect(isEnabled) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        if (isEnabled) {
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor, analyzer)
                        }

                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        analysis
                    )
                } catch (ex: Exception) {
                    onScannerError(ex.message ?: "Erro ao iniciar câmera")
                }
            }, executor)
        }

        onDispose {
            try {
                analyzer.stop()
                cameraProviderFuture.addListener({
                    try {
                        cameraProviderFuture.get().unbindAll()
                    } catch (_: Exception) {
                    }
                }, executor)
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}
