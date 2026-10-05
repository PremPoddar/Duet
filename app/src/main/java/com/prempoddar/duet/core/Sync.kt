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

/** Keeps a player on the shared position: tiny drift is ignored, small drift is eased out, big drift is jumped. */
object Drift {
    sealed interface Action {
        data class Rate(val speed: Float) : Action
        data class Seek(val positionMs: Long) : Action
    }

    const val DEAD_ZONE_MS = 15
    const val SEEK_OVER_MS = 80
    private const val MAX_ADJUST = 0.02f

    /** Positive drift means we're behind and should speed up. */
    fun decide(target: Long, actual: Long): Action {
        val drift = target - actual
        val size = kotlin.math.abs(drift)
        return when {
            size < DEAD_ZONE_MS -> Action.Rate(1f)
            size <= SEEK_OVER_MS -> Action.Rate(1f + (drift / 1000f).coerceIn(-MAX_ADJUST, MAX_ADJUST))
            else -> Action.Seek(target)
        }
    }
}
