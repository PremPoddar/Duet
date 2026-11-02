package com.prempoddar.duet.core

import kotlin.math.abs

/**
 * Which sample of the song each sample sent to the speaker came from. The engine writes the song in
 * small chunks, each read at its own speed (a hair faster or slower to stay on the shared clock), so
 * a sample the hardware says it's playing is traced back through the chunk it was written in.
 */
class Playout(private val keep: Int = 400) {
    private class Chunk(val written: Long, val source: Double, val step: Double)
    private val chunks = ArrayDeque<Chunk>()

    /** The chunk starting at output sample [written] reads the song from [source], [step] samples per sample. */
    fun wrote(written: Long, source: Double, step: Double) {
        chunks.addLast(Chunk(written, source, step))
        while (chunks.size > keep) chunks.removeFirst()
    }

    /** The song's sample that output sample [written] came from, or null if it's too old or not written yet. */
    fun sourceAt(written: Long): Double? {
        if (chunks.isEmpty() || written < chunks.first().written) return null
        // Newest first: the hardware is asking about something written in the last few chunks.
        for (i in chunks.indices.reversed()) {
            val c = chunks[i]
            if (c.written <= written) return c.source + (written - c.written) * c.step
        }
        return null
    }

    fun reset() = chunks.clear()
}

/**
 * How fast to read the song to close a gap without it being heard. [errorFrames] is how far the
 * speaker is ahead of where the song should be (negative: behind). Small gaps are closed slowly, at
 * most 0.3% faster or slower (far too little to hear); larger ones at up to 1.5%. Anything bigger
 * than [jumpOverMs] isn't eased at all: the engine starts again from the right place.
 */
object Slew {
    const val JUMP_OVER_MS = 80.0
    private const val DEADBAND_MS = 1.0
    private const val GENTLE = 0.003
    private const val FIRM = 0.015
    /** The gap is closed over about this long. */
    private const val CLOSE_IN_S = 2.0

    /** Samples of song to read for each sample played. */
    fun step(errorFrames: Double, rate: Int): Double {
        val ms = errorFrames * 1000.0 / rate
        if (abs(ms) < DEADBAND_MS) return 1.0
        val cap = if (abs(ms) < 10) GENTLE else FIRM
        // Ahead: read slower; behind: read faster.
        return 1.0 - (errorFrames / (rate * CLOSE_IN_S)).coerceIn(-cap, cap)
    }

    fun shouldJump(errorFrames: Double, rate: Int) = abs(errorFrames * 1000.0 / rate) > JUMP_OVER_MS
}
