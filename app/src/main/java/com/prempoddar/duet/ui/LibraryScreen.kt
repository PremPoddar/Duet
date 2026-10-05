package com.prempoddar.duet.ui

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
    val warm = LocalWarm.current
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
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    Column(Modifier.fillMaxSize().background(warm.paper).padding(top = insets.calculateTopPadding())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Add a song", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickFile.launch(arrayOf("audio/*")) }) { Icon(Icons.Rounded.FolderOpen, "Pick another file") }
        }
        if (!granted) {
            Column(Modifier.padding(24.dp)) {
                Text("Duet needs to see the music on this phone to list it.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { ask.launch(audioPermission) }, modifier = Modifier.padding(top = 16.dp)) { Text("Allow access to music") }
                Text("Or pick a single file with the folder button above.", style = MaterialTheme.typography.bodySmall, color = warm.muted, modifier = Modifier.padding(top = 12.dp))
            }
            return@Column
        }
        TextField(
            query, { query = it }, placeholder = { Text("Search your music") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.surface, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
            ),
        )
        val list = songs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text("No music found on this phone. Use the folder button to pick a file.", modifier = Modifier.padding(24.dp))
            else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = insets.calculateBottomPadding() + 16.dp)) {
                val q = query.trim().lowercase()
                items(list.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }, key = { it.uri }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = adding == null) { add(song.uri) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumb by produceState<ImageBitmap?>(null, song.uri) { value = withContext(Dispatchers.IO) { Library.thumbnail(context, song.uri)?.asImageBitmap() } }
                        Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Icon(Icons.Rounded.MusicNote, null, tint = warm.muted)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Unknown artist" },
                                style = MaterialTheme.typography.bodySmall, color = warm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (adding == song.uri) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(time(song.durationMs), style = MaterialTheme.typography.bodySmall, color = warm.muted)
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
