package com.prempoddar.duet.play

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.prempoddar.duet.core.Command
import com.prempoddar.duet.core.Drift
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.data.Output
import com.prempoddar.duet.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the shared song on this phone and keeps it on the shared position: every 200 ms it
 * compares where the song should be (host time + nudge for this output) with where it is.
 */
class PlayerController(private val context: Context, private val sessions: SessionManager) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(false)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var loaded: String? = null
    private var endedSent: String? = null
    private var lastSpeed = 1f
    @Volatile var settings = DuetSettings()

    private val _drift = MutableStateFlow(0L)
    /** How far off the shared position this phone is, for the sync indicator. */
    val drift: StateFlow<Long> = _drift.asStateFlow()
    private val _output = MutableStateFlow(Output.SPEAKER)
    val output: StateFlow<Output> = _output.asStateFlow()

    private val loop = object : Runnable {
        override fun run() {
            runCatching { step() }
            handler.postDelayed(this, STEP_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun step() {
        _output.value = currentOutput()
        val session = sessions.session
        val st = sessions.state.value
        val track = st.current
        if (session == null || track == null) {
            if (loaded != null) {
                player.stop(); player.clearMediaItems(); loaded = null
            }
            return
        }
        if (loaded != track.id) {
            val file = sessions.store.file(track.id)
            if (file == null) {
                // Still arriving; the others carry on and we join in when it's here.
                if (player.isPlaying) player.pause()
                return
            }
            val art = track.art?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            player.setMediaItem(
                MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setMediaId(track.id)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                            .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }.build(),
                    ).build(),
            )
            player.prepare()
            loaded = track.id
        }
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return

        val nudge = settings.nudge(_output.value)
        val target = st.positionAt(session.hostNow()) + nudge
        if (!st.playing) {
            if (player.isPlaying) player.pause()
            if (kotlin.math.abs(player.currentPosition - target) > Drift.SEEK_OVER_MS) player.seekTo(target.coerceAtLeast(0))
            setSpeed(1f)
            _drift.value = 0
            return
        }
        if (target >= track.durationMs - END_MARGIN_MS || player.playbackState == Player.STATE_ENDED) {
            if (endedSent != track.id) {
                endedSent = track.id
                session.command(Command.Ended(track.id))
            }
            return
        }
        if (!player.isPlaying) {
            player.seekTo(target.coerceAtLeast(0))
            player.play()
            return
        }
        _drift.value = target - player.currentPosition
        when (val a = Drift.decide(target, player.currentPosition)) {
            is Drift.Action.Rate -> setSpeed(a.speed)
            is Drift.Action.Seek -> {
                setSpeed(1f)
                player.seekTo(a.positionMs)
            }
        }
    }

    private fun setSpeed(speed: Float) {
        if (speed == lastSpeed) return
        lastSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
    }

    private fun currentOutput(): Output {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            devices.any { it.type in BT } -> Output.BLUETOOTH
            devices.any { it.type in WIRED } -> Output.WIRED
            else -> Output.SPEAKER
        }
    }

    companion object {
        private const val STEP_MS = 200L
        private const val END_MARGIN_MS = 150L
        private val BT = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 27)
        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
