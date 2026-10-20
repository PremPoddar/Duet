package com.prempoddar.duet.session

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.prempoddar.duet.core.ChatLine
import com.prempoddar.duet.core.GuestSession
import com.prempoddar.duet.core.HostSession
import com.prempoddar.duet.core.Member
import com.prempoddar.duet.core.PlaybackState
import com.prempoddar.duet.core.Session
import com.prempoddar.duet.core.SessionCode
import com.prempoddar.duet.core.SessionEvent
import com.prempoddar.duet.core.randomId
import com.prempoddar.duet.data.FileTrackStore
import com.prempoddar.duet.data.Library
import com.prempoddar.duet.net.LanBrowser
import com.prempoddar.duet.net.LanGuest
import com.prempoddar.duet.net.LanHost
import com.prempoddar.duet.net.MqttSignaling
import com.prempoddar.duet.net.NearbySession
import com.prempoddar.duet.net.PairRequest
import com.prempoddar.duet.net.RtcEvents
import com.prempoddar.duet.net.RtcLink
import com.prempoddar.duet.net.RtcPeer
import com.prempoddar.duet.net.Signal
import com.prempoddar.duet.net.Signaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface Phase {
    data object Idle : Phase
    data class Connecting(val message: String) : Phase
    data class Live(val isHost: Boolean) : Phase
    data class Failed(val message: String) : Phase
    data class Ended(val message: String) : Phase
}

data class FloatingReaction(val id: Long, val name: String, val emoji: String)

/**
 * Runs one Duet session on this phone, as host or guest: signalling, WebRTC connections, the
 * session logic, the files, and the stream of things the screens show.
 */
class SessionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = FileTrackStore(File(context.cacheDir, "tracks"))
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()
    private val _code = MutableStateFlow<SessionCode?>(null)
    val code: StateFlow<SessionCode?> = _code.asStateFlow()
    private val _relay = MutableStateFlow(false)
    /** Connected to the public relay (needed only for people joining from elsewhere). */
    val relay: StateFlow<Boolean> = _relay.asStateFlow()
    private val _pairRequests = MutableStateFlow<List<PairRequest>>(emptyList())
    val pairRequests: StateFlow<List<PairRequest>> = _pairRequests.asStateFlow()
    private val _nearby = MutableStateFlow<List<NearbySession>>(emptyList())
    val nearby: StateFlow<List<NearbySession>> = _nearby.asStateFlow()
    private val _reactions = MutableSharedFlow<FloatingReaction>(extraBufferCapacity = 32)
    val reactions: SharedFlow<FloatingReaction> = _reactions.asSharedFlow()
    private val _hearts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val hearts: SharedFlow<String> = _hearts.asSharedFlow()

    @Volatile var session: Session? = null
        private set
    private var hostSession: HostSession? = null
    private var guestSession: GuestSession? = null
    private var mqtt: MqttSignaling? = null
    private var lanHost: LanHost? = null
    private var lanGuest: LanGuest? = null
    private val peers = ConcurrentHashMap<String, RtcPeer>()
    private var hostPeer: RtcPeer? = null
    private var ticker: Job? = null
    private var browser: LanBrowser? = null

    val myName: String get() = myNameValue
    private var myNameValue = ""
    private var myId = randomId()

    // Finding sessions on this Wi-Fi (the home screen keeps this running).

    fun browseNearby() {
        if (browser != null) return
        browser = LanBrowser(context) { _nearby.value = it }.also { it.start() }
    }

    fun stopBrowsing() {
        browser?.stop(); browser = null
        _nearby.value = emptyList()
    }

    // Hosting

    fun host(name: String) = startHosting(name, SessionCode.generate())

    /** Hosts with [code]; [initial] carries on a session handed over to this phone. */
    private fun startHosting(name: String, code: SessionCode, initial: PlaybackState = PlaybackState(), keepSongs: Boolean = false) {
        reset(keepSongs = keepSongs)
        myNameValue = name
        _code.value = code
        val keys = code.keys
        val host = HostSession(myId, name, clock, store, initial, ::onEvent)
        hostSession = host
        session = host
        _phase.value = Phase.Live(isHost = true)
        publish()
        mqtt = MqttSignaling(keys.topic, keys.key, "host", { onHostSignal(it, viaLan = false) }, { _relay.value = it }).also { it.connect() }
        lanHost = runCatching {
            LanHost(context, scope, name, code, { s, _ -> onHostSignal(s, viaLan = true) }) { req ->
                _pairRequests.value = _pairRequests.value + req
            }.also { it.start() }
        }.getOrNull()
        startTicking()
    }

    fun approve(req: PairRequest) {
        lanHost?.approve(req)
        _pairRequests.value = _pairRequests.value - req
    }

    fun deny(req: PairRequest) {
        lanHost?.deny(req)
        _pairRequests.value = _pairRequests.value - req
    }

    private fun replyTo(peer: String, signal: Signal) {
        val lan = lanHost
        if (lan != null && lan.knows(peer)) lan.send(peer, signal) else mqtt?.send(peer, signal)
    }

    private fun onHostSignal(signal: Signal, viaLan: Boolean) {
        val host = hostSession ?: return
        when (signal) {
            is Signal.Offer -> {
                if (host.guestCount >= HostSession.MAX_GUESTS) {
                    replyTo(signal.from, Signal.Full("host"))
                    return
                }
                peers.remove(signal.from)?.link?.close()
                val peer = RtcPeer(context, signal.from, object : RtcEvents {
                    override fun ice(candidate: IceCandidate) =
                        replyTo(signal.from, Signal.Ice("host", candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
                    override fun linkOpen(link: RtcLink) = Unit // the guest says hello first
                    override fun text(link: RtcLink, text: String) = host.onText(link, text)
                    override fun binary(link: RtcLink, bytes: ByteArray) = host.onBinary(link, bytes)
                    override fun closed(link: RtcLink, failed: Boolean) {
                        host.onClosed(link)
                        peers.remove(signal.from)
                        link.close()
                    }
                })
                peers[signal.from] = peer
                peer.answer(signal.sdp) { sdp -> replyTo(signal.from, Signal.Answer("host", sdp)) }
            }
            is Signal.Ice -> peers[signal.from]?.addIce(signal.mid, signal.index, signal.candidate)
            else -> Unit
        }
    }

    // Joining

    /** Joins with a code; uses the local network if that host is on this Wi-Fi, else the relay. */
    fun join(code: SessionCode, name: String, lan: LanGuest? = null, keepSongs: Boolean = false) {
        reset(keepLan = lan, keepSongs = keepSongs)
        myNameValue = name
        _code.value = code
        _phase.value = Phase.Connecting("Finding the session…")
        val keys = code.keys
        val guest = GuestSession(myId, name, clock, store, ::onEvent)
        guestSession = guest
        session = guest
        var opened = false

        val onSignal: (Signal) -> Unit = { s ->
            when (s) {
                is Signal.Answer -> hostPeer?.acceptAnswer(s.sdp).also { _phase.value = Phase.Connecting("Connecting…") }
                is Signal.Ice -> hostPeer?.addIce(s.mid, s.index, s.candidate)
                is Signal.Full -> fail("This session already has 8 people.")
                else -> Unit
            }
        }
        val signaling: Signaling = lan?.also { lanGuest = it } ?: run {
            val nearbyHost = _nearby.value.firstOrNull { it.topicPrefix.isNotEmpty() && keys.topic.startsWith(it.topicPrefix) }
            if (nearbyHost != null) {
                LanGuest(scope, nearbyHost, onSignal).also { g ->
                    g.key = keys.key
                    lanGuest = g
                    scope.launch { runCatching { g.connect() }.onFailure { fail("Couldn't reach the host on this Wi-Fi.") } }
                }
            } else {
                MqttSignaling(keys.topic, keys.key, myId, onSignal) { _relay.value = it }.also { mqtt = it; it.connect() }
            }
        }
        val peer = RtcPeer(context, "host", object : RtcEvents {
            override fun ice(candidate: IceCandidate) = signaling.send("host", Signal.Ice(myId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
            override fun linkOpen(link: RtcLink) {
                opened = true
                guest.onOpen(link)
                _phase.value = Phase.Live(isHost = false)
                publish()
            }
            override fun text(link: RtcLink, text: String) = guest.onText(text)
            override fun binary(link: RtcLink, bytes: ByteArray) = guest.onBinary(bytes)
            override fun closed(link: RtcLink, failed: Boolean) {
                if (!opened) fail(NO_DIRECT_PATH) else end("Lost the connection to the host.")
            }
        })
        hostPeer = peer
        peer.createChannels()
        peer.offer { sdp -> signaling.send("host", Signal.Offer(myId, name, sdp)) }
        startTicking()
        scope.launch {
            delay(JOIN_TIMEOUT_MS)
            if (!opened && session === guest) fail(if (_relay.value || lanGuest != null) NO_DIRECT_PATH else NO_RELAY)
        }
    }

    /** Joins a session on this Wi-Fi by asking its host to let us in. */
    fun joinNearby(target: NearbySession, name: String) {
        reset()
        _phase.value = Phase.Connecting("Asking ${target.name} to let you in…")
        lateinit var lan: LanGuest
        var pendingSignals: ((Signal) -> Unit)? = null
        lan = LanGuest(scope, target) { s -> pendingSignals?.invoke(s) }
        scope.launch {
            runCatching {
                lan.connect(pairName = name) { code ->
                    if (code == null) {
                        fail("${target.name} didn't let you in.")
                    } else {
                        // Signals from now on go to the session we're about to start.
                        join(code, name, lan)
                        pendingSignals = { s -> routeGuestSignal(s) }
                    }
                }
            }.onFailure { fail("Couldn't reach ${target.name} on this Wi-Fi.") }
        }
    }

    private fun routeGuestSignal(s: Signal) {
        when (s) {
            is Signal.Answer -> hostPeer?.acceptAnswer(s.sdp)
            is Signal.Ice -> hostPeer?.addIce(s.mid, s.index, s.candidate)
            is Signal.Full -> fail("This session already has 8 people.")
            else -> Unit
        }
    }

    // While live

    suspend fun addSong(uri: Uri) {
        val s = session ?: return
        val meta = Library.import(context, uri, store, myId)
        s.addTrack(meta)
    }

    /** Adds a YouTube video to the queue; every phone streams it, nothing is sent. */
    fun addStream(meta: com.prempoddar.duet.core.TrackMeta) {
        session?.addTrack(meta)
    }

    fun leave() {
        session?.leave()
        reset()
        _phase.value = Phase.Idle
    }

    fun dismissEnd() {
        if (_phase.value is Phase.Failed || _phase.value is Phase.Ended) _phase.value = Phase.Idle
    }

    private fun onEvent(e: SessionEvent) {
        when (e) {
            is SessionEvent.StateChanged -> _state.value = e.state
            is SessionEvent.MembersChanged -> _members.value = e.members
            is SessionEvent.ChatReceived -> _chat.value = (_chat.value + e.line).takeLast(200)
            is SessionEvent.Reaction -> _reactions.tryEmit(FloatingReaction(System.nanoTime(), e.name, e.emoji))
            is SessionEvent.Heart -> {
                _hearts.tryEmit(e.name)
                heartbeat()
            }
            is SessionEvent.Progress -> _progress.value = _progress.value + (e.trackId to e.fraction)
            is SessionEvent.Ended -> end(e.reason)
            is SessionEvent.HandedOver -> carryOn(e)
            // Ticks come out as early as the music does, so they show where the music really is.
            is SessionEvent.Ticks -> com.prempoddar.duet.play.TickPlayer.play(
                e.at, com.prempoddar.duet.core.EarSync.HOST_HZ, com.prempoddar.duet.AppGraph.player.let { it.settings.nudge(it.output.value) }.toLong(),
            )
        }
    }

    private fun publish() {
        val s = session ?: return
        _state.value = s.state
        _members.value = s.members
        _chat.value = s.chat
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                session?.tick()
                delay(TICK_MS)
            }
        }
    }

    /** A heartbeat: two beats, the second softer. */
    private fun heartbeat() {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 110, 90, 600, 70, 110, 90), intArrayOf(0, 255, 0, 140, 0, 255, 0, 140), -1)) }
    }

    private fun fail(message: String) {
        reset()
        _phase.value = Phase.Failed(message)
    }

    private fun end(message: String) {
        reset()
        _phase.value = Phase.Ended(message)
    }

    private fun rejoin(code: SessionCode, name: String) = join(code, name, keepSongs = true)

    /** Leaves as host, handing the session to [guestId]. */
    fun handOver(guestId: String) {
        hostSession?.handOver(guestId)
        reset()
        _phase.value = Phase.Idle
    }

    /**
     * The host left and named a new one. That phone hosts under the same code from the same
     * moment; everyone else reconnects to it a few seconds later. Songs already here are kept.
     */
    private fun carryOn(e: SessionEvent.HandedOver) {
        val code = _code.value ?: return end("The host left")
        val name = myNameValue
        if (e.newHost == myId) {
            startHosting(name, code, e.state.copy(version = e.state.version + 1), keepSongs = true)
        } else {
            _phase.value = Phase.Connecting("The host left — reconnecting…")
            scope.launch {
                kotlinx.coroutines.delay(HANDOVER_WAIT_MS)
                rejoin(code, name)
            }
        }
    }

    private fun reset(keepLan: LanGuest? = null, keepSongs: Boolean = false) {
        ticker?.cancel(); ticker = null
        peers.values.forEach { it.link.close() }; peers.clear()
        hostPeer?.link?.close(); hostPeer = null
        mqtt?.close(); mqtt = null
        lanHost?.close(); lanHost = null
        if (lanGuest !== keepLan) lanGuest?.close()
        lanGuest = null
        session = null; hostSession = null; guestSession = null
        if (!keepSongs) store.clear()
        _state.value = PlaybackState(); _members.value = emptyList(); _chat.value = emptyList()
        _progress.value = emptyMap(); _code.value = null; _pairRequests.value = emptyList(); _relay.value = false
        myId = randomId()
    }

    companion object {
        private const val TICK_MS = 50L
        /** How long guests give the new host to come up before reconnecting. */
        private const val HANDOVER_WAIT_MS = 3_000L
        private const val JOIN_TIMEOUT_MS = 30_000L
        const val NO_DIRECT_PATH = "Your two networks won't let the phones connect directly — some mobile networks block it. Try with one of you on Wi-Fi."
        const val NO_RELAY = "Couldn't reach the connection relay. Check that you're online, or join from the same Wi-Fi."
    }
}
