package com.prempoddar.duet.core

import java.security.MessageDigest

/** One connection to another phone: a reliable text channel and a reliable binary channel. */
interface Link {
    val id: String
    fun sendText(text: String)
    fun sendBinary(bytes: ByteArray)
    /** Bytes queued but not yet sent; used to avoid flooding the connection with file data. */
    val buffered: Long
    fun close()
}

/** Where song files live while the session runs. */
interface TrackStore {
    fun has(id: String): Boolean
    fun size(id: String): Long?
    fun read(id: String, offset: Long, length: Int): ByteArray?
    /** Stores one piece; true once the whole file is in and its digest matches. */
    fun receive(meta: TrackMeta, index: Int, total: Int, bytes: ByteArray): Boolean
    fun progress(id: String): Float
}

sealed interface SessionEvent {
    data class StateChanged(val state: PlaybackState) : SessionEvent
    data class MembersChanged(val members: List<Member>) : SessionEvent
    data class ChatReceived(val line: ChatLine) : SessionEvent
    data class Reaction(val from: String, val name: String, val emoji: String) : SessionEvent
    data class Heart(val from: String, val name: String) : SessionEvent
    data class Progress(val trackId: String, val fraction: Float) : SessionEvent
    data class Ended(val reason: String) : SessionEvent
    /** Play ticks at these times of this phone's clock, for a guest matching by ear. */
    data class Ticks(val at: List<Long>) : SessionEvent
    /** The host left and handed the session to [newHost]; [state]'s times are in this phone's clock. */
    data class HandedOver(val newHost: String, val state: PlaybackState) : SessionEvent
}

/** What the UI and player use, whichever side of the session this phone is on. */
interface Session {
    val myId: String
    val isHost: Boolean
    val state: PlaybackState
    val members: List<Member>
    val chat: List<ChatLine>
    fun hostNow(): Long
    fun command(cmd: Command)
    fun say(text: String)
    fun react(emoji: String)
    fun heart()
    /** A song this phone has in its [TrackStore] goes into the shared queue. */
    fun addTrack(meta: TrackMeta)
    fun tick()
    fun leave()
    /** Guests: ask the host to tick at these host times. */
    fun requestTicks(at: List<Long>) {}
}

/** Sends files over links a piece at a time, never letting a link's buffer grow past [limit]. */
class FileSender(private val store: TrackStore, private val limit: Long = 1_000_000) {
    private class Job(val link: Link, val trackId: String, val total: Int, var next: Int = 0)
    private val jobs = ArrayList<Job>()

    @Synchronized fun sending(link: Link, trackId: String) = jobs.any { it.link === link && it.trackId == trackId }

    @Synchronized fun start(link: Link, trackId: String) {
        if (sending(link, trackId)) return
        val size = store.size(trackId) ?: return
        jobs += Job(link, trackId, Wire.chunks(size))
    }

    @Synchronized fun cancel(link: Link) = jobs.removeAll { it.link === link }

    @Synchronized fun pump() {
        val done = ArrayList<Job>()
        for (job in jobs) {
            while (job.next < job.total && job.link.buffered < limit) {
                val bytes = store.read(job.trackId, job.next.toLong() * Wire.CHUNK, Wire.CHUNK) ?: break
                job.link.sendBinary(Wire.frame(job.trackId, job.next, job.total, bytes))
                job.next++
            }
            if (job.next >= job.total) done += job
        }
        jobs.removeAll(done.toSet())
    }

    val idle: Boolean @Synchronized get() = jobs.isEmpty()
}

private const val MAX_NAME = 32
private const val MAX_CHAT = 500
private const val CHAT_KEEP = 200

/** The host: holds the truth, applies everyone's commands, hands out files. */
class HostSession(
    override val myId: String,
    private val myName: String,
    private val clock: () -> Long,
    private val store: TrackStore,
    initial: PlaybackState = PlaybackState(),
    private val events: (SessionEvent) -> Unit,
) : Session {
    private class Guest(val link: Link, val id: String, val name: String, val have: MutableSet<String>)

    override val isHost = true
    override var state = initial
        private set
    private val guests = LinkedHashMap<String, Guest>()
    private val _chat = ArrayList<ChatLine>()
    private val sender = FileSender(store)
    /** Tracks a guest added that we're still waiting to receive, and from whom. */
    private val uploads = HashMap<String, String>()

    override val chat: List<ChatLine> @Synchronized get() = _chat.toList()

    override val members: List<Member>
        @Synchronized get() {
            val track = state.current
            val current = track?.id
            // Everyone is ready for a stream: there's nothing to receive.
            val stream = track?.isStream == true
            return listOf(Member(myId, myName, isHost = true, ready = current == null || stream || store.has(current))) +
                guests.values.map { Member(it.id, it.name, isHost = false, ready = current == null || stream || current in it.have) }
        }

    override fun hostNow() = clock()

    @Synchronized fun onText(link: Link, text: String) {
        val msg = Wire.decode(text) ?: return
        val guest = guests[link.id]
        if (guest == null) {
            if (msg !is Msg.Hello || guests.size >= MAX_GUESTS) return
            val g = Guest(link, msg.peerId.take(32), msg.name.trim().take(MAX_NAME).ifEmpty { "Guest" }, msg.have.toMutableSet())
            guests[link.id] = g
            link.sendText(Msg.Welcome(g.id, members, state, _chat.takeLast(50)).encode())
            membersChanged()
            schedule()
            return
        }
        when (msg) {
            is Msg.Cmd -> {
                val cmd = msg.command
                if (cmd is Command.Add) {
                    val track = cmd.track.copy(owner = guest.id)
                    if (!track.isStream && !store.has(track.id)) {
                        uploads[track.id] = guest.id
                        link.sendText(Msg.Want(track.id).encode())
                    }
                    apply(Command.Add(track))
                } else if (cmd is Command.Vote) {
                    apply(cmd.copy(by = guest.id))
                } else {
                    apply(cmd)
                }
            }
            is Msg.Ping -> link.sendText(Msg.Pong(msg.t0, clock()).encode())
            is Msg.Chat -> post(guest.id, guest.name, msg.line.text)
            is Msg.React -> fan(guest.id, Msg.React(guest.id, msg.emoji.take(16))).also { events(SessionEvent.Reaction(guest.id, guest.name, msg.emoji.take(16))) }
            is Msg.Heart -> fan(guest.id, Msg.Heart(guest.id)).also { events(SessionEvent.Heart(guest.id, guest.name)) }
            is Msg.Have -> {
                guest.have += msg.trackId
                membersChanged()
            }
            is Msg.Ticks -> events(SessionEvent.Ticks(msg.at.take(EarSync.TICKS * 2)))
            is Msg.Bye -> onClosed(link)
            else -> Unit
        }
    }

    @Synchronized fun onBinary(link: Link, bytes: ByteArray) {
        val guest = guests[link.id] ?: return
        val frame = Wire.parseFrame(bytes) ?: return
        // Only the guest we asked may upload a file, and only one in the queue.
        if (uploads[frame.trackId] != guest.id) return
        val meta = state.queue.firstOrNull { it.id == frame.trackId } ?: return
        val complete = store.receive(meta, frame.index, frame.total, frame.bytes)
        events(SessionEvent.Progress(meta.id, store.progress(meta.id)))
        if (complete) {
            uploads.remove(meta.id)
            guest.have += meta.id
            membersChanged()
            schedule()
        }
    }

    @Synchronized fun onClosed(link: Link) {
        guests.remove(link.id) ?: return
        sender.cancel(link)
        membersChanged()
    }

    @Synchronized override fun command(cmd: Command) = apply(if (cmd is Command.Vote) cmd.copy(by = myId) else cmd)

    @Synchronized override fun say(text: String) = post(myId, myName, text)

    @Synchronized override fun react(emoji: String) {
        fan(null, Msg.React(myId, emoji.take(16)))
    }

    @Synchronized override fun heart() {
        fan(null, Msg.Heart(myId))
    }

    @Synchronized override fun addTrack(meta: TrackMeta) = apply(Command.Add(meta.copy(owner = myId)))

    @Synchronized override fun tick() = sender.pump()

    /** Leaves, giving the session to [guestId] so the music carries on. */
    @Synchronized fun handOver(guestId: String) {
        if (guests.values.none { it.id == guestId }) return leave()
        fan(null, Msg.Handover(guestId, state))
        guests.values.forEach { it.link.close() }
        guests.clear()
    }

    @Synchronized override fun leave() {
        fan(null, Msg.Bye("The host ended the session"))
        guests.values.forEach { it.link.close() }
        guests.clear()
    }

    val guestCount: Int @Synchronized get() = guests.size

    private fun apply(cmd: Command) {
        val next = Reducer.apply(state, cmd, clock())
        if (next == state) return
        state = next
        fan(null, Msg.State(state))
        events(SessionEvent.StateChanged(state))
        membersChanged()
        schedule()
    }

    private fun post(from: String, name: String, text: String) {
        val clean = text.trim().take(MAX_CHAT)
        if (clean.isEmpty()) return
        val line = ChatLine(randomId(), from, name, clean, clock())
        _chat += line
        while (_chat.size > CHAT_KEEP) _chat.removeAt(0)
        fan(null, Msg.Chat(line))
        events(SessionEvent.ChatReceived(line))
    }

    /** Sends to every guest except [except]. */
    private fun fan(except: String?, msg: Msg) {
        val text = msg.encode()
        guests.values.filter { it.id != except }.forEach { it.link.sendText(text) }
    }

    private fun membersChanged() {
        val list = members
        fan(null, Msg.Members(list))
        events(SessionEvent.MembersChanged(list))
    }

    /** Everyone gets the current song and the next one, as soon as we have them. */
    private fun schedule() {
        val wanted = listOfNotNull(state.current, state.queue.getOrNull(state.index + 1)).filter { store.has(it.id) }
        guests.values.forEach { g -> wanted.filter { it.id !in g.have }.forEach { sender.start(g.link, it.id) } }
        sender.pump()
    }

    companion object {
        const val MAX_GUESTS = 7
    }
}

/** A guest: mirrors the host's state, keeps its clock in step, and downloads songs. */
class GuestSession(
    override val myId: String,
    private val myName: String,
    private val clock: () -> Long,
    private val store: TrackStore,
    private val events: (SessionEvent) -> Unit,
) : Session {
    override val isHost = false
    override var state = PlaybackState()
        private set
    override var members: List<Member> = emptyList()
        private set
    private val _chat = ArrayList<ChatLine>()
    override val chat: List<ChatLine> @Synchronized get() = _chat.toList()
    val sync = ClockSync()
    private var link: Link? = null
    private var lastPing = 0L
    private val sender = FileSender(store)
    var welcomed = false
        private set

    @Synchronized fun onOpen(l: Link) {
        link = l
        l.sendText(Msg.Hello(myName, myId).encode())
        ping()
    }

    @Synchronized fun onText(text: String) {
        when (val msg = Wire.decode(text) ?: return) {
            is Msg.Welcome -> {
                welcomed = true
                state = msg.state
                members = msg.members
                _chat.clear(); _chat += msg.chat
                events(SessionEvent.StateChanged(state))
                events(SessionEvent.MembersChanged(members))
                reportHaves()
            }
            is Msg.State -> if (msg.state.version >= state.version) {
                state = msg.state
                events(SessionEvent.StateChanged(state))
            }
            is Msg.Members -> {
                members = msg.members
                events(SessionEvent.MembersChanged(members))
            }
            is Msg.Pong -> sync.add(msg.t0, msg.hostTime, clock())
            is Msg.Chat -> {
                _chat += msg.line
                while (_chat.size > CHAT_KEEP) _chat.removeAt(0)
                events(SessionEvent.ChatReceived(msg.line))
            }
            is Msg.React -> events(SessionEvent.Reaction(msg.from, nameOf(msg.from), msg.emoji))
            is Msg.Heart -> events(SessionEvent.Heart(msg.from, nameOf(msg.from)))
            is Msg.Want -> link?.let { sender.start(it, msg.trackId); sender.pump() }
            is Msg.Handover -> {
                // The old host's clock: turn its times into this phone's, so the new host (whoever
                // it is) can carry on from the same moment.
                val toLocal = clock() - sync.hostNow(clock())
                events(SessionEvent.HandedOver(msg.newHost, msg.state.copy(anchor = msg.state.anchor + toLocal)))
            }
            is Msg.Bye -> events(SessionEvent.Ended(msg.reason.ifEmpty { "The session ended" }))
            else -> Unit
        }
    }

    @Synchronized fun onBinary(bytes: ByteArray) {
        val frame = Wire.parseFrame(bytes) ?: return
        val meta = state.queue.firstOrNull { it.id == frame.trackId } ?: return
        if (store.has(meta.id)) return
        val complete = store.receive(meta, frame.index, frame.total, frame.bytes)
        events(SessionEvent.Progress(meta.id, store.progress(meta.id)))
        if (complete) link?.sendText(Msg.Have(meta.id).encode())
    }

    override fun hostNow() = sync.hostNow(clock())

    @Synchronized override fun command(cmd: Command) = send(Msg.Cmd(cmd))
    @Synchronized override fun say(text: String) {
        val clean = text.trim().take(MAX_CHAT)
        if (clean.isNotEmpty()) send(Msg.Chat(ChatLine("", myId, myName, clean, clock())))
    }
    @Synchronized override fun react(emoji: String) = send(Msg.React(myId, emoji.take(16)))
    @Synchronized override fun heart() = send(Msg.Heart(myId))
    @Synchronized override fun requestTicks(at: List<Long>) = send(Msg.Ticks(at))
    @Synchronized override fun addTrack(meta: TrackMeta) = send(Msg.Cmd(Command.Add(meta.copy(owner = myId))))

    /** Pings quickly until the clock estimate settles, then every few seconds. */
    @Synchronized override fun tick() {
        val now = clock()
        val every = if (sync.count < 8) 250 else 3_000
        if (now - lastPing >= every) ping()
        sender.pump()
    }

    @Synchronized override fun leave() {
        send(Msg.Bye())
        link?.close()
        link = null
    }

    private fun ping() {
        lastPing = clock()
        send(Msg.Ping(lastPing))
    }

    private fun reportHaves() = state.queue.filter { store.has(it.id) }.forEach { send(Msg.Have(it.id)) }

    private fun send(msg: Msg) {
        link?.sendText(msg.encode())
    }

    private fun nameOf(id: String) = members.firstOrNull { it.id == id }?.name ?: "Someone"
}

fun randomId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
