package com.vexora.ai

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Overlay { NONE, MUSIC, TEMPLATES }

sealed class Status {
    object Idle : Status()
    data class Working(val msg: String, val progress: Float?) : Status()
    data class Done(val project: Project) : Status()
    data class Error(val msg: String) : Status()
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    private val renderer = Renderer(app)

    var tab by mutableStateOf(0)
    var overlay by mutableStateOf(Overlay.NONE)
    var videoUri by mutableStateOf<Uri?>(null)
    var videoName by mutableStateOf("")
    var music by mutableStateOf<File?>(null)
    var prompt by mutableStateOf("")
    var style by mutableStateOf(EditStyle.CINEMATIC)
    var aspect by mutableStateOf(Aspect.PORTRAIT)
    var resolution by mutableStateOf(prefs.getInt("res", 1080))
    var status by mutableStateOf<Status>(Status.Idle)
    var projects by mutableStateOf(store.load())
    var musicFiles by mutableStateOf(store.musicFiles())
    var message by mutableStateOf<String?>(null)

    var theme by mutableStateOf(prefs.getString("theme", "system")!!)
    var lang by mutableStateOf(prefs.getString("lang", "en")!!)
    var exportRes by mutableStateOf(prefs.getInt("res", 1080))
    var location by mutableStateOf(prefs.getString("loc", "gallery")!!)
    var folderUri by mutableStateOf(prefs.getString("folder", null))

    fun setTheme(v: String) { theme = v; prefs.edit().putString("theme", v).apply() }
    fun setLang(v: String) { lang = v; prefs.edit().putString("lang", v).apply() }
    fun setExportRes(v: Int) { exportRes = v; resolution = v; prefs.edit().putInt("res", v).apply() }
    fun setLocation(v: String) { location = v; prefs.edit().putString("loc", v).apply() }
    fun setFolder(u: Uri) { folderUri = u.toString(); location = "folder"
        prefs.edit().putString("folder", u.toString()).putString("loc", "folder").apply() }

    private fun displayName(uri: Uri, fallback: String): String =
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: fallback

    fun pickVideo(uri: Uri) { videoUri = uri; videoName = displayName(uri, "video"); status = Status.Idle }

    fun importMusic(uri: Uri) = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(uri, "track_${System.currentTimeMillis()}").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dest = File(store.musicDir, name)
                app.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
                dest
            }.getOrNull()
        }
        if (f != null) { music = f; musicFiles = store.musicFiles() } else message = "Could not import that audio file"
    }

    fun deleteMusic(f: File) { f.delete(); if (music == f) music = null; musicFiles = store.musicFiles() }

    fun applyTemplate(t: Template) { style = t.style; aspect = t.aspect; prompt = t.prompt; overlay = Overlay.NONE; tab = 2 }

    fun generate() {
        val v = videoUri ?: return
        if (status is Status.Working) return
        viewModelScope.launch {
            try {
                val look = PromptRules.build(style, prompt)
                val m = music
                status = Status.Working("Analyzing music (beats & tempo)…", null)
                val beats = m?.let { withContext(Dispatchers.Default) { BeatAnalyzer.analyze(it) } }
                status = Status.Working("Analyzing video (motion & contrast)…", 0f)
                val info = withContext(Dispatchers.Default) {
                    VideoAnalyzer.analyze(app, v) { p -> status = Status.Working("Analyzing video (motion & contrast)…", p) }
                }
                status = Status.Working("Planning beat-synced cuts…", null)
                val cuts = withContext(Dispatchers.Default) { EditPlanner.plan(info, beats, look) }
                require(cuts.isNotEmpty()) { "Nothing to render" }
                val (w, h) = aspect.size(resolution)
                val out = File(store.videosDir, "vexora_${System.currentTimeMillis()}.mp4")
                status = Status.Working("Rendering on device…", 0f)
                renderer.render(v, m, cuts, look, w, h, out,
                    onProgress = { status = Status.Working("Rendering on device…", it) },
                    onDone = { r ->
                        r.onSuccess {
                            val p = Project(
                                id = out.nameWithoutExtension, title = videoName.substringBeforeLast('.'),
                                path = out.absolutePath, prompt = prompt, style = style.label, aspect = aspect.label,
                                resolution = resolution, music = m?.nameWithoutExtension ?: "",
                                durationMs = cuts.sumOf { it.lenMs }, createdAt = System.currentTimeMillis()
                            )
                            projects = listOf(p) + projects; store.save(projects); status = Status.Done(p)
                        }.onFailure { status = Status.Error(it.message ?: "Render failed") }
                    })
            } catch (e: Exception) {
                status = Status.Error(e.message ?: "Something went wrong")
            }
        }
    }

    fun cancel() { renderer.cancel(); status = Status.Idle }

    fun delete(p: Project) {
        File(p.path).delete(); projects = projects.filter { it.id != p.id }; store.save(projects)
        if ((status as? Status.Done)?.project?.id == p.id) status = Status.Idle
    }

    /** Copies the finished video to the default export location from Settings. */
    fun saveCopy(p: Project) = viewModelScope.launch {
        message = withContext(Dispatchers.IO) {
            runCatching {
                val name = "${p.id}.mp4"
                val src = File(p.path)
                val folder = folderUri
                if (location == "app") return@runCatching "Kept in app storage"
                if (location == "folder" && folder != null) {
                    val tree = Uri.parse(folder)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val doc = DocumentsContract.createDocument(app.contentResolver, parent, "video/mp4", name)!!
                    app.contentResolver.openOutputStream(doc)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                    "Saved to your chosen folder"
                } else {
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VEXORA AI")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    app.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                    cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                    app.contentResolver.update(uri, cv, null, null)
                    "Saved to Movies/VEXORA AI"
                }
            }.getOrElse { "Save failed: ${it.message}" }
        }
    }

    fun clearMusic() { store.musicDir.listFiles()?.forEach { it.delete() }; music = null; musicFiles = store.musicFiles() }
}
