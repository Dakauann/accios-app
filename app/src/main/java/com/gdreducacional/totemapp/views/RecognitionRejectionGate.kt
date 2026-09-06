package com.gdreducacional.totemapp.views

/** Holds a confirmed rejection across frames, including brief detector dropouts. */
class RecognitionRejectionGate {
    private var rejectedTrack: Int? = null
    private var minimumUntil = 0L
    private var absentSince: Long? = null

    @Synchronized
    fun reject(trackId: Int, now: Long) {
        rejectedTrack = trackId
        minimumUntil = now + 2_000L
        absentSince = null
    }

    @Synchronized
    fun observe(trackIds: Set<Int>, now: Long) {
        val rejected = rejectedTrack ?: return
        if (rejected in trackIds) {
            absentSince = null
        } else if (absentSince == null) {
            absentSince = now
        }
    }

    @Synchronized
    fun isBlocked(now: Long): Boolean {
        if (rejectedTrack == null) return false
        val absent = absentSince
        if (now >= minimumUntil && absent != null && now - absent >= 500L) {
            rejectedTrack = null
            absentSince = null
            return false
        }
        return true
    }
}
