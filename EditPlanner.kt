package com.vexora.ai

import kotlin.math.max
import kotlin.math.min

data class Cut(val srcStartMs: Long, val lenMs: Long, val zoom: Boolean)

/** Places cut points on music beats and picks the most interesting source windows. */
object EditPlanner {
    fun plan(video: VideoAnalyzer.Info, beats: BeatAnalyzer.Result?, look: Look): List<Cut> {
        val dur = video.durationMs
        val total = if (beats != null) min(beats.durationMs, look.targetMs ?: Long.MAX_VALUE)
        else look.targetMs ?: min(dur, 60_000L)

        val minLen = if (look.beatsPerCut == 1) 450L else 700L
        val bounds = mutableListOf(0L)
        if (beats != null) {
            beats.beatsMs.forEachIndexed { i, b ->
                if (i % look.beatsPerCut == 0 && b - bounds.last() >= minLen && b < total - minLen) bounds.add(b)
            }
        } else {
            val step = look.beatsPerCut * 800L
            var t = 0L
            while (t + step < total - minLen) { t += step; bounds.add(t) }
        }
        bounds.add(total)
        val lens = bounds.zipWithNext { a, b -> min(b - a, dur) }.filter { it > 100 }

        val step = video.stepMs
        val sc = video.scores
        fun winScore(s: Long, l: Long): Double {
            val a = (s / step).toInt().coerceIn(0, sc.size - 1)
            val b = ((s + l) / step).toInt().coerceIn(a, sc.size - 1)
            var sum = 0.0; for (i in a..b) sum += sc[i]
            return sum / (b - a + 1)
        }

        val used = mutableListOf<LongRange>()
        val starts = lens.map { l ->
            var best = 0L; var bestScore = -1.0
            var s = 0L
            while (s + l <= dur) {
                val overlap = used.sumOf { max(0L, min(it.last, s + l) - max(it.first, s)) }
                if (overlap < l * 0.4) {
                    val v = winScore(s, l)
                    if (v > bestScore) { bestScore = v; best = s }
                }
                s += step
            }
            if (bestScore < 0) best = ((used.size * 977L) % max(1L, dur - l + 1))  // footage exhausted: reuse
            used.add(best until best + l)
            best
        }.sorted()   // keep original chronological order of the footage

        return lens.mapIndexed { i, l ->
            Cut(min(starts[i], max(0L, dur - l)), l, look.zoom > 1f && i % 2 == 1)
        }
    }
}
