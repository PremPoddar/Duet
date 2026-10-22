package com.prempoddar.duet.ui

import com.prempoddar.duet.ui.design.tap
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import com.prempoddar.duet.ui.design.PillTextField
import com.prempoddar.duet.ui.design.EmptyState
import com.prempoddar.duet.ui.design.BigButton
import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.Library
import com.prempoddar.duet.data.LibrarySong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun LibraryScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var songs by remember { mutableStateOf<List<LibrarySong>?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(granted) { if (granted) songs = Library.songs(context) }

    fun add(uri: Uri) {
        adding = uri
        scope.launch {
            runCatching { AppGraph.sessions.addSong(uri) }
                .onSuccess { onDone() }
                .onFailure { e ->
                    Toast.makeText(context, if (e is Library.TooBig) "That song is over 50 MB" else "That file couldn't be added", Toast.LENGTH_LONG).show()
                }
            adding = null
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::add) }
    var youtube by remember { mutableStateOf(false) }
    var otherApp by remember { mutableStateOf(false) }
    if (otherApp) OtherAppDialog(onDismiss = { otherApp = false }, onAdded = { otherApp = false; onDone() })
    if (youtube) YouTubeLinkDialog(onDismiss = { youtube = false }, onAdded = { youtube = false; onDone() })
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(top = insets.calculateTopPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = tap(onDone)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Add a song", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = tap { otherApp = true }) { Icon(Icons.Rounded.Apps, "Play along with another app") }
            IconButton(onClick = tap { youtube = true }) { Icon(Icons.Rounded.SmartDisplay, "Add from YouTube") }
            IconButton(onClick = tap { pickFile.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.FolderOpen, "Pick another file") }
        }
        if (!granted) {
            Column(Modifier.padding(24.dp)) {
                Text("Duet needs to see the music on this phone to list it.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                BigButton("Allow access to music", onClick = { ask.launch(audioPermission) }, modifier = Modifier.padding(top = 16.dp), icon = Icons.Rounded.MusicNote)
                Text("Or pick a single file with the folder button above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            return@Column
        }
        PillTextField(
            query, { query = it }, "Search your music", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            leading = Icons.Rounded.Search,
        )
        val list = songs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Rounded.MusicNote, "No music found", "Use the folder button at the top to pick a song file.")
            else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = insets.calculateBottomPadding() + 16.dp)) {
                val q = query.trim().lowercase()
                items(list.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }, key = { it.uri }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = adding == null) { add(song.uri) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumb by produceState<ImageBitmap?>(null, song.uri) { value = withContext(Dispatchers.IO) { Library.thumbnail(context, song.uri)?.asImageBitmap() } }
                        Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Unknown artist" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (adding == song.uri) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(time(song.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * A YouTube link becomes a queue entry everyone streams. The title and channel come from
 * YouTube's public oEmbed lookup; if that fails the video is still added, just without a name.
 */
@Composable
private fun YouTubeLinkDialog(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = remember {
        runCatching { context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
    }
    var link by remember { mutableStateOf(clip?.takeIf { com.prempoddar.duet.core.YouTube.videoId(it) != null || com.prempoddar.duet.core.YouTube.playlistId(it) != null }.orEmpty()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val id = com.prempoddar.duet.core.YouTube.videoId(link)
    val list = com.prempoddar.duet.core.YouTube.playlistId(link)
    var wholeList by remember { mutableStateOf(true) }
    val valid = id != null || list != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SmartDisplay, null) },
        title = { Text("Add from YouTube") },
        text = {
            Column {
                Text("Paste a link to a video or song. Every phone plays it from YouTube, so everyone needs internet.", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedTextField(
                    link, { link = it }, singleLine = true, placeholder = { Text("youtube.com/watch?v=…") },
                    isError = link.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                    supportingText = { if (link.isNotBlank() && !valid) Text("That doesn't look like a YouTube video or playlist link") },
                    trailingIcon = {
                        if (link.isNotEmpty()) androidx.compose.material3.IconButton(onClick = tap { link = "" }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
                    },
                )
                if (list != null && id != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(wholeList, { wholeList = it })
                        Text("Add the whole playlist, not just this video", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = valid && busy == null, onClick = tap {
                busy = "Adding…"
                scope.launch {
                    val me = AppGraph.sessions.session?.myId.orEmpty()
                    val ids = if (list != null && (id == null || wholeList)) {
                        busy = "Reading the playlist…"
                        com.prempoddar.duet.play.YouTubePlayer.playlist(context, list).take(com.prempoddar.duet.core.YouTube.PLAYLIST_LIMIT)
                    } else listOfNotNull(id)
                    if (ids.isEmpty()) {
                        busy = null
                        android.widget.Toast.makeText(context, "That playlist couldn't be read. It may be private.", android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    ids.forEachIndexed { i, v ->
                        if (ids.size > 1) busy = "Adding ${i + 1} of ${ids.size}…"
                        val (title, channel) = withContext(Dispatchers.IO) { youTubeTitle(v) }
                        AppGraph.sessions.addStream(com.prempoddar.duet.core.YouTube.track(v, title, channel, me))
                    }
                    busy = null
                    if (ids.size > 1) android.widget.Toast.makeText(context, "Added ${ids.size} videos", android.widget.Toast.LENGTH_SHORT).show()
                    onAdded()
                }
            }) { Text(if (busy != null) "Adding…" else "Add") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = tap(onDismiss)) { Text("Cancel") } },
    )
}

/** The video's title and channel from YouTube's oEmbed endpoint, or blanks if it can't be reached. */
private fun youTubeTitle(id: String): Pair<String, String> = runCatching {
    val url = java.net.URL("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8"))
    val conn = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 6_000 }
    try {
        val o = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        o.optString("title") to o.optString("author_name")
    } finally { conn.disconnect() }
}.getOrDefault("" to "")

/**
 * Sharing what Spotify, Apple Music or another app is playing: everyone plays it in their own
 * app, and Duet keeps them in step through those apps' controls.
 */
@Composable
private fun OtherAppDialog(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var access by remember { mutableStateOf(com.prempoddar.duet.play.OtherApps.hasAccess(context)) }
    var playing by remember { mutableStateOf(emptyList<com.prempoddar.duet.play.OtherApps.Playing>()) }
    // Checked again on return from Settings, and every couple of seconds while open.
    LaunchedEffect(Unit) {
        while (true) {
            access = com.prempoddar.duet.play.OtherApps.hasAccess(context)
            if (access) playing = withContext(Dispatchers.IO) { com.prempoddar.duet.play.OtherApps.playing(context) }
            kotlinx.coroutines.delay(2_000)
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Apps, null) },
        title = { Text("Play along with another app") },
        text = {
            Column {
                Text(
                    "Start a song in Spotify, Apple Music, YouTube Music or another app, then share it here. Everyone plays it in their own app with their own account, and Duet keeps them together (to about a second).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!access) {
                    Text("Duet needs to see what's playing on this phone. Allow it in the next screen (it reads no messages).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    androidx.compose.material3.TextButton(onClick = tap { context.startActivity(com.prempoddar.duet.play.OtherApps.accessSettings()) }) { Text("Allow") }
                } else if (playing.isEmpty()) {
                    Text("Nothing is playing in another app yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                } else playing.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).clip(MaterialTheme.shapes.medium).clickable {
                            AppGraph.sessions.addStream(com.prempoddar.duet.play.OtherApps.track(p, AppGraph.sessions.session?.myId.orEmpty()))
                            onAdded()
                        }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (p.playing) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote, null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(p.artist, p.appName).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = tap(onDismiss)) { Text("Close") } },
    )
}
