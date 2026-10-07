package com.prempoddar.duet.core

import kotlin.math.abs

/**
 * Keeps one phone's player on the shared position without making the music stutter.
 *
 * Readings of a player's position are noisy, and starting or jumping takes a few hundred
 * milliseconds before sound comes out. So: readings are smoothed (median of the last few), nothing
 * is corrected while the player settles after a start or jump, small drift is eased out by playing
 * up to 1.5% faster or slower (changed at most once a second), and only a large, lasting drift
 * causes a jump. How long starting takes is learned, so later jumps land in the right place.
 */
class SyncController(private val clock: () -> Long) {
    sealed interface Action {
        data object None : Action
        data class Speed(val speed: Float) : Action
        /** Jump to the shared position plus [aheadMs] to cover the jump's own delay. */
        data class Seek(val aheadMs: Long) : Action
    }

    private val samples = ArrayDeque<Long>()
    private var settleUntil = 0L
    private var lastSpeedAt = 0L
    private var speed = 1f
    private var correcting = false
    private var measuringStart = false

    /** Learned delay between asking to play from a point and sound coming from that point. */
    var startLeadMs = 120L
        private set

    /** Smoothed drift for the sync indicator; positive means behind. */
    var drift = 0L
        private set

    /** Call after starting or jumping the player. */
    fun started() {
        samples.clear()
        settleUntil = clock() + SETTLE_MS
        measuringStart = true
        speed = 1f
        correcting = false
    }

    /** Call every tick while playing; [target] and [actual] are positions in ms. */
    fun step(target: Long, actual: Long): Action {
        val now = clock()
        samples.addLast(target - actual)
        while (samples.size > WINDOW) samples.removeFirst()
        if (now < settleUntil || samples.size < MIN_SAMPLES) return Action.None
        // Median to decide on jumps (one wild reading can't trigger one); trimmed mean for easing,
        // which stays steady when readings wobble.
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        drift = sorted.drop(1).dropLast(1).average().toLong()

        if (measuringStart) {
            // Whatever is left over right after a start is how far off our start lead was.
            measuringStart = false
            startLeadMs = (startLeadMs + median).coerceIn(0, MAX_LEAD_MS)
        }

        if (abs(median) > SEEK_OVER_MS) {
            started()
            return Action.Seek(startLeadMs)
        }
        val size = abs(drift)
        // Hysteresis: start easing past 30 ms, stop once within 10 ms.
        if (!correcting && size > START_EASING_MS) correcting = true
        if (correcting && size < STOP_EASING_MS) correcting = false
        val wanted = if (!correcting) 1f else 1f + (drift / 4000f).coerceIn(-MAX_ADJUST, MAX_ADJUST).let { (it * 200).toInt() / 200f }
        if (wanted == speed) return Action.None
        // Already easing the same way at about the right pace: leave it rather than wobble.
        if (wanted != 1f && speed != 1f && (wanted > 1f) == (speed > 1f) && abs(wanted - speed) < 0.0075f) return Action.None
        if (wanted != 1f && now - lastSpeedAt < MIN_SPEED_GAP_MS) return Action.None
        speed = wanted
        lastSpeedAt = now
        return Action.Speed(wanted)
    }

    companion object {
        const val SETTLE_MS = 1_200L
        const val SEEK_OVER_MS = 350L
        const val START_EASING_MS = 30L
        const val STOP_EASING_MS = 10L
        const val MAX_ADJUST = 0.015f
        const val MIN_SPEED_GAP_MS = 1_000L
        const val MAX_LEAD_MS = 800L
        private const val WINDOW = 7
        private const val MIN_SAMPLES = 4
    }
}
