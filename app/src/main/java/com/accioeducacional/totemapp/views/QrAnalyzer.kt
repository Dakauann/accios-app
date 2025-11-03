package com.accioeducacional.totemapp.views

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class QrAnalyzer(
    private val onQrDetected: (String) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    private val isProcessing = AtomicBoolean(false)
    private val hasResult = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        if (hasResult.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qr = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (qr?.rawValue != null) {
                    val value = qr.rawValue!!
                    if (hasResult.compareAndSet(false, true)) {
                        onQrDetected(value)
                    }
                }
            }
            .addOnFailureListener { ex ->
                Log.e(TAG, "QR scan failure: ${ex.message}")
                onError(ex.message ?: "Erro ao ler QRCode")
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    fun stop() {
        try {
            scanner.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val TAG = "QrAnalyzer"
    }
}
