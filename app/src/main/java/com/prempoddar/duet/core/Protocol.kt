package com.prempoddar.duet.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

@Serializable
data class Member(val id: String, val name: String, val isHost: Boolean, val ready: Boolean = false)

@Serializable
data class ChatLine(val id: String, val from: String, val name: String, val text: String, val at: Long)

/** Control messages, JSON over the reliable "control" channel. */
@Serializable
sealed class Msg {
    @Serializable @SerialName("hello") data class Hello(val name: String, val peerId: String, val have: List<String> = emptyList()) : Msg()
    @Serializable @SerialName("welcome") data class Welcome(val you: String, val members: List<Member>, val state: PlaybackState, val chat: List<ChatLine>) : Msg()
    @Serializable @SerialName("members") data class Members(val members: List<Member>) : Msg()
    @Serializable @SerialName("state") data class State(val state: PlaybackState) : Msg()
    @Serializable @SerialName("cmd") data class Cmd(val command: Command) : Msg()
    @Serializable @SerialName("ping") data class Ping(val t0: Long) : Msg()
    @Serializable @SerialName("pong") data class Pong(val t0: Long, val hostTime: Long) : Msg()
    @Serializable @SerialName("chat") data class Chat(val line: ChatLine) : Msg()
    @Serializable @SerialName("react") data class React(val from: String, val emoji: String) : Msg()
    @Serializable @SerialName("heart") data class Heart(val from: String) : Msg()
    /** "I have this whole file." */
    @Serializable @SerialName("have") data class Have(val trackId: String) : Msg()
    /** Host to a guest who added a track: please upload it. */
    @Serializable @SerialName("want") data class Want(val trackId: String) : Msg()
    /** A guest asks the host to tick at these host times, to match their sound by ear. */
    @Serializable @SerialName("ticks") data class Ticks(val at: List<Long>) : Msg()
    /** The host is leaving and [newHost] takes over; [state] is in the old host's clock. */
    @Serializable @SerialName("handover") data class Handover(val newHost: String, val state: PlaybackState) : Msg()
    @Serializable @SerialName("bye") data class Bye(val reason: String = "") : Msg()

    fun encode(): String = Wire.json.encodeToString(serializer(), this)
}

object Wire {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(text: String): Msg? = runCatching { json.decodeFromString(Msg.serializer(), text) }.getOrNull()

    const val CHUNK = 16 * 1024
    private const val HEADER = 16 + 4 + 4

    /** Binary frame on the "files" channel: `[track id: 16 ascii][index u32][total u32][bytes]`. */
    fun frame(trackId: String, index: Int, total: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER + bytes.size).put(trackId.toByteArray(Charsets.US_ASCII).copyOf(16)).putInt(index).putInt(total).put(bytes).array()

    class Frame(val trackId: String, val index: Int, val total: Int, val bytes: ByteArray)

    fun parseFrame(b: ByteArray): Frame? {
        if (b.size < HEADER) return null
        val buf = ByteBuffer.wrap(b)
        val id = ByteArray(16).also { buf.get(it) }.toString(Charsets.US_ASCII)
        val index = buf.int
        val total = buf.int
        if (total <= 0 || index !in 0 until total || !id.all { it.isLetterOrDigit() }) return null
        return Frame(id, index, total, b.copyOfRange(HEADER, b.size))
    }

    fun chunks(size: Long): Int = maxOf(1, ((size + CHUNK - 1) / CHUNK).toInt())
}
