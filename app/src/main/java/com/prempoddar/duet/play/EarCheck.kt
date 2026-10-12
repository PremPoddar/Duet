package com.prempoddar.duet.play

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import com.prempoddar.duet.core.EarSync
import com.prempoddar.duet.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** Plays ticks to the sample at given moments, using the audio system's own timestamps. */
object TickPlayer {
    /** [at] in this phone's clock (elapsedRealtime ms); [lead] ms earlier, to match the music's nudge. */
    fun play(at: List<Long>, hz: Int, lead: Long = 0) {
        val now = SystemClock.elapsedRealtime()
        val times = at.map { it - lead }.filter { it in now..now + 10_000 }.take(EarSync.TICKS * 2)
        if (times.isEmpty()) return
        thread(name = "duet-ticks") { runCatching { run(times, EarSync.tick(hz)) } }
    }

    private fun run(at: List<Long>, tick: ShortArray) {
        val rate = EarSync.RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        val chunk = rate / 100
        val buf = ShortArray(chunk)
        val ts = AudioTimestamp()
        // The audio clock is the monotonic clock; ours counts deep sleep too.
        val bootNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        val frames = LongArray(at.size) { -1 }
        var written = 0L
        val end = at.max() + 500
        track.play()
        try {
            while (SystemClock.elapsedRealtime() < end) {
                if (track.getTimestamp(ts) && ts.framePosition > 0) {
                    // Placed afresh from each newer timestamp until we start writing that tick.
                    at.forEachIndexed { n, t ->
                        if (frames[n] < 0 || frames[n] >= written + chunk) {
                            val f = ts.framePosition + (t * 1_000_000 - bootNs - ts.nanoTime) * rate / 1_000_000_000
                            frames[n] = if (f >= written) f else -2 // too late to play on time: skip it
                        }
                    }
                }
                buf.fill(0)
                for (f in frames) if (f >= 0) for (i in 0 until chunk) {
                    val j = written + i - f
                    if (j in tick.indices) buf[i] = tick[j.toInt()]
                }
                track.write(buf, 0, chunk)
                written += chunk
            }
        } finally {
            track.stop(); track.release()
        }
    }
}

/**
 * The guest's side of matching by ear: asks the host for its ticks, plays its own, records both
 * and works out how much later this phone sounds. Needs the microphone permission.
 */
object EarCheck {
    @SuppressLint("MissingPermission")
    suspend fun measure(session: Session, myLead: Long): Long? = withContext(Dispatchers.IO) {
        val toLocal = SystemClock.elapsedRealtime() - session.hostNow()
        val hostTimes = EarSync.hostTicks(session.hostNow() + 1_200)
        session.requestTicks(hostTimes)
        TickPlayer.play(hostTimes.map { it + EarSync.GAP_MS + toLocal }, EarSync.GUEST_HZ, myLead)
        val until = hostTimes.last() + EarSync.GAP_MS + toLocal + 600
        val rate = EarSync.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // The raw microphone where there is one: no noise removal eating the ticks.
        val record = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching { AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5)) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } ?: return@withContext null
        val out = ShortArray(rate * 7)
        var n = 0
        try {
            record.startRecording()
            while (SystemClock.elapsedRealtime() < until && n < out.size) {
                val r = record.read(out, n, minOf(rate / 50, out.size - n))
                if (r <= 0) break
                n += r
            }
        } finally {
            runCatching { record.stop() }; record.release()
        }
        EarSync.measure(out.copyOf(n))
    }
}
