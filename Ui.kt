@file:OptIn(ExperimentalMaterial3Api::class)

package com.vexora.ai

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Template(val name: String, val desc: String, val style: EditStyle, val aspect: Aspect, val prompt: String)

val TEMPLATES = listOf(
    Template("Social Reel", "Fast, bright, vertical", EditStyle.VIBRANT, Aspect.PORTRAIT, "fast bright vibrant zoom"),
    Template("Party Hype", "Beat-per-cut energy", EditStyle.ENERGETIC, Aspect.PORTRAIT, "fast punchy zoom"),
    Template("Travel Film", "Slow, warm, widescreen", EditStyle.CINEMATIC, Aspect.LANDSCAPE, "slow cinematic warm"),
    Template("Retro Memories", "Faded vintage look", EditStyle.VINTAGE, Aspect.FOUR_FIVE, "vintage warm slow"),
    Template("Noir Short", "Black & white, moody", EditStyle.NOIR, Aspect.LANDSCAPE, "black and white moody"),
    Template("Clean Showcase", "Neutral and calm", EditStyle.CLEAN, Aspect.SQUARE, "smooth clean")
)

@Composable
fun VexoraApp(vm: AppViewModel) {
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.status is Status.Working }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    BackHandler(vm.overlay != Overlay.NONE) { vm.overlay = Overlay.NONE }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(vm.tab == 0, { vm.tab = 0; vm.overlay = Overlay.NONE },
                    { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.nav_home)) })
                NavigationBarItem(vm.tab == 1, { vm.tab = 1; vm.overlay = Overlay.NONE },
                    { Icon(Icons.Default.PlayArrow, null) }, label = { Text(stringResource(R.string.nav_edits)) })
                NavigationBarItem(vm.tab == 2, { vm.tab = 2; vm.overlay = Overlay.NONE },
                    { Icon(Icons.Default.Add, null) }, label = { Text(stringResource(R.string.nav_create)) })
                NavigationBarItem(vm.tab == 3, { vm.tab = 3; vm.overlay = Overlay.NONE },
                    { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                vm.overlay == Overlay.MUSIC -> MusicScreen(vm)
                vm.overlay == Overlay.TEMPLATES -> TemplatesScreen(vm)
                vm.tab == 0 -> HomeScreen(vm)
                vm.tab == 1 -> MyEditsScreen(vm)
                vm.tab == 2 -> CreateScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text(
            "VEXORA AI", fontSize = 38.sp, fontWeight = FontWeight.Black,
            style = LocalTextStyleWithBrush()
        )
        Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button({ vm.tab = 2 }, Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp)) {
            Text(stringResource(R.string.btn_new), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        HomeCard(stringResource(R.string.explore_music), "Your local music library") { vm.overlay = Overlay.MUSIC }
        HomeCard(stringResource(R.string.templates), "Ready-made styles and formats") { vm.overlay = Overlay.TEMPLATES }
        HomeCard(stringResource(R.string.creations), "${vm.projects.size} saved on this device") { vm.tab = 1 }
    }
}

@Composable
private fun LocalTextStyleWithBrush() = androidx.compose.ui.text.TextStyle(
    brush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE)))
)

@Composable
fun HomeCard(title: String, sub: String, onClick: () -> Unit) {
    Card(onClick, Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

@Composable
fun MusicScreen(vm: AppViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importMusic(it) } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Music library", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledIconButton({ pick.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.Add, "Import music") }
        }
        Text("Tracks are copied into the app and never leave your device.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f))
        Spacer(Modifier.height(12.dp))
        if (vm.musicFiles.isEmpty()) Text("No music yet. Tap + to import a track from your phone.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.musicFiles) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(if (vm.music == f) "Selected for next edit" else "%.1f MB".format(f.length() / 1e6), fontSize = 12.sp)
                        }
                        TextButton({ vm.music = f; vm.overlay = Overlay.NONE; vm.tab = 2 }) { Text("Use") }
                        IconButton({ vm.deleteMusic(f) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun TemplatesScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Templates", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TEMPLATES) { t ->
                Card({ vm.applyTemplate(t) }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t.name, fontWeight = FontWeight.Bold)
                        Text("${t.desc} · ${t.aspect.label} · ${t.style.label}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(it == selected, { onSelect(it) }, { Text(label(it)) }) }
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
fun CreateScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { vm.pickVideo(it) } }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importMusic(it) } }
    val working = vm.status is Status.Working
    var playing by remember { mutableStateOf<Project?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.nav_create), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SectionTitle("1 · Video")
        OutlinedButton({ pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            Modifier.fillMaxWidth(), enabled = !working) {
            Text(if (vm.videoUri == null) "Import video from phone" else vm.videoName, maxLines = 1)
        }

        SectionTitle("2 · Music")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.music?.nameWithoutExtension ?: "No music (keeps the video's own audio)",
                Modifier.weight(1f), maxLines = 1)
            if (vm.music != null) TextButton({ vm.music = null }, enabled = !working) { Text("Clear") }
            TextButton({ vm.overlay = Overlay.MUSIC }, enabled = !working) { Text("Library") }
            FilledIconButton({ pickMusic.launch(arrayOf("audio/*")) }, enabled = !working) { Icon(Icons.Default.Add, "Import music") }
        }

        SectionTitle("3 · Editing prompt")
        OutlinedTextField(vm.prompt, { vm.prompt = it }, Modifier.fillMaxWidth(), enabled = !working,
            placeholder = { Text("e.g. fast, warm, zoom, 20 seconds") }, minLines = 2)
        Text("Keywords control cut speed, color, zoom and length. Matching is done locally by rules.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f))

        SectionTitle("4 · Style")
        ChipRow(EditStyle.values().toList(), vm.style, { it.label }) { vm.style = it }
        SectionTitle("Aspect ratio")
        ChipRow(Aspect.values().toList(), vm.aspect, { it.label }) { vm.aspect = it }
        SectionTitle("Resolution")
        ChipRow(listOf(720, 1080), vm.resolution, { "${it}p" }) { vm.resolution = it }

        Spacer(Modifier.height(20.dp))
        Button({ vm.generate() }, Modifier.fillMaxWidth().height(56.dp), enabled = vm.videoUri != null && !working,
            shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.generate), fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(16.dp))
        when (val s = vm.status) {
            is Status.Working -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.msg)
                    Spacer(Modifier.height(8.dp))
                    if (s.progress != null) LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton({ vm.cancel() }) { Text("Cancel") }
                }
            }
            is Status.Error -> Text(s.msg, color = MaterialTheme.colorScheme.error)
            is Status.Done -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your video is ready", fontWeight = FontWeight.Bold)
                    Text("%d s · %s · %dp".format(s.project.durationMs / 1000, s.project.aspect, s.project.resolution), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ playing = s.project }) { Text("Play") }
                        OutlinedButton({ vm.saveCopy(s.project) }) { Text("Save") }
                        OutlinedButton({ share(ctx, s.project) }) { Text("Share") }
                    }
                }
            }
            else -> {}
        }
    }
    playing?.let { PlayerDialog(it.path) { playing = null } }
}

@Composable
fun MyEditsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<Project?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.nav_edits), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (vm.projects.isEmpty()) Text("No edits yet. Tap Create (+) to make your first video.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp)) {
                        Thumb(p.path, Modifier.size(84.dp, 112.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${p.style} · ${p.aspect} · ${p.resolution}p · ${p.durationMs / 1000}s", fontSize = 12.sp)
                            Text(fmt.format(Date(p.createdAt)), fontSize = 12.sp)
                            if (p.prompt.isNotBlank()) Text("“${p.prompt}”", fontSize = 12.sp, maxLines = 1)
                            Row {
                                IconButton({ playing = p }) { Icon(Icons.Default.PlayArrow, "Play") }
                                IconButton({ share(ctx, p) }) { Icon(Icons.Default.Share, "Share") }
                                TextButton({ vm.saveCopy(p) }) { Text("Save") }
                                IconButton({ vm.delete(p) }) { Icon(Icons.Default.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    playing?.let { PlayerDialog(it.path) { playing = null } }
}

@Composable
fun Thumb(path: String, modifier: Modifier) {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever(); r.setDataSource(path)
                val b: Bitmap? = r.getFrameAtTime(500_000); r.release(); b?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF222233))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun PlayerDialog(path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ PlayerView(it).apply { player = exo } }, Modifier.fillMaxSize())
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
    }
}

fun share(ctx: android.content.Context, p: Project) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(p.path))
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Share video"))
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        u?.let {
            ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.setFolder(it)
        }
    }
    val videoMb = remember(vm.projects) { vm.store.sizeOf(vm.store.videosDir) / 1e6 }
    val musicMb = remember(vm.musicFiles) { vm.store.sizeOf(vm.store.musicDir) / 1e6 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.nav_settings), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SectionTitle(stringResource(R.string.language))
        ChipRow(listOf("en" to "English", "es" to "Español"), vm.lang.let { l -> if (l == "es") "es" to "Español" else "en" to "English" }, { it.second }) {
            vm.setLang(it.first)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it.first))
        }
        SectionTitle(stringResource(R.string.theme))
        ChipRow(listOf("system", "light", "dark"), vm.theme, { it.replaceFirstChar { c -> c.uppercase() } }) { vm.setTheme(it) }
        SectionTitle(stringResource(R.string.export_resolution))
        ChipRow(listOf(720, 1080), vm.exportRes, { "${it}p" }) { vm.setExportRes(it) }

        SectionTitle(stringResource(R.string.export_location))
        ChipRow(listOf("gallery", "folder", "app"), vm.location,
            { when (it) { "gallery" -> "Gallery (Movies/VEXORA AI)"; "folder" -> "Custom folder"; else -> "App storage only" } }) {
            if (it == "folder") pickFolder.launch(null) else vm.setLocation(it)
        }
        if (vm.location == "folder") Text(vm.folderUri ?: "No folder chosen", fontSize = 11.sp)

        SectionTitle(stringResource(R.string.storage))
        Text("Videos: %.1f MB · Music library: %.1f MB".format(videoMb, musicMb))
        TextButton({ vm.clearMusic() }) { Text("Clear music library") }

        SectionTitle(stringResource(R.string.about))
        Text("VEXORA AI 1.0.0\nFully offline video editor. No account, no server, no internet permission. " +
            "Audio beat detection, video analysis and rendering all run on this device.", fontSize = 13.sp)
    }
}
