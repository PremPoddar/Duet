package com.prempoddar.duet.play

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.prempoddar.duet.core.Playout
import com.prempoddar.duet.core.Slew
import kotlin.math.abs

/**
 * Plays decoded songs straight to Android's low-latency audio output and keeps them on the shared
 * clock, sample by sample. Every few chunks it asks the hardware which sample is coming out of the
 * speaker at which moment, compares that with where the song should be at that moment, and reads the
 * song a hair faster or slower until they match. Only a big gap (a jump, or starting) restarts it.
 */
class Engine {
    /** What should be playing: [pcm] at [positionMs] (song time) for any System.nanoTime(). */
    class Want(val pcm: Pcm, val playing: Boolean, val positionMs: (Long) -> Double)

    @Volatile var want: Want? = null
    /** How far the speaker is from the shared position, in ms (positive: behind), for the indicator. */
    @Volatile var driftMs = 0.0
        private set
    @Volatile var running = false
        private set

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "duet-engine").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
    }

    private var track: AudioTrack? = null
    private var trackPcm: Pcm? = null
    private val playout = Playout()
    private var written = 0L
    private var source = 0.0
    private var started = false
    /** Learned time from writing a sample to hearing it, used to aim a fresh start. */
    private var pipelineNs = 60_000_000L
    private val ts = AudioTimestamp()
    private val errors = ArrayDeque<Double>()
    private var lastCheck = 0L
    private var settleUntil = 0L
    /** Whether the first reading after a start has been used to check where it landed. */
    private var aimed = false

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                val w = want
                if (w == null || !w.playing) { halt(); Thread.sleep(20); continue }
                ensureTrack(w.pcm)
                val t = track ?: run { Thread.sleep(20); null } ?: continue
                if (!started) begin(w, t)
                check(w, t)
                writeChunk(w.pcm, t)
            }
        } finally {
            release()
        }
    }

    private fun ensureTrack(pcm: Pcm) {
        val t = track
        if (t != null && trackPcm?.rate == pcm.rate && trackPcm?.channels == pcm.channels) {
            if (trackPcm?.id != pcm.id) started = false // a new song starts from its own place
            trackPcm = pcm; return
        }
        release()
        val mask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(pcm.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(pcm.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, CHUNK * pcm.channels * 2 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trackPcm = pcm
        started = false
    }

    /** Starts writing from where the song will be by the time this sample is heard. */
    private fun begin(w: Want, t: AudioTrack) {
        t.pause(); t.flush()
        playout.reset(); errors.clear()
        written = 0
        source = w.positionMs(System.nanoTime() + pipelineNs) * w.pcm.rate / 1000.0
        t.play()
        started = true
        settleUntil = System.nanoTime() + SETTLE_NS
        aimed = false
    }

    private fun check(w: Want, t: AudioTrack) {
        val now = System.nanoTime()
        if (now - lastCheck < CHECK_NS || now < settleUntil) return
        lastCheck = now
        if (!t.getTimestamp(ts) || ts.framePosition <= 0) return
        val heard = playout.sourceAt(ts.framePosition) ?: return
        val should = w.positionMs(ts.nanoTime) * w.pcm.rate / 1000.0
        val err = heard - should
        if (!aimed) {
            aimed = true
            // The start landed off by however much more or less than expected the output delays:
            // learn that and start again, so later starts (and this one) land on time.
            if (abs(err) > w.pcm.rate * AIM_WITHIN_S) {
                pipelineNs = (pipelineNs - err * 1e9 / w.pcm.rate).toLong().coerceIn(0, MAX_PIPELINE_NS)
                started = false
                return
            }
        }
        errors.addLast(err); while (errors.size > 5) errors.removeFirst()
        val median = errors.sorted()[errors.size / 2]
        driftMs = -median * 1000.0 / w.pcm.rate
        // Someone jumped, or this phone stalled: start again from the right place.
        if (Slew.shouldJump(median, w.pcm.rate)) started = false
    }

    private val buffer = ShortArray(CHUNK * 2)

    private fun writeChunk(pcm: Pcm, t: AudioTrack) {
        if (!started) return
        val median = if (errors.isEmpty()) 0.0 else errors.sorted()[errors.size / 2]
        val step = Slew.step(median, pcm.rate)
        // Each output sample is read between two of the song's, so a changed pace never clicks.
        for (i in 0 until CHUNK) {
            val s = source + i * step
            val f = s.toLong(); val frac = s - f
            for (c in 0 until pcm.channels) {
                val a = pcm.at(f, c); val b = pcm.at(f + 1, c)
                buffer[i * pcm.channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        playout.wrote(written, source, step)
        val n = t.write(buffer, 0, CHUNK * pcm.channels, AudioTrack.WRITE_BLOCKING)
        if (n > 0) { written += n / pcm.channels; source += (n / pcm.channels) * step }
    }

    private fun halt() {
        track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) { it.pause(); it.flush() } }
        started = false; errors.clear(); driftMs = 0.0
    }

    private fun release() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null; trackPcm = null; started = false
    }

    companion object {
        /** 10 ms at 48 kHz: small enough to correct smoothly, big enough to keep the output fed. */
        private const val CHUNK = 480
        private const val CHECK_NS = 50_000_000L
        /** Timestamps are unreliable for the first moments after starting. */
        private const val SETTLE_NS = 250_000_000L
        /** A start this close to the target is eased in rather than redone. */
        private const val AIM_WITHIN_S = 0.01
        private const val MAX_PIPELINE_NS = 500_000_000L
    }
}
