package com.prempoddar.duet.play

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.prempoddar.duet.core.Command
import com.prempoddar.duet.core.PlaybackState
import com.prempoddar.duet.core.TrackMeta
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.data.Output
import com.prempoddar.duet.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Plays the shared song on this phone and keeps it on the shared position. Songs sent between the
 * phones are decoded once and played by [Engine], which matches them to the shared clock sample by
 * sample; YouTube videos and other apps' songs are checked every 200 ms.
 */
class PlayerController(private val context: Context, private val sessions: SessionManager) {
    /** What the lock screen and notification show. */
    val player = SharedPlayer()
    private val engine = Engine()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var endedSent: String? = null
    @Volatile var settings = DuetSettings()

    private val pcmDir = File(context.cacheDir, "pcm")
    private val decoder = Executors.newSingleThreadExecutor()
    private val decoded = ConcurrentHashMap<String, Pcm>()
    private val decoding = ConcurrentHashMap.newKeySet<String>()

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
        engine.start()
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun step() {
        _output.value = currentOutput()
        val session = sessions.session
        val st = sessions.state.value
        val track = st.current
        if (session == null || track == null) {
            engine.want = null
            focus(false)
            player.show(null, false) { 0 }
            YouTubePlayer.stop()
            return
        }
        player.show(track.takeIf { it.external == null }, st.playing) { st.positionAt(session.hostNow()) }
        if (moveOf(st) != shownMove) { shownMove = moveOf(st); player.moved() }
        if (track.isStream) focus(false)
        if (track.external != null) {
            YouTubePlayer.stop()
            engine.want = null
            externalStep(session, st, track)
            return
        }
        if (track.isStream) {
            engine.want = null
            streamStep(session, st, track)
            return
        }
        YouTubePlayer.stop()
        st.queue.getOrNull(st.index + 1)?.let { prepare(it) }
        val pcm = prepare(track)
        if (pcm == null) {
            // Still arriving or being decoded; the others carry on and we join in when it's here.
            engine.want = null
            return
        }
        val nudge = settings.nudge(_output.value)
        aim(session, st, track, pcm, nudge.toLong())
        focus(st.playing)
        if (!st.playing) { _drift.value = 0; return }
        _drift.value = engine.driftMs.toLong()
        val target = st.positionAt(session.hostNow()) + nudge
        if (target >= track.durationMs - END_MARGIN_MS && endedSent != track.id) {
            endedSent = track.id
            session.command(Command.Ended(track.id))
        }
    }

    private var aimedAt: Any? = null
    private var aimedNs = 0L

    /**
     * Tells the engine where the song should be at any moment. The shared clock is read once and
     * carried forward with this phone's own precise clock, and read again now and then as the
     * estimate of the host's clock is refined.
     */
    private fun aim(session: com.prempoddar.duet.core.Session, st: PlaybackState, track: TrackMeta, pcm: Pcm, nudge: Long) {
        val key = Triple(st, nudge, pcm.id)
        val now = System.nanoTime()
        if (key == aimedAt && now - aimedNs < REAIM_NS) return
        aimedAt = key; aimedNs = now
        val host = session.hostNow().toDouble()
        val length = track.durationMs.toDouble()
        engine.want = Engine.Want(pcm, st.playing) { n ->
            val h = host + (n - now) / 1e6
            val pos = if (st.playing) st.positionMs + (h - st.anchor) else st.positionMs.toDouble()
            (if (length > 0) pos.coerceIn(0.0, length) else pos.coerceAtLeast(0.0)) + nudge
        }
    }

    /** The song ready to play, or null while it's decoded in the background (starting that if needed). */
    private fun prepare(track: TrackMeta): Pcm? {
        if (track.isStream) return null
        decoded[track.id]?.let { return it }
        val file = sessions.store.file(track.id) ?: return null
        if (decoding.add(track.id)) decoder.execute {
            try {
                pcmDir.mkdirs()
                decoded[track.id] = Pcm.decode(track.id, file, File(pcmDir, track.id))
                tidy()
            } catch (e: Exception) {
                android.util.Log.w("DuetEngine", "couldn't decode ${track.title}", e)
            } finally {
                decoding.remove(track.id)
            }
        }
        return null
    }

    /** Decoded songs take room: only the current and next are kept. */
    private fun tidy() {
        val st = sessions.state.value
        val keep = setOfNotNull(st.current?.id, st.queue.getOrNull(st.index + 1)?.id)
        decoded.keys.filter { it !in keep }.forEach { decoded.remove(it) }
        pcmDir.listFiles()?.filter { it.name !in keep && !it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { }
        .build()
    private var focused = false

    /** Other apps' music stops while Duet plays; the shared session decides when Duet itself stops. */
    private fun focus(want: Boolean) {
        if (want == focused) return
        focused = want
        if (want) audio.requestAudioFocus(focusRequest) else audio.abandonAudioFocusRequest(focusRequest)
    }

    private var shownMove: Triple<String?, Long, Long>? = null

    /** The shared state's last jump (its song, anchor and position), to notice the next one. */
    private var lastMove: Triple<String?, Long, Long>? = null
    private fun moveOf(st: com.prempoddar.duet.core.PlaybackState) = Triple(st.current?.id, st.anchor, st.positionMs)

    private var lastStreamSeek = 0L
    /** How far ahead to aim a jump, learnt from how far behind the video is after one. */
    private var streamLead = STREAM_JUMP_LEAD_MS
    private var checkAfterSeek = false

    /**
     * A YouTube video: the embedded player can't change speed finely, so drift is corrected by
     * jumping, and only when it is large enough to hear and not more than every few seconds.
     */
    private fun streamStep(session: com.prempoddar.duet.core.Session, st: com.prempoddar.duet.core.PlaybackState, track: com.prempoddar.duet.core.TrackMeta) {
        val yt = YouTubePlayer
        val now = android.os.SystemClock.elapsedRealtime()
        val target = st.positionAt(session.hostNow()) + settings.nudge(_output.value)
        if (yt.videoId != track.youtube) {
            // Aimed ahead by what loading and starting have cost before, and checked once it
            // plays, so a new video starts in step instead of being pulled in afterwards.
            yt.load(track.youtube!!, (target + if (st.playing) streamLead else 0).coerceAtLeast(0), st.playing)
            lastStreamSeek = now
            checkAfterSeek = st.playing
            lastMove = moveOf(st)
            return
        }
        if (yt.ended) {
            if (endedSent != track.id) { endedSent = track.id; session.command(Command.Ended(track.id)) }
            return
        }
        if (!st.playing) {
            if (yt.playing) yt.pause()
            yt.setRate(1.0)
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
        if (moveOf(st) != lastMove) {
            lastMove = moveOf(st)
            if (kotlin.math.abs(drift) > MOVE_REALIGN_MS) {
                yt.setRate(1.0)
                yt.seek((target + streamLead).coerceAtLeast(0))
                lastStreamSeek = now
                checkAfterSeek = true
                return
            }
        }
        // Once playing again after a jump, whatever is still off is how much the jump itself cost.
        if (checkAfterSeek && now - lastStreamSeek > 2_000) {
            checkAfterSeek = false
            streamLead = (streamLead + drift).coerceIn(0, MAX_STREAM_LEAD_MS)
        }
        if (now - lastStreamSeek < 2_000) return
        val size = kotlin.math.abs(drift)
        // Small drift is eased out by playing 5% faster or slower, where YouTube allows it; that's
        // silent, while a jump means a moment of buffering. Big drift, or no fine speeds, jumps.
        val canEase = yt.rateWorks != false
        val jumpOver = if (canEase) STREAM_JUMP_OVER_MS else STREAM_TOLERANCE_MS
        if (size > jumpOver && now - lastStreamSeek > STREAM_SEEK_GAP_MS) {
            android.util.Log.i("DuetSync", "stream drift $drift ms, jumping with lead $streamLead")
            yt.setRate(1.0)
            yt.seek((target + streamLead).coerceAtLeast(0))
            lastStreamSeek = now
            checkAfterSeek = true
            return
        }
        if (!canEase) return
        val wanted = when {
            size < STREAM_EASED_MS -> 1.0
            size > STREAM_EASE_FROM_MS -> if (drift > 0) 1.05 else 0.95
            else -> yt.rate
        }
        yt.setRate(wanted)
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
        private const val STREAM_TOLERANCE_MS = 200L
        private const val STREAM_JUMP_OVER_MS = 1_200L
        private const val STREAM_EASE_FROM_MS = 50L
        private const val STREAM_EASED_MS = 15L
        private const val STREAM_JUMP_LEAD_MS = 300L
        private const val MAX_STREAM_LEAD_MS = 2_000L
        private const val STREAM_SEEK_GAP_MS = 4_000L
        private const val END_MARGIN_MS = 150L
        private const val REAIM_NS = 2_000_000_000L
        /** After a shared jump, anything further off than this is lined up straight away. */
        private const val MOVE_REALIGN_MS = 150L
        private val BT = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 27)
        private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
