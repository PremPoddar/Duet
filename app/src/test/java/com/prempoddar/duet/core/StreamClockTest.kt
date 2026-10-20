package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamClockTest {
    @Test fun staleReportsDontPullTheEstimateBack() {
        val c = StreamClock()
        // True position = at - 1000. The player updates its time every 250 ms; reports arrive
        // between 0 and 240 ms after an update, so they read up to 240 ms behind.
        for (at in 1_000L..4_000L step 50) {
            val updatedAt = at - (at % 250)
            c.add(at, updatedAt - 1_000)
        }
        assertThat(c.now(4_020)).isWithin(5).of(3_020)
    }

    @Test fun aJumpBackStartsAgain() {
        val c = StreamClock()
        for (at in 0L..2_000L step 200) c.add(at, at + 10_000)
        c.add(2_200, 5_000)
        assertThat(c.now(2_300)).isEqualTo(5_100)
    }

    @Test fun nothingKnownAfterReset() {
        val c = StreamClock()
        c.add(0, 100)
        c.reset()
        assertThat(c.now(50)).isNull()
    }
}
