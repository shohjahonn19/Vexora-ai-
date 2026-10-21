package com.vexora.ai

import org.json.JSONObject

/** Parameters that describe how an edit looks and how fast it cuts. */
data class Look(
    val beatsPerCut: Int,
    val zoom: Float,          // punch-in scale applied to alternate cuts (1 = off)
    val contrast: Float,      // -1..1
    val brightness: Float,    // -1..1
    val saturation: Float,    // -100..100
    val red: Float,
    val blue: Float,
    val targetMs: Long? = null
)

enum class EditStyle(val label: String, val look: Look) {
    CINEMATIC("Cinematic", Look(4, 1.06f, 0.20f, -0.02f, -10f, 1.05f, 0.95f)),
    ENERGETIC("Energetic", Look(1, 1.12f, 0.15f, 0.03f, 25f, 1f, 1f)),
    VINTAGE("Vintage", Look(2, 1f, 0.10f, 0.04f, -35f, 1.10f, 0.85f)),
    VIBRANT("Vibrant", Look(2, 1.08f, 0.10f, 0.02f, 45f, 1f, 1f)),
    NOIR("Black & White", Look(2, 1.05f, 0.30f, -0.02f, -100f, 1f, 1f)),
    CLEAN("Clean", Look(4, 1f, 0f, 0f, 0f, 1f, 1f))
}

enum class Aspect(val label: String, val w: Int, val h: Int) {
    PORTRAIT("9:16", 9, 16), LANDSCAPE("16:9", 16, 9), SQUARE("1:1", 1, 1), FOUR_FIVE("4:5", 4, 5);

    /** Output size where [shortSide] is the smaller dimension; always even numbers. */
    fun size(shortSide: Int): Pair<Int, Int> {
        val (ow, oh) = when {
            w < h -> shortSide to shortSide * h / w
            w > h -> shortSide * w / h to shortSide
            else -> shortSide to shortSide
        }
        return (ow / 2) * 2 to (oh / 2) * 2
    }
}

data class Project(
    val id: String,
    val title: String,
    val path: String,
    val prompt: String,
    val style: String,
    val aspect: String,
    val resolution: Int,
    val music: String,
    val durationMs: Long,
    val createdAt: Long
) {
    fun toJson() = JSONObject().put("id", id).put("title", title).put("path", path)
        .put("prompt", prompt).put("style", style).put("aspect", aspect)
        .put("resolution", resolution).put("music", music)
        .put("durationMs", durationMs).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Project(
            o.getString("id"), o.getString("title"), o.getString("path"),
            o.optString("prompt"), o.optString("style"), o.optString("aspect"),
            o.optInt("resolution", 1080), o.optString("music"),
            o.optLong("durationMs"), o.optLong("createdAt")
        )
    }
}

/** Keyword-driven (rule-based, not a language model) interpretation of the editing prompt. */
object PromptRules {
    fun build(style: EditStyle, prompt: String): Look {
        val p = prompt.lowercase()
        var l = style.look
        fun has(vararg k: String) = k.any { p.contains(it) }
        if (has("fast", "quick", "energetic", "hype", "punchy")) l = l.copy(beatsPerCut = 1)
        else if (has("slow", "calm", "smooth", "relax")) l = l.copy(beatsPerCut = 4)
        if (has("warm", "golden", "sunset")) l = l.copy(red = 1.10f, blue = 0.90f)
        if (has("cool", "cold", "icy")) l = l.copy(red = 0.92f, blue = 1.10f)
        if (has("dark", "moody")) l = l.copy(brightness = l.brightness - 0.06f, contrast = l.contrast + 0.1f)
        if (has("bright", "airy")) l = l.copy(brightness = l.brightness + 0.06f)
        if (has("black and white", "b&w", "monochrome", "noir")) l = l.copy(saturation = -100f)
        if (has("vivid", "colorful", "colourful", "vibrant")) l = l.copy(saturation = maxOf(l.saturation, 45f))
        if (has("no zoom")) l = l.copy(zoom = 1f)
        else if (has("zoom", "punch")) l = l.copy(zoom = maxOf(l.zoom, 1.12f))
        Regex("(\\d{1,3})\\s*(s|sec|secs|seconds)\\b").find(p)?.let {
            l = l.copy(targetMs = it.groupValues[1].toLong().coerceIn(3, 180) * 1000)
        }
        return l
    }
}
