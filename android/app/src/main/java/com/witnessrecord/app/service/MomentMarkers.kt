package com.witnessrecord.app.service

/**
 * Moments the person marked while recording - a flag tap or a volume-button
 * press. Stored as plain wall-clock times, so a session picks up its own by
 * its start and end without the capture layer having to hand over an ID.
 *
 * They go into the session manifest, which is hashed and independently
 * time-stamped, so a mark is part of the evidence record rather than a note
 * that could be added afterwards. The highlight cut always keeps the seconds
 * around each one.
 */
object MomentMarkers {
    private const val KEY = "momentMarkers"

    /**
     * Kept long enough for any session to be finalized; older ones are
     * already in their manifest.
     */
    private const val KEEP_FOR_MS = 30L * 24 * 3600 * 1000

    @Synchronized
    fun add(timeMs: Long = System.currentTimeMillis()) {
        val cutoff = System.currentTimeMillis() - KEEP_FOR_MS
        val all = read().filter { it > cutoff } + timeMs
        Prefs.setString(KEY, all.joinToString(","))
    }

    /** The marks made from [startMs] to [endMs] (plus a second's slack), oldest first. */
    @Synchronized
    fun between(startMs: Long, endMs: Long): List<Long> =
        read().filter { it >= startMs && it <= endMs + 1_000 }.sorted()

    private fun read(): List<Long> =
        Prefs.getString(KEY)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
}
