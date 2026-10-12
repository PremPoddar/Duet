package com.prempoddar.duet.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures, by ear, how much later this phone's sound comes out than the host's.
 *
 * Players report where they are, but not how long the speaker, its processing or a Bluetooth
 * link adds after that, and phones differ by tens of milliseconds. So the host plays a few short
 * low ticks and this phone plays high ticks [GAP_MS] after each, both aimed at the same shared
 * clock, while this phone's microphone listens. Both ticks reach the same microphone, so its own
 * delay cancels out: whatever the gap between them is beyond [GAP_MS] is the difference in output
 * delay, which becomes this output's nudge.
 */
object EarSync {
    const val RATE = 48_000
    const val HOST_HZ = 2_000
    const val GUEST_HZ = 3_200
    const val GAP_MS = 300L
    const val PERIOD_MS = 1_000L
    const val TICKS = 4
    const val TICK_MS = 12

    /** The host's tick times (shared clock) for a measurement starting at [start]; ours are [GAP_MS] later. */
    fun hostTicks(start: Long) = List(TICKS) { start + it * PERIOD_MS }

    /** One tick at [hz]: a short tone with soft edges, so it doesn't click and is easy to pick out. */
    fun tick(hz: Int): ShortArray {
        val n = RATE * TICK_MS / 1000
        return ShortArray(n) { i ->
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(2 * PI * hz * i / RATE) * window * 26_000).toInt().toShort()
        }
    }

    /**
     * Where each tick at [hz] starts in a recording, in ms from its start: points where that
     * pitch's loudness rises well above its usual level, at least 400 ms apart.
     */
    fun onsets(pcm: ShortArray, hz: Int, rate: Int = RATE): List<Double> {
        val win = rate / 500 // 2 ms
        val hop = rate / 4000 // 0.25 ms
        val k = 2 * cos(2 * PI * hz / rate)
        val levels = ArrayList<Double>()
        var start = 0
        while (start + win <= pcm.size) {
            var s1 = 0.0; var s2 = 0.0
            for (i in start until start + win) {
                val s0 = pcm[i] + k * s1 - s2
                s2 = s1; s1 = s0
            }
            levels += sqrt(abs(s1 * s1 + s2 * s2 - k * s1 * s2))
            start += hop
        }
        if (levels.isEmpty()) return emptyList()
        val floor = levels.sorted()[levels.size / 2].coerceAtLeast(1.0)
        val peak = levels.max()
        // Halfway (on a log scale) between the quiet and the loudest tick.
        val threshold = maxOf(floor * 6, sqrt(floor * peak))
        val out = ArrayList<Double>()
        var i = 0
        val gap = (400.0 / 0.25).toInt()
        while (i < levels.size) {
            if (levels[i] > threshold) {
                out += i * hop * 1000.0 / rate
                i += gap
            } else i++
        }
        return out
    }

    /**
     * How much later this phone's sound comes out than the host's, in ms, from a recording; null
     * if the ticks couldn't be heard clearly enough to trust the answer.
     */
    fun measure(pcm: ShortArray, rate: Int = RATE): Long? {
        val host = onsets(pcm, HOST_HZ, rate)
        val mine = onsets(pcm, GUEST_HZ, rate)
        val gaps = host.mapNotNull { h -> mine.firstOrNull { it > h && it < h + PERIOD_MS - 100 }?.let { it - h - GAP_MS } }
            .filter { abs(it) < 250 }
        if (gaps.size < 2) return null
        val sorted = gaps.sorted()
        // Readings that disagree a lot mean echoes or noise got in the way.
        if (sorted.last() - sorted.first() > 25) return null
        return sorted[sorted.size / 2].toLong()
    }
}
