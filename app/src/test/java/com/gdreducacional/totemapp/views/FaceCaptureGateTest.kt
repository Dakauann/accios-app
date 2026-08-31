package com.gdreducacional.totemapp.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCaptureGateTest {

    @Test
    fun upperThirdFullyVisibleFace_isReady() {
        val status = FaceCaptureGate.evaluate(
            left = FACE_LEFT,
            top = 80,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = 80 + FACE_HEIGHT,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
    }

    @Test
    fun centeredFullyVisibleFace_isReady() {
        val top = (FRAME_HEIGHT - FACE_HEIGHT) / 2
        val status = FaceCaptureGate.evaluate(
            left = FACE_LEFT,
            top = top,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = top + FACE_HEIGHT,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
    }

    @Test
    fun lowerThirdFullyVisibleFace_isReady() {
        val top = FRAME_HEIGHT - 80 - FACE_HEIGHT
        val status = FaceCaptureGate.evaluate(
            left = FACE_LEFT,
            top = top,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = top + FACE_HEIGHT,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
    }

    @Test
    fun faceFlushWithTop_isReadyToCollect() {
        val status = FaceCaptureGate.evaluate(
            left = FACE_LEFT,
            top = 0,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = FACE_HEIGHT,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
    }

    @Test
    fun faceAboveFrame_walkUpStillReady() {
        val status = FaceCaptureGate.evaluate(
            left = 200,
            top = -40,
            right = 360,
            bottom = 160,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
    }

    @Test
    fun mostlyOffScreen_isClipped() {
        val status = FaceCaptureGate.evaluate(
            left = FACE_LEFT,
            top = -180,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = 80,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.CLIPPED, status)
    }

    @Test
    fun farNoiseFace_isIgnored() {
        val width = 40
        val height = 45
        val left = (FRAME_WIDTH - width) / 2
        val top = (FRAME_HEIGHT - height) / 2
        val status = FaceCaptureGate.evaluate(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.NONE, status)
    }

    @Test
    fun approachingFace_isReadyToCollect() {
        val width = 140
        val height = 150
        val left = (FRAME_WIDTH - width) / 2
        val top = (FRAME_HEIGHT - height) / 2
        val status = FaceCaptureGate.evaluate(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.READY, status)
        assertTrue(
            FaceCaptureGate.isCommitQuality(width, height, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun walkUpFace_collectsBeforeCommitSize() {
        val width = 70
        val height = 72
        assertEquals(
            FaceCaptureStatus.READY,
            FaceCaptureGate.evaluate(
                left = 200,
                top = 200,
                right = 200 + width,
                bottom = 200 + height,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT
            )
        )
        assertFalse(
            FaceCaptureGate.isCommitQuality(width, height, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun sizeHysteresis_keepsReadyWhenBoxShrinksSlightly() {
        val width = 55
        val height = 58
        val left = (FRAME_WIDTH - width) / 2
        val top = (FRAME_HEIGHT - height) / 2
        assertEquals(
            FaceCaptureStatus.TOO_SMALL,
            FaceCaptureGate.evaluate(
                left, top, left + width, top + height,
                FRAME_WIDTH, FRAME_HEIGHT,
                previouslyReady = false
            )
        )
        assertEquals(
            FaceCaptureStatus.READY,
            FaceCaptureGate.evaluate(
                left, top, left + width, top + height,
                FRAME_WIDTH, FRAME_HEIGHT,
                previouslyReady = true
            )
        )
    }

    @Test
    fun tinyClippedNoise_isIgnored() {
        val status = FaceCaptureGate.evaluate(
            left = 0,
            top = 40,
            right = 30,
            bottom = 75,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.NONE, status)
    }

    @Test
    fun upperThirdFaceWouldFailOldCenterGate_butPassesVisibility() {
        val top = 80
        val bottom = top + FACE_HEIGHT
        val centerY = (top + bottom) / 2f
        val frameCenterY = FRAME_HEIGHT / 2f
        val oldToleranceY = FRAME_HEIGHT * OLD_CENTER_TOLERANCE_RATIO / 2f
        assertTrue(
            "cenário do bug: cara alta fora da faixa 40–60%",
            kotlin.math.abs(centerY - frameCenterY) > oldToleranceY
        )
        assertTrue(
            FaceCaptureGate.isFaceFullyVisible(
                FACE_LEFT,
                top,
                FACE_LEFT + FACE_WIDTH,
                bottom,
                FRAME_WIDTH,
                FRAME_HEIGHT
            )
        )
        assertEquals(
            FaceCaptureStatus.READY,
            FaceCaptureGate.evaluate(
                FACE_LEFT,
                top,
                FACE_LEFT + FACE_WIDTH,
                bottom,
                FRAME_WIDTH,
                FRAME_HEIGHT
            )
        )
    }

    @Test
    fun invalidRect_isNone() {
        val status = FaceCaptureGate.evaluate(
            left = 10,
            top = 10,
            right = 10,
            bottom = 50,
            frameWidth = FRAME_WIDTH,
            frameHeight = FRAME_HEIGHT
        )
        assertEquals(FaceCaptureStatus.NONE, status)
    }

    @Test
    fun largeEnoughUsesOriginalBoxNotExpanded() {
        assertTrue(
            FaceCaptureGate.isFaceLargeEnough(
                faceWidth = FACE_WIDTH,
                faceHeight = FACE_HEIGHT,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT
            )
        )
        assertFalse(
            FaceCaptureGate.isFaceLargeEnough(
                faceWidth = 40,
                faceHeight = 45,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT
            )
        )
        assertFalse(
            FaceCaptureGate.isCommitQuality(70, 72, FRAME_WIDTH, FRAME_HEIGHT)
        )
        assertTrue(
            FaceCaptureGate.isCommitQuality(140, 150, FRAME_WIDTH, FRAME_HEIGHT)
        )
    }

    @Test
    fun queueBehindDoesNotStealPrimary_picksLargestFrontFace() {
        val front = FaceBox(
            left = FACE_LEFT,
            top = 80,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = 80 + FACE_HEIGHT,
            isFrontFacing = true
        )
        val behind = FaceBox(
            left = 80,
            top = 200,
            right = 80 + 90,
            bottom = 200 + 110,
            isFrontFacing = true
        )
        val primary = FaceCaptureGate.selectPrimary(listOf(behind, front))
        assertEquals(front, primary)
        assertEquals(
            FaceCaptureStatus.READY,
            FaceCaptureGate.evaluate(
                primary!!.left,
                primary.top,
                primary.right,
                primary.bottom,
                FRAME_WIDTH,
                FRAME_HEIGHT
            )
        )
    }

    @Test
    fun frontPersonNotLooking_doesNotFallBackToQueue() {
        val front = FaceBox(
            left = FACE_LEFT,
            top = 80,
            right = FACE_LEFT + FACE_WIDTH,
            bottom = 80 + FACE_HEIGHT,
            isFrontFacing = false
        )
        val behind = FaceBox(
            left = 80,
            top = 200,
            right = 80 + 90,
            bottom = 200 + 110,
            isFrontFacing = true
        )
        assertEquals(null, FaceCaptureGate.selectPrimary(listOf(front, behind)))
    }

    @Test
    fun onlyQueueFaces_picksLargestLooking() {
        val closerInQueue = FaceBox(100, 300, 220, 450, isFrontFacing = true)
        val fartherInQueue = FaceBox(400, 320, 480, 420, isFrontFacing = true)
        assertEquals(closerInQueue, FaceCaptureGate.selectPrimary(listOf(fartherInQueue, closerInQueue)))
    }

    companion object {
        private const val FRAME_WIDTH = 720
        private const val FRAME_HEIGHT = 1280
        private const val FACE_WIDTH = 220
        private const val FACE_HEIGHT = 260
        private const val FACE_LEFT = (FRAME_WIDTH - FACE_WIDTH) / 2
        private const val OLD_CENTER_TOLERANCE_RATIO = 0.2f
    }
}
