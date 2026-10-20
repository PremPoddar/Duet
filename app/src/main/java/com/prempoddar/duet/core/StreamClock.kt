package com.prempoddar.duet.core

/**
 * The playing position of a player that only reports its time now and then, and late. Each report
 * is "the video was at [media] ms" received at [at]; carried forward, a stale report lands behind
 * the truth and never ahead, so the best guess is the furthest-ahead of the recent ones (an upper
 * envelope). Only the last couple of seconds count, so a stall shows within that time.
 */
class StreamClock(private val windowMs: Long = 2_500) {
    private val reports = ArrayDeque<Pair<Long, Long>>()
    /** How fast the video is playing; reports are carried forward at this rate. */
    var rate = 1.0
        private set

    @Synchronized fun reset() = reports.clear()

    @Synchronized fun setRate(r: Double) { if (r != rate) { rate = r; reports.clear() } }

    @Synchronized fun add(at: Long, media: Long) {
        // Far behind the envelope means the video jumped back or stalled: start again.
        current(at)?.let { if (media < it - JUMP_MS) reports.clear() }
        reports.addLast(at to media)
        while (reports.isNotEmpty() && at - reports.first().first > windowMs) reports.removeFirst()
    }

    @Synchronized fun now(at: Long): Long? = current(at)

    private fun current(at: Long): Long? = reports.maxOfOrNull { (t, m) -> m + ((at - t) * rate).toLong() }

    companion object { private const val JUMP_MS = 1_500L }
}
