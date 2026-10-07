package com.prempoddar.duet.core

/**
 * Estimates how far the host's clock is from ours, NTP style: each ping gives an offset, and the
 * one with the shortest round trip is the most trustworthy (least time for asymmetry to hide in).
 */
class ClockSync(private val window: Int = 16) {
    private class Sample(val rtt: Long, val offset: Long)
    private val samples = ArrayDeque<Sample>()

    /** [t0] when we sent, [hostTime] the host's clock when it answered, [t2] when we got it. */
    @Synchronized
    fun add(t0: Long, hostTime: Long, t2: Long) {
        val rtt = (t2 - t0).coerceAtLeast(0)
        samples.addLast(Sample(rtt, hostTime - (t0 + rtt / 2)))
        while (samples.size > window) samples.removeFirst()
    }

    val offset: Long? @Synchronized get() = samples.minByOrNull { it.rtt }?.offset
    val rtt: Long? @Synchronized get() = samples.minByOrNull { it.rtt }?.rtt
    val count: Int @Synchronized get() = samples.size

    fun hostNow(localNow: Long): Long = localNow + (offset ?: 0)
}
