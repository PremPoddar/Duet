package com.prempoddar.duet.play

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.MainActivity
import com.prempoddar.duet.core.Command

/**
 * Lock-screen and notification controls, and playback in the background. Buttons there change the
 * shared session (so everyone pauses), not just this phone's player.
 */
class DuetService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val shared = object : ForwardingPlayer(AppGraph.player.player) {
            private fun send(c: Command) = AppGraph.sessions.session?.command(c)
            override fun play() { send(Command.Play) }
            override fun pause() { send(Command.Pause) }
            override fun setPlayWhenReady(playWhenReady: Boolean) { send(if (playWhenReady) Command.Play else Command.Pause) }
            override fun seekToNext() { send(Command.Next) }
            override fun seekToNextMediaItem() { send(Command.Next) }
            override fun seekToPrevious() { send(Command.Previous) }
            override fun seekToPreviousMediaItem() { send(Command.Previous) }
            override fun seekTo(positionMs: Long) { send(Command.Seek(positionMs)) }
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_PLAY_PAUSE).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this, shared).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Duet away while not in a session stops the service.
        if (AppGraph.sessions.session == null) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
