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
    const val NOISE_SIZE_RATIO = 0.06f
    const val COLLECT_SIZE_RATIO = 0.08f
    const val COMMIT_SIZE_RATIO = 0.12f
    const val MIN_FACE_SIZE_RATIO = COLLECT_SIZE_RATIO
    const val HOLD_SIZE_RATIO = 0.07f
    const val PRIMARY_AREA_RATIO = 0.7f
    const val MIN_VISIBLE_RATIO = 0.55f

    data class VisibleBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val visibleRatio: Float
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun clampVisible(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        frameWidth: Int,
        frameHeight: Int
    ): VisibleBox {
        val origW = (right - left).coerceAtLeast(0)
        val origH = (bottom - top).coerceAtLeast(0)
        val origArea = origW * origH
        val cLeft = left.coerceIn(0, frameWidth)
        val cTop = top.coerceIn(0, frameHeight)
        val cRight = right.coerceIn(0, frameWidth)
        val cBottom = bottom.coerceIn(0, frameHeight)
        val visW = (cRight - cLeft).coerceAtLeast(0)
        val visH = (cBottom - cTop).coerceAtLeast(0)
        val ratio = if (origArea == 0) 0f else (visW * visH).toFloat() / origArea.toFloat()
        return VisibleBox(cLeft, cTop, cRight, cBottom, ratio)
    }

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
        val visible = clampVisible(left, top, right, bottom, frameWidth, frameHeight)
        if (visible.width <= 0 || visible.height <= 0) return FaceCaptureStatus.NONE

        val minSize = if (previouslyReady) HOLD_SIZE_RATIO else MIN_FACE_SIZE_RATIO
        val largeEnough = isFaceLargeEnough(
            visible.width,
            visible.height,
            frameWidth,
            frameHeight,
            minSize
        )
        val noisy = isFaceLargeEnough(
            visible.width,
            visible.height,
            frameWidth,
            frameHeight,
            NOISE_SIZE_RATIO
        )

        if (visible.visibleRatio < MIN_VISIBLE_RATIO) {
            return if (noisy) FaceCaptureStatus.CLIPPED else FaceCaptureStatus.NONE
        }
        if (!largeEnough) {
            return if (noisy) FaceCaptureStatus.TOO_SMALL else FaceCaptureStatus.NONE
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
        val visible = clampVisible(left, top, right, bottom, frameWidth, frameHeight)
        return visible.visibleRatio >= MIN_VISIBLE_RATIO
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

    fun isCommitQuality(
        faceWidth: Int,
        faceHeight: Int,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean = isFaceLargeEnough(faceWidth, faceHeight, frameWidth, frameHeight, COMMIT_SIZE_RATIO)

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
