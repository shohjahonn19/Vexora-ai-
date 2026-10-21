package com.vexora.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.sqrt

/** Samples frames locally and scores each time step by motion and visual contrast. */
object VideoAnalyzer {
    class Info(val durationMs: Long, val width: Int, val height: Int, val scores: DoubleArray, val stepMs: Long)

    fun analyze(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            require(dur > 500) { "Could not read the video" }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val step = if (dur > 600_000) dur / 1200 else 500L
            val n = (dur / step).toInt() + 1
            val scores = DoubleArray(n)
            var prev: FloatArray? = null
            val px = IntArray(32 * 32)
            for (i in 0 until n) {
                val bmp = r.getScaledFrameAtTime(i * step * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32)
                if (bmp != null) {
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val cnt = bmp.width * bmp.height
                    val luma = FloatArray(cnt) { k ->
                        val c = px[k]
                        (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
                    }
                    val mean = luma.average()
                    var v = 0.0; for (l in luma) v += (l - mean) * (l - mean)
                    val std = sqrt(v / cnt)
                    var diff = 0.0
                    prev?.let { p -> for (k in 0 until cnt) diff += abs(luma[k] - p[k]); diff /= cnt }
                    var s = diff * 4.0 + std
                    if (mean < 0.05) s *= 0.2           // nearly black frames are poor picks
                    scores[i] = s
                    prev = luma
                    if (bmp.width > 0) bmp.recycle()
                }
                onProgress((i + 1f) / n)
            }
            return Info(dur, w, h, scores, step)
        } finally {
            r.release()
        }
    }
}
