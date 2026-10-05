package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class MemoryStore : TrackStore {
    val files = HashMap<String, ByteArray>()
    private val partial = HashMap<String, Array<ByteArray?>>()
    override fun has(id: String) = id in files
    override fun size(id: String) = files[id]?.size?.toLong()
    override fun read(id: String, offset: Long, length: Int): ByteArray? {
        val f = files[id] ?: return null
        if (offset >= f.size) return null
        return f.copyOfRange(offset.toInt(), minOf(f.size, (offset + length).toInt()))
    }
    override fun receive(meta: TrackMeta, index: Int, total: Int, bytes: ByteArray): Boolean {
        if (meta.id in files) return true
        val parts = partial.getOrPut(meta.id) { arrayOfNulls(total) }
        if (parts.size != total) return false
        parts[index] = bytes
        if (parts.any { it == null }) return false
        val whole = parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
        partial.remove(meta.id)
        if (sha256Hex(whole) != meta.digest) return false
        files[meta.id] = whole
        return true
    }
    override fun progress(id: String): Float = if (id in files) 1f else partial[id]?.let { p -> p.count { it != null }.toFloat() / p.size } ?: 0f
}

/** Host and guests joined by in-memory links; [settle] delivers everything queued. */
class TestRoom {
    var hostClock = 100_000L
    val queue = ArrayDeque<() -> Unit>()
    val hostStore = MemoryStore()
    val hostEvents = ArrayList<SessionEvent>()
    val host = HostSession("host", "Prem", { hostClock }, hostStore) { hostEvents += it }

    inner class Guest(val name: String, val clockSkew: Long) {
        val store = MemoryStore()
        val events = ArrayList<SessionEvent>()
        val session = GuestSession("g-$name", name, { hostClock + clockSkew }, store) { events += it }
        private var pending: Long = 0L
        /** Host's end of the link. */
        val toGuest: Link = object : Link {
            override val id = "link-$name"
            override fun sendText(text: String) { queue.addLast { session.onText(text) } }
            override fun sendBinary(bytes: ByteArray) { queue.addLast { session.onBinary(bytes) } }
            override val buffered get() = 0L
            override fun close() {}
        }
        /** Guest's end of the link. */
        val toHost: Link = object : Link {
            override val id = "up-$name"
            override fun sendText(text: String) { queue.addLast { host.onText(toGuest, text) } }
            override fun sendBinary(bytes: ByteArray) { queue.addLast { host.onBinary(toGuest, bytes) } }
            override val buffered: Long get() = pending
            override fun close() { queue.addLast { host.onClosed(toGuest) } }
        }
    }

    fun join(name: String, skew: Long = 0): Guest = Guest(name, skew).also { it.session.onOpen(it.toHost); settle() }

    fun settle() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(++guard < 1_000_000)
            queue.removeFirst()()
        }
    }

    fun track(id: String, bytes: ByteArray, store: MemoryStore, owner: String = "host"): TrackMeta {
        store.files[id] = bytes
        return TrackMeta(id, "Song $id", durationMs = 180_000, mime = "audio/mpeg", size = bytes.size.toLong(), owner = owner, digest = sha256Hex(bytes))
    }
}

class SessionTest {
    private val room = TestRoom()
    private val song = Random(1).nextBytes(100_000)

    @Test fun guestJoinsAndSeesMembers() {
        val g = room.join("Asha")
        assertThat(g.session.welcomed).isTrue()
        assertThat(g.session.members.map { it.name }).containsExactly("Prem", "Asha").inOrder()
        assertThat(room.host.members.single { !it.isHost }.name).isEqualTo("Asha")
    }

    @Test fun clockSyncCancelsSkew() {
        val g = room.join("Asha", skew = -7_000)
        repeat(10) { room.hostClock += 300; g.session.tick(); room.settle() }
        assertThat(g.session.hostNow()).isEqualTo(room.hostClock)
    }

    @Test fun hostTrackReachesEveryoneAndTheyBecomeReady() {
        val a = room.join("Asha"); val b = room.join("Ben")
        val meta = room.track("aaaaaaaaaaaaaaaa", song, room.hostStore)
        room.host.addTrack(meta)
        repeat(20) { room.host.tick(); room.settle() }
        assertThat(a.store.files[meta.id]).isEqualTo(song)
        assertThat(b.store.files[meta.id]).isEqualTo(song)
        assertThat(room.host.members.all { it.ready }).isTrue()
        assertThat(a.session.state.playing).isTrue()
        assertThat(a.session.state.current).isEqualTo(meta)
    }

    @Test fun guestCommandsChangeEveryonesState() {
        val a = room.join("Asha"); val b = room.join("Ben")
        room.host.addTrack(room.track("aaaaaaaaaaaaaaaa", song, room.hostStore))
        room.settle()
        room.hostClock += 10_000
        a.session.command(Command.Pause); room.settle()
        assertThat(room.host.state.playing).isFalse()
        assertThat(b.session.state.playing).isFalse()
        assertThat(b.session.state.positionMs).isEqualTo(10_000)
    }

    @Test fun guestTrackIsUploadedThenForwarded() {
        val a = room.join("Asha"); val b = room.join("Ben")
        val mine = Random(2).nextBytes(70_000)
        val meta = room.track("bbbbbbbbbbbbbbbb", mine, a.store, owner = "g-Asha")
        a.session.addTrack(meta)
        repeat(30) { a.session.tick(); room.host.tick(); room.settle() }
        assertThat(room.hostStore.files[meta.id]).isEqualTo(mine)
        assertThat(b.store.files[meta.id]).isEqualTo(mine)
        assertThat(room.host.state.current!!.owner).isEqualTo("g-Asha")
    }

    @Test fun uploadsFromSomeoneElseAreIgnored() {
        val a = room.join("Asha"); val b = room.join("Ben")
        val meta = room.track("cccccccccccccccc", song, a.store, owner = "g-Asha")
        a.session.addTrack(meta); room.settle()
        // Ben tries to push a different file under Asha's track id.
        val fake = Random(3).nextBytes(100)
        room.host.onBinary(b.toGuest, Wire.frame(meta.id, 0, 1, fake))
        assertThat(room.hostStore.files[meta.id]).isEqualTo(song)
        // And nobody can push a file the host never asked for.
        room.host.onBinary(b.toGuest, Wire.frame("ffffffffffffffff", 0, 1, fake))
        assertThat(room.hostStore.has("ffffffffffffffff")).isFalse()
    }

    @Test fun corruptedFileIsRejected() {
        val a = room.join("Asha")
        val meta = room.track("dddddddddddddddd", song, room.hostStore).copy(digest = "00")
        room.host.addTrack(meta)
        repeat(20) { room.host.tick(); room.settle() }
        assertThat(a.store.has(meta.id)).isFalse()
    }

    @Test fun chatReactionsAndHearts() {
        val a = room.join("Asha"); val b = room.join("Ben")
        a.session.say("  hello  "); room.settle()
        assertThat(b.session.chat.single().text).isEqualTo("hello")
        assertThat(b.session.chat.single().name).isEqualTo("Asha")
        assertThat(a.session.chat.single().text).isEqualTo("hello") // echoed back
        a.session.heart(); room.settle()
        assertThat(b.events.filterIsInstance<SessionEvent.Heart>().single().name).isEqualTo("Asha")
        assertThat(a.events.filterIsInstance<SessionEvent.Heart>()).isEmpty()
        room.host.react("🔥"); room.settle()
        assertThat(b.events.filterIsInstance<SessionEvent.Reaction>().single().emoji).isEqualTo("🔥")
    }

    @Test fun leavingAndHostEnding() {
        val a = room.join("Asha"); val b = room.join("Ben")
        a.session.leave(); room.settle()
        assertThat(room.host.members.map { it.name }).containsExactly("Prem", "Ben")
        room.host.leave(); room.settle()
        assertThat(b.events.filterIsInstance<SessionEvent.Ended>()).isNotEmpty()
    }

    @Test fun sessionIsCappedAtEightPeople() {
        repeat(HostSession.MAX_GUESTS) { room.join("g$it") }
        val late = room.join("late")
        assertThat(late.session.welcomed).isFalse()
        assertThat(room.host.guestCount).isEqualTo(HostSession.MAX_GUESTS)
    }

    @Test fun senderRespectsLinkBuffer() {
        val store = MemoryStore().apply { files["eeeeeeeeeeeeeeee"] = song }
        var sent = 0
        var full = true
        val link = object : Link {
            override val id = "x"
            override fun sendText(text: String) {}
            override fun sendBinary(bytes: ByteArray) { sent++ }
            override val buffered get() = if (full) 2_000_000L else 0L
            override fun close() {}
        }
        val s = FileSender(store)
        s.start(link, "eeeeeeeeeeeeeeee"); s.pump()
        assertThat(sent).isEqualTo(0)
        full = false; s.pump()
        assertThat(sent).isEqualTo(Wire.chunks(song.size.toLong()))
        assertThat(s.idle).isTrue()
    }
}
