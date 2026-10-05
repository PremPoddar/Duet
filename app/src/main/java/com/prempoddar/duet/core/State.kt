package com.prempoddar.duet.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackMeta(
    /** 16 hex characters, also used in file frames. */
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long,
    val mime: String,
    val size: Long,
    /** Who added it; their phone (or the host) sends the file. */
    val owner: String,
    /** Small JPEG of the cover, base64, so everyone sees it before the file arrives. */
    val art: String? = null,
    /** SHA-256 of the file, hex. */
    val digest: String,
)

/**
 * Everyone's shared truth, kept by the host. [positionMs] was the position at host time [anchor];
 * while playing, the position now is that plus however long it's been.
 */
@Serializable
data class PlaybackState(
    val queue: List<TrackMeta> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val anchor: Long = 0,
    val playing: Boolean = false,
    val version: Long = 0,
) {
    val current: TrackMeta? get() = queue.getOrNull(index)

    fun positionAt(hostNow: Long): Long {
        val pos = if (playing) positionMs + (hostNow - anchor) else positionMs
        val max = current?.durationMs ?: return 0
        return pos.coerceIn(0, max)
    }
}

@Serializable
sealed class Command {
    @Serializable @SerialName("play") data object Play : Command()
    @Serializable @SerialName("pause") data object Pause : Command()
    @Serializable @SerialName("seek") data class Seek(val positionMs: Long) : Command()
    @Serializable @SerialName("next") data object Next : Command()
    @Serializable @SerialName("previous") data object Previous : Command()
    @Serializable @SerialName("jump") data class Jump(val index: Int) : Command()
    @Serializable @SerialName("add") data class Add(val track: TrackMeta) : Command()
    @Serializable @SerialName("remove") data class Remove(val trackId: String) : Command()
    @Serializable @SerialName("move") data class Move(val from: Int, val to: Int) : Command()
    /** A player reached the end of [trackId]; ignored if the queue already moved on. */
    @Serializable @SerialName("ended") data class Ended(val trackId: String) : Command()
}

/** How commands change the shared state. Pure; only the host runs it. */
object Reducer {
    const val MAX_QUEUE = 200
    private const val RESTART_IF_PAST_MS = 3_000

    fun apply(s: PlaybackState, cmd: Command, now: Long): PlaybackState {
        val pos = s.positionAt(now)
        val next = when (cmd) {
            Command.Play -> when {
                s.queue.isEmpty() -> return s
                s.index == -1 -> s.copy(index = 0, positionMs = 0, anchor = now, playing = true)
                else -> s.copy(positionMs = pos, anchor = now, playing = true)
            }
            Command.Pause -> if (!s.playing) return s else s.copy(positionMs = pos, anchor = now, playing = false)
            is Command.Seek -> {
                val track = s.current ?: return s
                s.copy(positionMs = cmd.positionMs.coerceIn(0, track.durationMs), anchor = now)
            }
            Command.Next -> step(s, s.index + 1, now)
            Command.Previous -> if (pos > RESTART_IF_PAST_MS || s.index <= 0) s.copy(positionMs = 0, anchor = now) else step(s, s.index - 1, now)
            is Command.Jump -> if (cmd.index !in s.queue.indices) return s else s.copy(index = cmd.index, positionMs = 0, anchor = now, playing = true)
            is Command.Add -> {
                if (s.queue.size >= MAX_QUEUE || s.queue.any { it.id == cmd.track.id }) return s
                val queue = s.queue + cmd.track
                // The first song added starts the music.
                if (s.index == -1 || s.current == null) s.copy(queue = queue, index = queue.lastIndex, positionMs = 0, anchor = now, playing = true)
                else s.copy(queue = queue)
            }
            is Command.Remove -> {
                val at = s.queue.indexOfFirst { it.id == cmd.trackId }
                if (at < 0) return s
                val queue = s.queue.filterIndexed { i, _ -> i != at }
                when {
                    at < s.index -> s.copy(queue = queue, index = s.index - 1)
                    at > s.index -> s.copy(queue = queue)
                    queue.isEmpty() -> PlaybackState(version = s.version)
                    at <= queue.lastIndex -> s.copy(queue = queue, positionMs = 0, anchor = now)
                    else -> s.copy(queue = queue, index = queue.lastIndex, positionMs = 0, anchor = now, playing = false)
                }
            }
            is Command.Move -> {
                if (cmd.from !in s.queue.indices || cmd.to !in s.queue.indices || cmd.from == cmd.to) return s
                val currentId = s.current?.id
                val queue = s.queue.toMutableList().apply { add(cmd.to, removeAt(cmd.from)) }
                s.copy(queue = queue, index = queue.indexOfFirst { it.id == currentId })
            }
            is Command.Ended -> if (s.current?.id != cmd.trackId) return s else step(s, s.index + 1, now)
        }
        return next.copy(version = s.version + 1)
    }

    private fun step(s: PlaybackState, to: Int, now: Long): PlaybackState = when {
        s.queue.isEmpty() -> s
        to > s.queue.lastIndex -> s.copy(positionMs = 0, anchor = now, playing = false) // end of the queue: stop
        else -> s.copy(index = to.coerceAtLeast(0), positionMs = 0, anchor = now)
    }
}
