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
        val c = ClockSync(window = 3)
        c.add(0, 5_010, 20)
        repeat(3) { i -> c.add(1_000L * (i + 1), 1_000L * (i + 1) + 6_100, 1_000L * (i + 1) + 200) }
        assertThat(c.offset).isEqualTo(6_000)
    }

    @Test fun noSamplesNoOffset() {
        assertThat(ClockSync().offset).isNull()
    }

}
