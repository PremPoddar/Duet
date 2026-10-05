package com.prempoddar.duet.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import com.prempoddar.duet.core.Pairing
import com.prempoddar.duet.core.SessionCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** One line of the local protocol. Signals inside are sealed with the session key. */
@Serializable
sealed class LanFrame {
    @Serializable @SerialName("pair") data class PairRequest(val name: String, val key: String) : LanFrame()
    @Serializable @SerialName("approved") data class PairApproved(val hostKey: String, val code: String) : LanFrame()
    @Serializable @SerialName("denied") data object PairDenied : LanFrame()
    @Serializable @SerialName("signal") data class Sealed(val data: String) : LanFrame()

    fun line(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
        fun parse(line: String): LanFrame? = runCatching { json.decodeFromString(serializer(), line) }.getOrNull()
    }
}

private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
private fun unb64(s: String) = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()

data class PairRequest(val id: String, val name: String)

/**
 * The host's side of same-room mode: a TCP server advertised on the Wi-Fi with NSD. Guests either
 * already know the code (and just signal) or ask to join and wait for the host to approve.
 */
class LanHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hostName: String,
    private val code: SessionCode,
    private val onSignal: (Signal, LanHost) -> Unit,
    private val onPairRequest: (PairRequest) -> Unit,
) : Signaling {
    private class Conn(val socket: Socket, val out: PrintWriter, var peer: String? = null, var pairKey: ByteArray? = null)

    private val key = code.keys.key
    private val server = ServerSocket(0)
    private val conns = ConcurrentHashMap<String, Conn>()
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { serve(socket) }
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = hostName.take(40)
            serviceType = SERVICE_TYPE
            port = server.localPort
            setAttribute("t", code.keys.topic.take(8))
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
    }

    private fun serve(socket: Socket) {
        val id = java.util.UUID.randomUUID().toString()
        val conn = Conn(socket, PrintWriter(socket.getOutputStream().bufferedWriter(), true))
        conns[id] = conn
        runCatching {
            val reader: BufferedReader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > 64_000) break
                when (val f = LanFrame.parse(line)) {
                    is LanFrame.PairRequest -> {
                        conn.pairKey = unb64(f.key)?.takeIf { it.size == 32 } ?: continue
                        onPairRequest(PairRequest(id, f.name.take(32)))
                    }
                    is LanFrame.Sealed -> {
                        val signal = unb64(f.data)?.let { Signal.open(key, it) } ?: continue
                        conn.peer = signal.from
                        onSignal(signal, this)
                    }
                    else -> Unit
                }
            }
        }
        conns.remove(id)
        runCatching { socket.close() }
    }

    fun approve(request: PairRequest) {
        val conn = conns[request.id] ?: return
        val approval = Pairing.approve(conn.pairKey ?: return, code)
        conn.out.println(LanFrame.PairApproved(b64(approval.hostKey), b64(approval.sealedCode)).line())
    }

    fun deny(request: PairRequest) {
        conns[request.id]?.out?.println(LanFrame.PairDenied.line())
    }

    /** True if [peer] reached us over the local network (so signals go back the same way). */
    fun knows(peer: String) = conns.values.any { it.peer == peer }

    override fun send(to: String, signal: Signal) {
        val conn = conns.values.firstOrNull { it.peer == to } ?: return
        scope.launch(Dispatchers.IO) { conn.out.println(LanFrame.Sealed(b64(Signal.seal(key, signal))).line()) }
    }

    override fun close() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        conns.values.forEach { runCatching { it.socket.close() } }
        runCatching { server.close() }
    }

    companion object {
        const val SERVICE_TYPE = "_duet._tcp."
    }
}

data class NearbySession(val name: String, val host: String, val port: Int, val topicPrefix: String)

/** Finds Duet sessions on this Wi-Fi. */
class LanBrowser(context: Context, private val onChange: (List<NearbySession>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = ConcurrentHashMap<String, NearbySession>()
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                onChange(found.values.toList())
            }
        }
        listener = l
        runCatching { nsd.discoverServices(LanHost.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION") // resolveService still works everywhere; the new API needs Android 14.
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(r: NsdServiceInfo) {
                    val host = r.host?.hostAddress ?: return
                    val topic = r.attributes["t"]?.decodeToString().orEmpty()
                    found[r.serviceName] = NearbySession(r.serviceName, host, r.port, topic)
                    onChange(found.values.toList())
                }
            })
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }
}

/** A guest's connection to a host on the same Wi-Fi: pairing first if needed, then signals. */
class LanGuest(
    private val scope: CoroutineScope,
    private val session: NearbySession,
    private val onSignal: (Signal) -> Unit,
) : Signaling {
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    var key: ByteArray? = null

    /** Connects; with [pairName], asks the host to let us in and returns the code they approve. */
    suspend fun connect(pairName: String? = null, onPaired: (SessionCode?) -> Unit = {}) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val s = Socket().apply { connect(InetSocketAddress(session.host, session.port), 5_000) }
        socket = s
        out = PrintWriter(s.getOutputStream().bufferedWriter(), true)
        val request = pairName?.let { Pairing.Request() }
        if (request != null) out!!.println(LanFrame.PairRequest(pairName, b64(request.publicKey)).line())
        scope.launch(Dispatchers.IO) {
            runCatching {
                val reader = s.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val f = LanFrame.parse(line)) {
                        is LanFrame.PairApproved -> {
                            val hostKey = unb64(f.hostKey); val sealed = unb64(f.code)
                            val code = if (request != null && hostKey != null && sealed != null) request.accept(Pairing.Approval(hostKey, sealed)) else null
                            code?.let { key = it.keys.key }
                            onPaired(code)
                        }
                        LanFrame.PairDenied -> onPaired(null)
                        is LanFrame.Sealed -> {
                            val k = key ?: continue
                            unb64(f.data)?.let { Signal.open(k, it) }?.let(onSignal)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun send(to: String, signal: Signal) {
        val k = key ?: return
        val o = out ?: return
        scope.launch(Dispatchers.IO) { o.println(LanFrame.Sealed(b64(Signal.seal(k, signal))).line()) }
    }

    override fun close() {
        runCatching { socket?.close() }
    }
}
