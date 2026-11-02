package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayoutTest {
    @Test fun tracesOutputBackThroughChunks() {
        val p = Playout()
        p.wrote(0, 1000.0, 1.0)
        p.wrote(480, 1480.0, 1.01)
        p.wrote(960, 1964.8, 0.99)
        assertThat(p.sourceAt(100)).isWithin(1e-9).of(1100.0)
        assertThat(p.sourceAt(480)).isWithin(1e-9).of(1480.0)
        assertThat(p.sourceAt(580)).isWithin(1e-9).of(1581.0)
        assertThat(p.sourceAt(1060)).isWithin(1e-9).of(2063.8)
    }

    @Test fun unknownBeforeTheFirstChunk() {
        val p = Playout()
        assertThat(p.sourceAt(5)).isNull()
        p.wrote(1000, 0.0, 1.0)
        assertThat(p.sourceAt(999)).isNull()
        p.reset()
        assertThat(p.sourceAt(1000)).isNull()
    }

    @Test fun forgetsOldChunks() {
        val p = Playout(keep = 3)
        repeat(5) { p.wrote(it * 100L, it * 100.0, 1.0) }
        assertThat(p.sourceAt(50)).isNull()
        assertThat(p.sourceAt(250)).isWithin(1e-9).of(250.0)
    }

    @Test fun inTimeIsLeftAlone() {
        assertThat(Slew.step(20.0, 48_000)).isEqualTo(1.0) // under a millisecond
    }

    @Test fun aheadReadsSlowerBehindReadsFaster() {
        assertThat(Slew.step(240.0, 48_000)).isLessThan(1.0)   // 5 ms ahead
        assertThat(Slew.step(-240.0, 48_000)).isGreaterThan(1.0)
    }

    @Test fun smallGapsAreClosedInaudibly() {
        val s = Slew.step(-430.0, 48_000) // 9 ms behind
        assertThat(s).isAtMost(1.003)
    }

    @Test fun biggerGapsCloseFasterButBounded() {
        assertThat(Slew.step(-2_400.0, 48_000)).isWithin(1e-9).of(1.015) // 50 ms behind
    }

    @Test fun onlyBigGapsJump() {
        assertThat(Slew.shouldJump(3_000.0, 48_000)).isFalse()  // 62 ms
        assertThat(Slew.shouldJump(-4_800.0, 48_000)).isTrue()  // 100 ms
    }

    /** A simulated speaker that starts 30 ms behind is brought in line within a few seconds. */
    @Test fun convergesOnTheTarget() {
        val rate = 48_000; val chunk = 480
        var source = 0.0; var target = 1_440.0 // 30 ms
        repeat(1_000) {
            val step = Slew.step(source - target, rate)
            source += chunk * step; target += chunk
        }
        assertThat(kotlin.math.abs(source - target) * 1000 / rate).isLessThan(1.0)
    }
}
