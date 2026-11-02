package com.prempoddar.duet.play

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** A song decoded to 16-bit samples, read straight from a file on disk. */
class Pcm(val id: String, val rate: Int, val channels: Int, val samples: ShortBuffer) {
    val frames: Long get() = (samples.capacity() / channels).toLong()

    /** Sample [c] of frame [f], or silence outside the song. */
    fun at(f: Long, c: Int): Int {
        if (f < 0 || f >= frames) return 0
        return samples.get((f * channels + c).toInt()).toInt()
    }

    companion object {
        /**
         * Decodes [song] into [out] (raw samples with a small header), unless it's already there.
         * Takes a second or two for a song; runs off the main thread.
         */
        fun decode(id: String, song: File, out: File): Pcm {
            if (!out.isFile) {
                val tmp = File(out.path + ".part")
                val (rate, ch) = decodeTo(song, tmp)
                // Rate and channels go first, so the file can be opened again later on its own.
                RandomAccessFile(tmp, "rw").use { f -> f.seek(0); f.writeInt(rate); f.writeInt(ch) }
                tmp.renameTo(out)
            }
            return open(id, out)
        }

        private fun open(id: String, file: File): Pcm {
            val f = RandomAccessFile(file, "r")
            val rate = f.readInt(); val ch = f.readInt()
            val map = f.channel.map(FileChannel.MapMode.READ_ONLY, HEADER.toLong(), file.length() - HEADER)
            f.close() // the mapping stays valid
            return Pcm(id, rate, ch, map.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
        }

        private fun decodeTo(song: File, out: File): Pair<Int, Int> {
            val ex = MediaExtractor()
            ex.setDataSource(song.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { o ->
                o.write(ByteArray(HEADER))
                var inputDone = false
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o2 = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o2 == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                            rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o2 >= 0 -> {
                            if (info.size > 0) {
                                val b = codec.getOutputBuffer(o2)!!
                                val bytes = ByteArray(info.size); b.position(info.offset); b.get(bytes)
                                o.write(bytes)
                            }
                            codec.releaseOutputBuffer(o2, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
            codec.stop(); codec.release(); ex.release()
            return rate to channels
        }

        private const val HEADER = 8
    }
}
