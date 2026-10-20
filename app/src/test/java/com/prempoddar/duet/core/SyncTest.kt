package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncTest {
    @Test fun offsetFromSymmetricRoundTrip() {
        val c = ClockSync()
        // Host clock is 5000 ms ahead; 40 ms each way.
        c.add(t0 = 1_000, hostTime = 6_040, t2 = 1_080)
        assertThat(c.offset).isEqualTo(5_000)
        assertThat(c.hostNow(2_000)).isEqualTo(7_000)
    }

    @Test fun lowestRttSampleWins() {
        val c = ClockSync()
        c.add(0, 5_150, 300)   // slow, asymmetric: would say 5000
        c.add(1_000, 6_010, 1_020) // fast: says 5000 exactly
        c.add(2_000, 7_200, 2_200) // slow: says 5100
        assertThat(c.offset).isEqualTo(5_000)
        assertThat(c.rtt).isEqualTo(20)
    }

    @Test fun oldSamplesAgeOut() {
        val c = ClockSync(keep = 3)
        c.add(0, 5_010, 20)
        repeat(3) { i -> c.add(1_000L * (i + 1), 1_000L * (i + 1) + 6_100, 1_000L * (i + 1) + 200) }
        assertThat(c.offset).isEqualTo(6_000)
    }

    @Test fun noSamplesNoOffset() {
        assertThat(ClockSync().offset).isNull()
    }

    @Test fun pingsStuckBehindSongDataAreIgnored() {
        val c = ClockSync()
        // A good start, then two minutes of replies held up 1-3 s on the way back.
        c.add(0, 5_010, 20)
        for (i in 1..60) { val t0 = i * 2_000L; val down = 1_000L + (i % 3) * 1_000; c.add(t0, t0 + 5_000 + 10, t0 + 10 + down) }
        assertThat(c.hostNow(200_000) - 200_000).isWithin(5).of(5_000)
    }

    @Test fun followsAClockRunningFast() {
        val c = ClockSync()
        // The host gains 100 ppm: 6 ms a minute. Over 5 minutes the offset moves 30 ms.
        for (i in 0..150) {
            val t0 = i * 2_000L
            val offset = 5_000 + t0 / 10_000
            c.add(t0, t0 + 10 + offset, t0 + 20)
        }
        assertThat(c.skewPpm).isWithin(10.0).of(100.0)
        // Ten minutes on, still right to a few ms (a fixed offset would be 30 ms out).
        val later = 900_000L
        assertThat(c.hostNow(later) - later).isWithin(5).of(5_000 + later / 10_000)
    }
}
