package com.gdreducacional.totemapp.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySampleBufferTest {

    @Test
    fun keepsHighestScoresAndEvictsWorst() {
        val evicted = mutableListOf<String>()
        val buffer = QualitySampleBuffer<String>(
            maxSamples = 3,
            matchCount = 2,
            onEvict = { evicted += it }
        )
        buffer.offer("a", score = 0.2f, sizeRatio = 0.12f)
        buffer.offer("b", score = 0.8f, sizeRatio = 0.18f)
        buffer.offer("c", score = 0.5f, sizeRatio = 0.15f)
        buffer.offer("d", score = 0.9f, sizeRatio = 0.20f)

        assertEquals(listOf("a"), evicted)
        assertEquals(listOf("d", "b"), buffer.top(2).map { it.payload })
        assertTrue(buffer.readyToCommit(0.16f))
    }

    @Test
    fun doesNotCommitUntilBestSizeReachesThreshold() {
        val buffer = QualitySampleBuffer<Int>(maxSamples = 5, matchCount = 2)
        buffer.offer(1, 0.9f, 0.12f)
        buffer.offer(2, 0.8f, 0.13f)
        assertTrue(buffer.hasPair())
        assertFalse(buffer.readyToCommit(0.16f))
        buffer.offer(3, 1.0f, 0.17f)
        assertTrue(buffer.readyToCommit(0.16f))
        assertEquals(3, buffer.top(1).first().payload)
    }

    @Test
    fun fastCommitUsesSingleLargeFrame() {
        val buffer = QualitySampleBuffer<Int>(maxSamples = 5, matchCount = 2)
        buffer.offer(1, 0.4f, 0.35f)
        assertTrue(buffer.readyForFastCommit(0.16f))
        assertEquals(1, buffer.matchFrameCount(0.16f))
        buffer.offer(2, 0.5f, 0.36f)
        assertEquals(2, buffer.matchFrameCount(0.16f))
    }

    @Test
    fun clearEvictsAll() {
        val evicted = mutableListOf<Int>()
        val buffer = QualitySampleBuffer<Int>(onEvict = { evicted += it })
        buffer.offer(1, 1f, 0.2f)
        buffer.offer(2, 0.5f, 0.2f)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(setOf(1, 2), evicted.toSet())
    }
}
