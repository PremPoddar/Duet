package com.prempoddar.duet.net

import android.content.Context
import com.prempoddar.duet.core.Link
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/** WebRTC set up for data only: two reliable, ordered channels per connection. */
object Rtc {
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
        }
    }

    val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    fun config() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
}

/** A [Link] over two data channels: JSON on "control", file frames on "files". */
class RtcLink(override val id: String, private val pc: PeerConnection) : Link {
    var control: DataChannel? = null
    var files: DataChannel? = null
    val open: Boolean get() = control?.state() == DataChannel.State.OPEN && files?.state() == DataChannel.State.OPEN

    override fun sendText(text: String) {
        control?.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
    }

    override fun sendBinary(bytes: ByteArray) {
        files?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    override val buffered: Long get() = (files?.bufferedAmount() ?: 0) + (control?.bufferedAmount() ?: 0)

    override fun close() {
        runCatching { control?.close(); files?.close(); pc.close() }
    }
}

/** Callbacks a peer connection reports back to whoever owns it. */
interface RtcEvents {
    fun ice(candidate: IceCandidate)
    fun linkOpen(link: RtcLink)
    fun text(link: RtcLink, text: String)
    fun binary(link: RtcLink, bytes: ByteArray)
    fun closed(link: RtcLink, failed: Boolean)
}

/** Builds one peer connection and wires its channels to [events]. */
class RtcPeer(context: Context, linkId: String, private val events: RtcEvents) {
    private var linkRef: RtcLink? = null
    private var reportedOpen = false
    private var reportedClosed = false

    val pc: PeerConnection = Rtc.factory(context).createPeerConnection(Rtc.config(), object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) = events.ice(c)
        override fun onDataChannel(dc: DataChannel) = attach(dc)
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED -> close(failed = true)
                PeerConnection.IceConnectionState.CLOSED -> close(failed = false)
                else -> Unit
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED || state == PeerConnection.PeerConnectionState.FAILED) {
                close(failed = state == PeerConnection.PeerConnectionState.FAILED || !reportedOpen)
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }) ?: error("WebRTC unavailable")

    val link: RtcLink = RtcLink(linkId, pc).also { linkRef = it }

    /** The guest opens both channels before making its offer. */
    fun createChannels() {
        attach(pc.createDataChannel("control", DataChannel.Init().apply { ordered = true }))
        attach(pc.createDataChannel("files", DataChannel.Init().apply { ordered = true }))
    }

    private fun attach(dc: DataChannel) {
        when (dc.label()) {
            "control" -> link.control = dc
            "files" -> link.files = dc
            else -> return
        }
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (link.open && !reportedOpen) {
                    reportedOpen = true
                    events.linkOpen(link)
                }
                if (dc.state() == DataChannel.State.CLOSED) close(failed = false)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                if (buffer.binary) events.binary(link, bytes) else events.text(link, bytes.decodeToString())
            }
        })
    }

    fun offer(onSdp: (String) -> Unit) = pc.createOffer(created { desc ->
        pc.setLocalDescription(set { }, desc)
        onSdp(desc.description)
    }, MediaConstraints())

    fun answer(remoteOffer: String, onSdp: (String) -> Unit) {
        pc.setRemoteDescription(set {
            pc.createAnswer(created { desc ->
                pc.setLocalDescription(set { }, desc)
                onSdp(desc.description)
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
    }

    fun acceptAnswer(remoteAnswer: String) = pc.setRemoteDescription(set { }, SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer))

    fun addIce(mid: String, index: Int, candidate: String) {
        pc.addIceCandidate(IceCandidate(mid, index, candidate))
    }

    @Synchronized private fun close(failed: Boolean) {
        if (reportedClosed) return
        reportedClosed = true
        events.closed(link, failed)
    }

    /** Observer for createOffer/createAnswer. */
    private fun created(then: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = then(desc)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = close(failed = true)
        override fun onSetFailure(error: String?) = Unit
    }

    /** Observer for setLocalDescription/setRemoteDescription. */
    private fun set(then: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = then()
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = close(failed = true)
    }
}
