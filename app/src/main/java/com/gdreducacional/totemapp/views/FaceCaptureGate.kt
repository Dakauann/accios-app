package com.gdreducacional.totemapp.views

import kotlin.math.min

enum class FaceCaptureStatus {
    NONE,
    CLIPPED,
    TOO_SMALL,
    READY
}

data class FaceBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isFrontFacing: Boolean = true
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width.coerceAtLeast(0) * height.coerceAtLeast(0)
}

object FaceCaptureGate {
    const val EDGE_MARGIN_RATIO = 0.03f
    const val HOLD_EDGE_MARGIN_RATIO = 0.005f
    const val NOISE_SIZE_RATIO = 0.12f
    const val MIN_FACE_SIZE_RATIO = 0.20f
    const val HOLD_SIZE_RATIO = 0.14f
    const val PRIMARY_AREA_RATIO = 0.7f

    fun evaluate(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        frameWidth: Int,
        frameHeight: Int,
        previouslyReady: Boolean = false
    ): FaceCaptureStatus {
        if (frameWidth <= 0 || frameHeight <= 0) return FaceCaptureStatus.NONE
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return FaceCaptureStatus.NONE

        val edgeMargin = if (previouslyReady) HOLD_EDGE_MARGIN_RATIO else EDGE_MARGIN_RATIO
        val minSize = if (previouslyReady) HOLD_SIZE_RATIO else MIN_FACE_SIZE_RATIO

        if (!isFaceFullyVisible(left, top, right, bottom, frameWidth, frameHeight, edgeMargin)) {
            return if (isFaceLargeEnough(width, height, frameWidth, frameHeight, NOISE_SIZE_RATIO)) {
                FaceCaptureStatus.CLIPPED
            } else {
                FaceCaptureStatus.NONE
            }
        }
        if (!isFaceLargeEnough(width, height, frameWidth, frameHeight, minSize)) {
            return if (isFaceLargeEnough(width, height, frameWidth, frameHeight, NOISE_SIZE_RATIO)) {
                FaceCaptureStatus.TOO_SMALL
            } else {
                FaceCaptureStatus.NONE
            }
        }
        return FaceCaptureStatus.READY
    }

    fun isFaceFullyVisible(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        frameWidth: Int,
        frameHeight: Int,
        edgeMarginRatio: Float = EDGE_MARGIN_RATIO
    ): Boolean {
        val margin = min(frameWidth, frameHeight) * edgeMarginRatio
        return left >= margin &&
            top >= margin &&
            right <= frameWidth - margin &&
            bottom <= frameHeight - margin
    }

    fun isFaceLargeEnough(
        faceWidth: Int,
        faceHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        minSizeRatio: Float = MIN_FACE_SIZE_RATIO
    ): Boolean {
        val minSize = min(frameWidth, frameHeight) * minSizeRatio
        return faceWidth >= minSize && faceHeight >= minSize
    }

    /**
     * Pessoa do totem: a cara mais próxima da câmera (maior área).
     * Faces menores da fila não viram alvo, mesmo olhando para a câmera.
     * Se a maior cara não está frontal, espera essa pessoa — não cai na fila.
     */
    fun selectPrimary(faces: List<FaceBox>): FaceBox? {
        if (faces.isEmpty()) return null
        val largestArea = faces.maxOf { it.area }
        if (largestArea <= 0) return null
        val minPrimaryArea = (largestArea * PRIMARY_AREA_RATIO).toInt()
        return faces
            .filter { it.isFrontFacing && it.area >= minPrimaryArea }
            .maxByOrNull { it.area }
    }
}
