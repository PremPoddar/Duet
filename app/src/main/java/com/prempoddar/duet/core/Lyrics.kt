package com.prempoddar.duet.core

/**
 * Song lyrics, from a .lrc file beside the song or the song's own tags. Timed lines
 * ("[01:23.45]words") follow the music; plain lyrics are shown as they are.
 */
object Lyrics {
    data class Line(val atMs: Long?, val text: String)

    private val STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Lines in order; timed if the text has timestamps, otherwise one untimed line each. */
    fun parse(text: String): List<Line> {
        val timed = ArrayList<Line>()
        val plain = ArrayList<Line>()
        text.lineSequence().forEach { raw ->
            val stamps = STAMP.findAll(raw).toList()
            val words = raw.replace(STAMP, "").trim()
            if (stamps.isEmpty()) {
                // Header tags such as [ar:Artist] carry no words to show.
                if (!raw.trim().matches(Regex("""\[[a-z]+:.*]"""))) plain += Line(null, words)
            } else stamps.forEach { m ->
                val (min, sec, frac) = m.destructured
                val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                timed += Line(min.toLong() * 60_000 + sec.toLong() * 1_000 + ms, words)
            }
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.atMs } else plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
    }

    /** The line being sung at [positionMs], or -1 before the first; untimed lyrics have none. */
    fun current(lines: List<Line>, positionMs: Long): Int {
        if (lines.firstOrNull()?.atMs == null) return -1
        return lines.indexOfLast { (it.atMs ?: Long.MAX_VALUE) <= positionMs }
    }

    /**
     * Lyrics stored in an MP3's ID3v2 tag (the USLT frame), or null. Only the start of the file is
     * needed: the tag comes first.
     */
    fun fromId3(head: ByteArray): String? {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val version = head[3].toInt()
        val size = syncsafe(head, 6)
        var at = 10
        val end = minOf(head.size, 10 + size)
        while (at + 10 <= end) {
            val id = String(head, at, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val len = if (version >= 4) syncsafe(head, at + 4) else int32(head, at + 4)
            if (len <= 0 || at + 10 + len > end) break
            if (id == "USLT") return uslt(head.copyOfRange(at + 10, at + 10 + len))
            at += 10 + len
        }
        return null
    }

    // USLT: encoding (1), language (3), description (terminated), then the lyrics.
    private fun uslt(f: ByteArray): String? {
        if (f.size < 5) return null
        val enc = f[0].toInt()
        val wide = enc == 1 || enc == 2
        var i = 4
        if (wide) { while (i + 1 < f.size && !(f[i] == 0.toByte() && f[i + 1] == 0.toByte())) i += 2; i += 2 }
        else { while (i < f.size && f[i] != 0.toByte()) i++; i++ }
        if (i > f.size) return null
        val body = f.copyOfRange(i, f.size)
        val charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(body, charset).trim('\u0000', ' ', '\n').takeIf { it.isNotBlank() }
    }

    private fun syncsafe(b: ByteArray, i: Int) = (b[i].toInt() and 0x7f shl 21) or (b[i + 1].toInt() and 0x7f shl 14) or (b[i + 2].toInt() and 0x7f shl 7) or (b[i + 3].toInt() and 0x7f)
    private fun int32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff shl 24) or (b[i + 1].toInt() and 0xff shl 16) or (b[i + 2].toInt() and 0xff shl 8) or (b[i + 3].toInt() and 0xff)

    /** Lyrics sent with a song are capped so the queue stays small to send. */
    const val MAX_CHARS = 20_000
}
