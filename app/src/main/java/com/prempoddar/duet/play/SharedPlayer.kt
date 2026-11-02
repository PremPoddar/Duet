package com.prempoddar.duet.play

import android.os.Looper
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.prempoddar.duet.core.TrackMeta

/**
 * What the lock screen and notification show: the shared song, whether it's playing and where it is.
 * The sound itself comes from [Engine] (or YouTube); this only reports, and [DuetService] turns its
 * buttons into commands for the whole session.
 */
@OptIn(UnstableApi::class)
class SharedPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var track: TrackMeta? = null
    private var playing = false
    private var position: () -> Long = { 0 }

    /** Updates what's shown; call on the main thread whenever the shared song or play state changes. */
    fun show(track: TrackMeta?, playing: Boolean, position: () -> Long) {
        val changed = track?.id != this.track?.id || playing != this.playing
        this.track = track; this.playing = playing; this.position = position
        if (changed) invalidateState()
    }

    /** After a jump, so the lock screen's progress bar follows. */
    fun moved() = invalidateState()

    override fun getState(): State {
        val t = track
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE,
        ).build()
        val b = State.Builder().setAvailableCommands(commands)
        if (t == null) return b.setPlaybackState(STATE_IDLE).build()
        val art = t.art?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }.build()
        val item = MediaItemData.Builder(t.id)
            .setMediaItem(MediaItem.Builder().setMediaId(t.id).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .apply { if (t.durationMs > 0) setDurationUs(t.durationMs * 1000) }
            .build()
        val pos = position
        return b.setPlaylist(listOf(item))
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs { pos() }
            .build()
    }

    // The buttons are taken over by DuetService before they get here; these only keep the player whole.
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
}
