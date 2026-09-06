package com.gdreducacional.totemapp.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRejectionGateTest {
    @Test fun samePersonRemainsBlockedBeyondMinimum() {
        val gate = RecognitionRejectionGate()
        gate.reject(1, 0)
        gate.observe(setOf(1), 10_000)
        assertTrue(gate.isBlocked(10_000))
    }

    @Test fun departureCannotShortenTwoSecondMessage() {
        val gate = RecognitionRejectionGate()
        gate.reject(1, 0)
        gate.observe(emptySet(), 100)
        assertTrue(gate.isBlocked(1_999))
        assertFalse(gate.isBlocked(2_000))
    }

    @Test fun briefDropoutDoesNotRestartRecognition() {
        val gate = RecognitionRejectionGate()
        gate.reject(1, 0)
        gate.observe(emptySet(), 3_000)
        assertTrue(gate.isBlocked(3_499))
        gate.observe(setOf(1), 3_499)
        assertTrue(gate.isBlocked(5_000))
    }

    @Test fun newTrackCanProceedAfterStableReplacement() {
        val gate = RecognitionRejectionGate()
        gate.reject(1, 0)
        gate.observe(setOf(2), 2_000)
        assertTrue(gate.isBlocked(2_499))
        assertFalse(gate.isBlocked(2_500))
    }

    @Test fun personInQueueDoesNotReleaseRejectedPerson() {
        val gate = RecognitionRejectionGate()
        gate.reject(1, 0)
        gate.observe(setOf(1, 2), 3_000)
        assertTrue(gate.isBlocked(5_000))
    }

    @Test fun nextRejectionStartsANewMinimum() {
        val gate = RecognitionRejectionGate()
        assertFalse(gate.isBlocked(0))
        gate.reject(1, 0)
        gate.observe(emptySet(), 100)
        assertFalse(gate.isBlocked(2_000))
        gate.reject(2, 3_000)
        gate.observe(emptySet(), 3_100)
        assertTrue(gate.isBlocked(4_999))
        assertFalse(gate.isBlocked(5_000))
    }
}
