package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class EarSyncTest {
    /** A recording: quiet noise, the host's ticks at [hostAt] (quieter, further away), ours [late] ms after the plan. */
    private fun recording(late: Int, hostAt: Int = 150, quiet: Double = 0.3, ticks: Int = EarSync.TICKS): ShortArray {
        val ms = 4_500
        val r = Random(7)
        val pcm = DoubleArray(EarSync.RATE * ms / 1000) { r.nextDouble(-600.0, 600.0) }
        fun put(at: Int, t: ShortArray, gain: Double) {
            val s = at * EarSync.RATE / 1000
            for (i in t.indices) if (s + i < pcm.size) pcm[s + i] += t[i] * gain
        }
        val hostTick = EarSync.tick(EarSync.HOST_HZ); val myTick = EarSync.tick(EarSync.GUEST_HZ)
        repeat(ticks) { n ->
            put(hostAt + n * 1000, hostTick, quiet)
            put(hostAt + n * 1000 + EarSync.GAP_MS.toInt() + late, myTick, 1.0)
        }
        return ShortArray(pcm.size) { pcm[it].coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    @Test fun findsHowMuchLaterThisPhoneSounds() {
        assertThat(EarSync.measure(recording(late = 0))!!).isIn(com.google.common.collect.Range.closed(-2L, 2L))
        assertThat(EarSync.measure(recording(late = 100))!!).isIn(com.google.common.collect.Range.closed(98L, 102L))
        assertThat(EarSync.measure(recording(late = -60))!!).isIn(com.google.common.collect.Range.closed(-62L, -58L))
    }

    @Test fun aFaintHostStillCounts() {
        assertThat(EarSync.measure(recording(late = 40, quiet = 0.08))!!).isIn(com.google.common.collect.Range.closed(38L, 42L))
    }

    @Test fun givesUpWhenTheHostCantBeHeard() {
        assertThat(EarSync.measure(recording(late = 40, quiet = 0.0))).isNull()
    }
}
