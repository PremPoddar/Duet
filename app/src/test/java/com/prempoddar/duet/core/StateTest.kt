package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StateTest {
    private fun track(id: String, dur: Long = 200_000) = TrackMeta(id.padEnd(16, '0'), "Song $id", durationMs = dur, mime = "audio/mpeg", size = 100, owner = "h", digest = "")
    private val a = track("a"); private val b = track("b"); private val c = track("c")

    @Test fun firstAddStartsPlayingFromZero() {
        val s = Reducer.apply(PlaybackState(), Command.Add(a), now = 1_000)
        assertThat(s.index).isEqualTo(0)
        assertThat(s.playing).isTrue()
        assertThat(s.positionAt(6_000)).isEqualTo(5_000)
        assertThat(s.version).isEqualTo(1)
    }

    @Test fun pauseFreezesPositionAndPlayResumesFromIt() {
        var s = Reducer.apply(PlaybackState(), Command.Add(a), 0)
        s = Reducer.apply(s, Command.Pause, 10_000)
        assertThat(s.positionAt(50_000)).isEqualTo(10_000)
        s = Reducer.apply(s, Command.Play, 60_000)
        assertThat(s.positionAt(61_000)).isEqualTo(11_000)
    }

    @Test fun positionIsClampedToTheTrack() {
        val s = Reducer.apply(PlaybackState(), Command.Add(track("x", dur = 1_000)), 0)
        assertThat(s.positionAt(99_999)).isEqualTo(1_000)
        assertThat(Reducer.apply(s, Command.Seek(-5), 0).positionMs).isEqualTo(0)
    }

    @Test fun nextPreviousAndEndOfQueue() {
        var s = listOf(a, b).fold(PlaybackState()) { st, t -> Reducer.apply(st, Command.Add(t), 0) }
        s = Reducer.apply(s, Command.Next, 1_000)
        assertThat(s.current).isEqualTo(b)
        s = Reducer.apply(s, Command.Previous, 2_000) // within 3 s: back to a
        assertThat(s.current).isEqualTo(a)
        s = Reducer.apply(s, Command.Next, 3_000)
        s = Reducer.apply(s, Command.Previous, 10_000) // 7 s in: restart b
        assertThat(s.current).isEqualTo(b)
        assertThat(s.positionMs).isEqualTo(0)
        s = Reducer.apply(s, Command.Next, 11_000) // past the end
        assertThat(s.playing).isFalse()
        assertThat(s.current).isEqualTo(b)
    }

    @Test fun endedOnlyCountsForTheCurrentTrack() {
        var s = listOf(a, b).fold(PlaybackState()) { st, t -> Reducer.apply(st, Command.Add(t), 0) }
        val same = Reducer.apply(s, Command.Ended(b.id), 1)
        assertThat(same).isEqualTo(s)
        s = Reducer.apply(s, Command.Ended(a.id), 1)
        assertThat(s.current).isEqualTo(b)
        // A second player reporting the same end must not skip again.
        assertThat(Reducer.apply(s, Command.Ended(a.id), 2)).isEqualTo(s)
    }

    @Test fun removeAndMoveKeepTheCurrentSong() {
        var s = listOf(a, b, c).fold(PlaybackState()) { st, t -> Reducer.apply(st, Command.Add(t), 0) }
        s = Reducer.apply(s, Command.Jump(1), 0)
        s = Reducer.apply(s, Command.Move(1, 2), 0)
        assertThat(s.current).isEqualTo(b)
        assertThat(s.queue.map { it.id }).containsExactly(a.id, c.id, b.id).inOrder()
        s = Reducer.apply(s, Command.Remove(a.id), 0)
        assertThat(s.current).isEqualTo(b)
        s = Reducer.apply(s, Command.Remove(b.id), 0)
        assertThat(s.current).isEqualTo(c)
        s = Reducer.apply(s, Command.Remove(c.id), 0)
        assertThat(s.current).isNull()
        assertThat(s.playing).isFalse()
    }

    @Test fun duplicatesAndBadIndexesIgnored() {
        val s = Reducer.apply(PlaybackState(), Command.Add(a), 0)
        assertThat(Reducer.apply(s, Command.Add(a), 0)).isEqualTo(s)
        assertThat(Reducer.apply(s, Command.Jump(5), 0)).isEqualTo(s)
        assertThat(Reducer.apply(s, Command.Move(0, 3), 0)).isEqualTo(s)
    }

    @Test fun messagesAndFramesRoundTrip() {
        val msgs = listOf(
            Msg.Hello("Asha", "p1", listOf("x")), Msg.Cmd(Command.Seek(5)), Msg.Cmd(Command.Add(a)),
            Msg.State(PlaybackState(listOf(a), 0, 5, 6, true, 7)), Msg.Ping(1), Msg.Pong(1, 2),
            Msg.Chat(ChatLine("i", "p1", "Asha", "hi", 3)), Msg.React("p1", "🔥"), Msg.Heart("p1"),
            Msg.Have("t"), Msg.Want("t"), Msg.Bye("host left"),
        )
        msgs.forEach { assertThat(Wire.decode(it.encode())).isEqualTo(it) }
        assertThat(Wire.decode("{nope")).isNull()
        val f = Wire.parseFrame(Wire.frame(a.id, 2, 9, byteArrayOf(7, 8)))!!
        assertThat(f.trackId).isEqualTo(a.id)
        assertThat(f.index).isEqualTo(2)
        assertThat(f.total).isEqualTo(9)
        assertThat(f.bytes).isEqualTo(byteArrayOf(7, 8))
        assertThat(Wire.parseFrame(ByteArray(5))).isNull()
    }
}
