package com.prempoddar.duet.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

class LyricsTest {
    @Test fun readsTimedLines() {
        val lines = Lyrics.parse("[ar:Someone]\n[00:12.50]First line\n[00:15.2]Second\n[01:02.345][01:30.00]Chorus\n")
        assertThat(lines.map { it.atMs }).containsExactly(12_500L, 15_200L, 62_345L, 90_000L).inOrder()
        assertThat(lines.last().text).isEqualTo("Chorus")
        assertThat(Lyrics.current(lines, 0)).isEqualTo(-1)
        assertThat(Lyrics.current(lines, 15_200)).isEqualTo(1)
        assertThat(Lyrics.current(lines, 70_000)).isEqualTo(2)
    }

    @Test fun plainLyricsHaveNoCurrentLine() {
        val lines = Lyrics.parse("\nHello\nIs it me\n\n")
        assertThat(lines.map { it.text }).containsExactly("Hello", "Is it me").inOrder()
        assertThat(Lyrics.current(lines, 5_000)).isEqualTo(-1)
    }

    @Test fun findsLyricsInAnMp3Tag() {
        // An ID3v2.3 tag with a TIT2 frame and then a USLT frame in UTF-8.
        fun frame(id: String, body: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        val uslt = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "[00:01.00]Hi there".toByteArray()
        val frames = frame("TIT2", byteArrayOf(3) + "Song".toByteArray()) + frame("USLT", uslt)
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray()); out.write(byteArrayOf(3, 0, 0, 0, 0, 0, frames.size.toByte())); out.write(frames); out.write(ByteArray(20))
        assertThat(Lyrics.fromId3(out.toByteArray())).isEqualTo("[00:01.00]Hi there")
        assertThat(Lyrics.fromId3("not a tag".toByteArray())).isNull()
    }
}
