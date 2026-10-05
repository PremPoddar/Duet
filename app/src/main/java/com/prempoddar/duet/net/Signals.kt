package com.prempoddar.duet.net

import com.prempoddar.duet.core.Sealer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What two phones exchange to set up a WebRTC connection. Always sealed with the session key. */
@Serializable
sealed class Signal {
    abstract val from: String

    @Serializable @SerialName("offer") data class Offer(override val from: String, val name: String, val sdp: String) : Signal()
    @Serializable @SerialName("answer") data class Answer(override val from: String, val sdp: String) : Signal()
    @Serializable @SerialName("ice") data class Ice(override val from: String, val mid: String, val index: Int, val candidate: String) : Signal()
    @Serializable @SerialName("full") data class Full(override val from: String) : Signal()

    companion object {
        private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        fun seal(key: ByteArray, s: Signal): ByteArray = Sealer.seal(key, json.encodeToString(serializer(), s).toByteArray())

        fun open(key: ByteArray, bytes: ByteArray): Signal? =
            Sealer.open(key, bytes)?.let { runCatching { json.decodeFromString(serializer(), it.decodeToString()) }.getOrNull() }
    }
}

/** A way to pass sealed signals between host and guests: the public MQTT relay or the local network. */
interface Signaling {
    /** [to] is "host" or a guest's peer id. */
    fun send(to: String, signal: Signal)
    fun close()
}
