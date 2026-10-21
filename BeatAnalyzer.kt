package com.vexora.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Real local audio analysis: decodes the track to PCM with MediaCodec, builds an onset-strength
 * envelope (low band + high band energy flux), estimates tempo by autocorrelation and places
 * beats on the best-matching phase, snapping each one to the nearest onset peak.
 */
object BeatAnalyzer {
    data class Result(val beatsMs: List<Long>, val bpm: Double, val durationMs: Long)

    private const val HOP_SEC = 0.0116

    fun analyze(file: File, maxMs: Long = 240_000): Result {
        val onset = onsetEnvelope(file, maxMs)
        require(onset.size > 200) { "Music track is too short to analyze" }
        val o = onset

        fun at(x: Double): Float {
            val i = x.toInt()
            if (i < 0 || i + 1 >= o.size) return 0f
            val f = (x - i).toFloat()
            return o[i] * (1 - f) + o[i + 1] * f
        }
        fun acScore(lag: Double): Double {
            var s = 0.0; var t = 0
            while (t + lag < o.size - 1) { s += o[t] * at(t + lag); t++ }
            return s / (o.size - lag)
        }

        val minLag = 60.0 / 180 / HOP_SEC
        val maxLag = 60.0 / 70 / HOP_SEC
        var bestLag = minLag; var best = -1.0
        var lag = minLag
        while (lag <= maxLag) {
            val bpm = 60.0 / (lag * HOP_SEC)
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.7).let { it * it })
            val s = acScore(lag) * prior
            if (s > best) { best = s; bestLag = lag }
            lag += 0.25
        }
        // fine refinement (fractional lag) to limit drift across a whole song
        var fine = bestLag; var fineBest = -1.0
        var l2 = bestLag - 0.5
        while (l2 <= bestLag + 0.5) {
            val s = acScore(l2); if (s > fineBest) { fineBest = s; fine = l2 }; l2 += 0.05
        }

        var bestPhase = 0.0; var phaseBest = -1.0
        var p = 0.0
        while (p < fine) {
            var s = 0.0; var x = p
            while (x < o.size - 1) { s += at(x); x += fine }
            if (s > phaseBest) { phaseBest = s; bestPhase = p }
            p += 1.0
        }

        val beats = ArrayList<Long>()
        var x = bestPhase
        val win = (fine * 0.2).toInt().coerceAtLeast(1)
        while (x < o.size - 1) {
            val c = x.toInt()
            var bi = c; var bv = -1f
            for (i in max(0, c - win)..min(o.size - 1, c + win)) if (o[i] > bv) { bv = o[i]; bi = i }
            beats.add((bi * HOP_SEC * 1000).toLong())
            x += fine
        }
        return Result(beats.distinct().sorted(), 60.0 / (fine * HOP_SEC), (o.size * HOP_SEC * 1000).toLong())
    }

    private fun onsetEnvelope(file: File, maxMs: Long): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var idx = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
        }
        require(idx >= 0 && fmt != null) { "No audio track found in the music file" }
        ex.selectTrack(idx)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()

        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        val raw = ArrayList<Float>()
        var inDone = false; var outDone = false
        var y = 0f; var sl = 0f; var sh = 0f; var n = 0
        var prevL = 0.0; var prevH = 0.0
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0 || ex.sampleTime > maxMs * 1000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val hop = (sr * HOP_SEC).toInt().coerceAtLeast(1)
                    while (sb.remaining() >= ch) {
                        var m = 0f
                        for (c in 0 until ch) m += sb.get()
                        m /= (ch * 32768f)
                        y += 0.08f * (m - y)
                        val hi = m - y
                        sl += y * y; sh += hi * hi; n++
                        if (n >= hop) {
                            val ll = ln(1.0 + 1e4 * (sl / n)); val lh = ln(1.0 + 1e4 * (sh / n))
                            raw.add((max(0.0, ll - prevL) + max(0.0, lh - prevH)).toFloat())
                            prevL = ll; prevH = lh; sl = 0f; sh = 0f; n = 0
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        // remove slow-moving mean so only onsets remain
        val w = 43
        val out = FloatArray(raw.size)
        var run = 0.0
        for (i in raw.indices) {
            run += raw[i]
            if (i >= w) run -= raw[i - w]
            val mean = run / min(i + 1, w)
            out[i] = max(0f, raw[i] - mean.toFloat())
        }
        return out
    }
}
