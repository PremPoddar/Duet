package com.prempoddar.duet.core

/**
 * Estimates the host's clock from ours, the way NTP and Snapcast do.
 *
 * Each ping gives an offset whose error is at most half its round trip, and a ping that waited
 * behind song data on the way back can be off by seconds. So only pings that came back about as
 * fast as the fastest ever are trusted, and they are kept for minutes rather than the last few.
 * Phone clocks also run at slightly different rates (tens of parts per million, which is a
 * hundred milliseconds or more over an hour), so with trusted pings spread over a minute or more
 * the offset is fitted as a line over time rather than taken as a constant.
 */
class ClockSync(private val keep: Int = 300, private val maxAgeMs: Long = 10 * 60_000L) {
    private class Sample(val at: Long, val rtt: Long, val offset: Long)
    private val samples = ArrayDeque<Sample>()
    private var bestRtt = Long.MAX_VALUE
    // The fitted line: offset = base + slope * (localNow - origin).
    private var origin = 0L
    private var base: Double? = null
    private var slope = 0.0

    /** [t0] when we sent, [hostTime] the host's clock when it answered, [t2] when we got it. */
    @Synchronized
    fun add(t0: Long, hostTime: Long, t2: Long) {
        val rtt = (t2 - t0).coerceAtLeast(0)
        samples.addLast(Sample(t2, rtt, hostTime - (t0 + rtt / 2)))
        while (samples.size > keep || (samples.isNotEmpty() && t2 - samples.first().at > maxAgeMs)) samples.removeFirst()
        // The fastest round trip can only be trusted while it's in the window, so a slower network
        // later is accepted rather than every ping being refused.
        bestRtt = samples.minOf { it.rtt }
        fit()
    }

    private fun trusted(): List<Sample> {
        val limit = bestRtt + maxOf(4L, bestRtt / 2)
        return samples.filter { it.rtt <= limit }
    }

    private fun fit() {
        val good = trusted()
        if (good.isEmpty()) { base = null; return }
        val span = good.last().at - good.first().at
        if (good.size < 6 || span < MIN_FIT_SPAN_MS) {
            // Too few or too close together for a rate: the single best one.
            val best = good.minBy { it.rtt }
            origin = best.at; base = best.offset.toDouble(); slope = 0.0
            return
        }
        origin = good.last().at
        val xs = good.map { (it.at - origin).toDouble() }
        val ys = good.map { it.offset.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val raw = if (sxx > 0) xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx else 0.0
        // Real clocks differ by well under 200 ppm; a steeper line is noise.
        slope = raw.coerceIn(-MAX_SKEW, MAX_SKEW)
        base = my - slope * mx
    }

    val offset: Long? @Synchronized get() = base?.let { (it).toLong() }
    val rtt: Long? @Synchronized get() = if (samples.isEmpty()) null else bestRtt
    val count: Int @Synchronized get() = samples.size
    /** How fast the host's clock runs against ours, in parts per million. */
    val skewPpm: Double @Synchronized get() = slope * 1e6

    @Synchronized
    fun hostNow(localNow: Long): Long {
        val b = base ?: return localNow
        return localNow + (b + slope * (localNow - origin)).toLong()
    }

    companion object {
        private const val MIN_FIT_SPAN_MS = 60_000L
        private const val MAX_SKEW = 200e-6
    }
}
