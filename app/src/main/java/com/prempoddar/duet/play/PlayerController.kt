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
    private val sync = com.prempoddar.duet.core.SyncController { android.os.SystemClock.elapsedRealtime() }
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
            YouTubePlayer.stop()
            return
        }
        if (track.external != null) {
            YouTubePlayer.stop()
            if (loaded != null) { player.stop(); player.clearMediaItems(); loaded = null }
            externalStep(session, st, track)
            return
        }
        if (track.isStream) {
            streamStep(session, st, track)
            return
        }
        YouTubePlayer.stop()
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
            if (kotlin.math.abs(player.currentPosition - target) > com.prempoddar.duet.core.SyncController.SEEK_OVER_MS) player.seekTo(target.coerceAtLeast(0))
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
            // Start a little ahead: sound comes out a moment after play() is called.
            setSpeed(1f)
            player.seekTo((target + sync.startLeadMs).coerceAtLeast(0))
            player.play()
            sync.started()
            return
        }
        when (val a = sync.step(target, player.currentPosition)) {
            com.prempoddar.duet.core.SyncController.Action.None -> Unit
            is com.prempoddar.duet.core.SyncController.Action.Speed -> setSpeed(a.speed)
            is com.prempoddar.duet.core.SyncController.Action.Seek -> {
                setSpeed(1f)
                player.seekTo((target + a.aheadMs).coerceAtLeast(0))
            }
        }
        _drift.value = sync.drift
    }

    private var lastStreamSeek = 0L
    /** How far ahead to aim a jump, learnt from how far behind the video is after one. */
    private var streamLead = STREAM_JUMP_LEAD_MS
    private var checkAfterSeek = false

    /**
     * A YouTube video: the embedded player can't change speed finely, so drift is corrected by
     * jumping, and only when it is large enough to hear and not more than every few seconds.
     */
    private fun streamStep(session: com.prempoddar.duet.core.Session, st: com.prempoddar.duet.core.PlaybackState, track: com.prempoddar.duet.core.TrackMeta) {
        if (loaded != null) { player.stop(); player.clearMediaItems(); loaded = null }
        val yt = YouTubePlayer
        val now = android.os.SystemClock.elapsedRealtime()
        val target = st.positionAt(session.hostNow()) + settings.nudge(_output.value)
        if (yt.videoId != track.youtube) {
            yt.load(track.youtube!!, target.coerceAtLeast(0), st.playing)
            lastStreamSeek = now
            return
        }
        if (yt.ended) {
            if (endedSent != track.id) { endedSent = track.id; session.command(Command.Ended(track.id)) }
            return
        }
        if (!st.playing) {
            if (yt.playing) yt.pause()
            if (kotlin.math.abs(yt.positionMs() - target) > 700) yt.seek(target.coerceAtLeast(0))
            _drift.value = 0
            return
        }
        if (!yt.playing) {
            // Buffering after a jump or a stall: give it time before asking again.
            if (now - lastStreamSeek > 4_000) { yt.seek((target + streamLead).coerceAtLeast(0)); yt.play(); lastStreamSeek = now; checkAfterSeek = true }
            return
        }
        val drift = target - yt.positionMs()
        _drift.value = drift
        // Once playing again after a jump, whatever is still off is how much the jump itself cost.
        if (checkAfterSeek && now - lastStreamSeek > 2_000) {
            checkAfterSeek = false
            streamLead = (streamLead + drift).coerceIn(0, MAX_STREAM_LEAD_MS)
        }
        if (kotlin.math.abs(drift) > STREAM_TOLERANCE_MS && now - lastStreamSeek > STREAM_SEEK_GAP_MS) {
            android.util.Log.i("DuetSync", "stream drift ${drift} ms, jumping with lead $streamLead")
            yt.seek((target + streamLead).coerceAtLeast(0))
            lastStreamSeek = now
            checkAfterSeek = true
        }
    }

    private var lastExternalCheck = 0L

    /**
     * A song playing in another app on each phone. The phone that shared it leads: its app's play,
     * pause and position become everyone's. The others' apps are brought into line with that.
     */
    private fun externalStep(session: com.prempoddar.duet.core.Session, st: com.prempoddar.duet.core.PlaybackState, track: com.prempoddar.duet.core.TrackMeta) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExternalCheck < 1_000) return
        lastExternalCheck = now
        if (!OtherApps.hasAccess(context)) { _drift.value = 0; return }
        val target = st.positionAt(session.hostNow())
        if (track.owner == session.myId) {
            val mine = OtherApps.playing(context).firstOrNull { it.app == track.external && it.title.equals(track.title, ignoreCase = true) } ?: return
            if (mine.playing != st.playing) session.command(if (mine.playing) Command.Play else Command.Pause)
            if (kotlin.math.abs(mine.positionMs - target) > 2_000) session.command(Command.Seek(mine.positionMs))
            _drift.value = 0
        } else {
            _drift.value = OtherApps.align(context, track, target, st.playing) ?: 0
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
        private const val STREAM_TOLERANCE_MS = 350L
        private const val STREAM_JUMP_LEAD_MS = 300L
        private const val MAX_STREAM_LEAD_MS = 2_000L
        private const val STREAM_SEEK_GAP_MS = 6_000L
        private const val END_MARGIN_MS = 150L
        private val BT = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 27)
        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
