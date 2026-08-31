package com.gdreducacional.totemapp.views

data class RankedSample<T>(
    val payload: T,
    val score: Float,
    val sizeRatio: Float
)

class QualitySampleBuffer<T>(
    private val maxSamples: Int = 5,
    private val matchCount: Int = 2,
    private val onEvict: (T) -> Unit = {}
) {
    private val samples = ArrayList<RankedSample<T>>(maxSamples)

    val size: Int get() = samples.size

    val bestSizeRatio: Float get() = samples.maxOfOrNull { it.sizeRatio } ?: 0f

    val bestScore: Float get() = samples.maxOfOrNull { it.score } ?: 0f

    fun offer(payload: T, score: Float, sizeRatio: Float) {
        if (samples.size < maxSamples) {
            samples += RankedSample(payload, score, sizeRatio)
            return
        }
        val worstIndex = samples.indices.minByOrNull { samples[it].score } ?: return
        if (score <= samples[worstIndex].score) {
            onEvict(payload)
            return
        }
        onEvict(samples[worstIndex].payload)
        samples[worstIndex] = RankedSample(payload, score, sizeRatio)
    }

    fun top(n: Int = matchCount): List<RankedSample<T>> {
        return samples.sortedByDescending { it.score }.take(n.coerceAtMost(samples.size))
    }

    fun hasPair(): Boolean = samples.size >= matchCount

    fun readyToCommit(commitSizeRatio: Float): Boolean {
        return hasPair() && bestSizeRatio >= commitSizeRatio
    }

    fun readyForFastCommit(commitSizeRatio: Float): Boolean {
        return samples.isNotEmpty() && bestSizeRatio >= commitSizeRatio
    }

    fun matchFrameCount(commitSizeRatio: Float): Int {
        return when {
            readyForFastCommit(commitSizeRatio) -> size.coerceAtMost(matchCount).coerceAtLeast(1)
            hasPair() -> matchCount
            else -> 0
        }
    }

    fun clear() {
        samples.forEach { onEvict(it.payload) }
        samples.clear()
    }
}
