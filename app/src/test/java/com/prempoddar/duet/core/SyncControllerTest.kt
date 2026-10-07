package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncControllerTest {
    private var now = 0L
    private val c = SyncController { now }

    /** Feeds one sample per tick, 200 ms apart, and returns the actions taken. */
    private fun run(ticks: Int, drift: (Int) -> Long): List<SyncController.Action> = (0 until ticks).map { i ->
        now += 200
        c.step(target = 60_000 + now, actual = 60_000 + now - drift(i))
    }

    @Test fun startingLatencyDoesNotCauseASeekLoop() {
        c.started()
        // A player that starts 250 ms late and then plays at the right pace.
        val actions = run(20) { 250 }
        assertThat(actions.count { it is SyncController.Action.Seek }).isAtMost(1)
        // After one correction it settles: no more seeks.
        assertThat(actions.takeLast(10).none { it is SyncController.Action.Seek }).isTrue()
    }

    @Test fun nothingHappensDuringTheSettleTime() {
        c.started()
        val first = run(5) { 2_000 }
        assertThat(first.all { it == SyncController.Action.None }).isTrue()
    }

    @Test fun smallDriftIsEasedOutWithoutJumping() {
        c.started()
        run(10) { 0 }
        val actions = run(15) { 60 }
        assertThat(actions.none { it is SyncController.Action.Seek }).isTrue()
        val speed = actions.filterIsInstance<SyncController.Action.Speed>().last().speed
        assertThat(speed).isGreaterThan(1f)
        assertThat(speed).isAtMost(1.015f)
    }

    @Test fun speedChangesAreRare() {
        c.started()
        run(10) { 0 }
        // Noisy readings around a 40 ms drift.
        val actions = run(50) { i -> if (i % 2 == 0) 25 else 55 }
        assertThat(actions.count { it is SyncController.Action.Speed }).isAtMost(6)
    }

    @Test fun oneBadReadingIsIgnored() {
        c.started()
        run(10) { 0 }
        val actions = run(6) { i -> if (i == 2) 900 else 0 }
        assertThat(actions.none { it is SyncController.Action.Seek }).isTrue()
    }

    @Test fun aRealJumpIsFollowedQuickly() {
        c.started()
        run(10) { 0 }
        val actions = run(5) { 3_000 }
        val seek = actions.filterIsInstance<SyncController.Action.Seek>().single()
        // Seeks a little ahead to make up for the time the jump itself takes.
        assertThat(seek.aheadMs).isAtLeast(0)
    }

    @Test fun learnsHowLongStartingTakes() {
        c.started()
        run(12) { 300 }
        assertThat(c.startLeadMs).isGreaterThan(150)
    }

    @Test fun returnsToNormalSpeedWhenInSync() {
        c.started()
        run(10) { 0 }
        run(10) { 70 }
        val back = run(10) { 0 }
        assertThat(back.filterIsInstance<SyncController.Action.Speed>().last().speed).isEqualTo(1f)
    }
}
