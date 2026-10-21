package com.vexora.ai

import android.content.Context
import org.json.JSONArray
import java.io.File

/** All app data lives in the app's private storage. No network, no accounts. */
class Store(ctx: Context) {
    val videosDir = File(ctx.filesDir, "videos").apply { mkdirs() }
    val musicDir = File(ctx.filesDir, "music").apply { mkdirs() }
    private val file = File(ctx.filesDir, "projects.json")

    fun load(): List<Project> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { Project.fromJson(arr.getJSONObject(it)) }
            .filter { File(it.path).exists() }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun save(list: List<Project>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun musicFiles(): List<File> = musicDir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
