package com.prempoddar.duet.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.core.Command
import com.prempoddar.duet.core.TrackMeta
import com.prempoddar.duet.ui.design.EmptyState
import com.prempoddar.duet.ui.design.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shared queue: the song playing in its own card, what's up next (with votes), and what has
 * played, dimmed. Tapping a song plays it for everyone; its menu has the rest.
 */
@Composable
internal fun QueueSheet(queue: List<TrackMeta>, index: Int, votes: Map<String, List<String>> = emptyMap(), playing: Boolean = false) {
    val warm = LocalWarm.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = AppGraph.sessions.session
    val store = AppGraph.sessions.store
    val myId = session?.myId
    // Redrawn as songs arrive, so their Save option turns on.
    val arriving by AppGraph.sessions.progress.collectAsStateWithLifecycle()
    // Songs others added that have arrived here; one's own songs are already on this phone.
    val saveable = remember(queue, arriving) { queue.filter { it.owner != myId && !it.isStream && store.has(it.id) } }
    fun save(songs: List<TrackMeta>) = scope.launch {
        val saved = withContext(Dispatchers.IO) {
            songs.count { t -> store.file(t.id)?.let { com.prempoddar.duet.data.SaveToPhone.save(context, t, it) } == true }
        }
        val text = when {
            saved == 0 -> "Couldn't save that to this phone"
            songs.size == 1 -> "Saved to Music/Duet"
            saved < songs.size -> "Saved $saved of ${songs.size} songs to Music/Duet"
            else -> "Saved $saved songs to Music/Duet"
        }
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
    val current = queue.getOrNull(index)
    val upcoming = queue.withIndex().filter { it.index > index }
    val played = queue.withIndex().filter { it.index < index }
    val left = upcoming.sumOf { it.value.durationMs.coerceAtLeast(0) }

    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        queue.isEmpty() -> "Nothing yet"
                        upcoming.isEmpty() -> "Last song"
                        left > 0 -> "${upcoming.size} up next · ${time(left)}"
                        else -> "${upcoming.size} up next"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = warm.muted,
                )
            }
            if (saveable.size > 1) FilledTonalButton(onClick = tap { save(saveable) }) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Text("  Save all")
            }
        }
        if (queue.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Nothing queued yet", "Anyone in the session can add songs from their own phone.", Modifier.padding(vertical = 32.dp))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (current != null) {
                item(key = "now-label") { Label("Now playing") }
                item(key = "now-${current.id}") {
                    QueueRow(current, index, queue, index, votes, myId, store.has(current.id), now = true, playing = playing, onSave = { save(listOf(current)) })
                }
            }
            if (upcoming.isNotEmpty()) item(key = "next-label") { Label("Up next") }
            itemsIndexed(upcoming, key = { _, it -> it.value.id }) { _, (i, t) ->
                QueueRow(t, i, queue, index, votes, myId, store.has(t.id), onSave = { save(listOf(t)) })
            }
            if (played.isNotEmpty()) item(key = "played-label") { Label("Played") }
            itemsIndexed(played, key = { _, it -> it.value.id }) { _, (i, t) ->
                QueueRow(t, i, queue, index, votes, myId, store.has(t.id), dim = true, onSave = { save(listOf(t)) })
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(
    text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
)

@Composable
private fun QueueRow(
    t: TrackMeta, i: Int, queue: List<TrackMeta>, index: Int, votes: Map<String, List<String>>, myId: String?, arrived: Boolean,
    now: Boolean = false, playing: Boolean = false, dim: Boolean = false, onSave: () -> Unit,
) {
    val warm = LocalWarm.current
    val session = AppGraph.sessions.session
    var menu by remember { mutableStateOf(false) }
    val voters = votes[t.id].orEmpty()
    val mineVote = myId in voters
    Surface(
        onClick = tap { if (!now) session?.command(Command.Jump(i)) },
        shape = RoundedCornerShape(if (now) 24.dp else 18.dp),
        color = if (now) warm.brick.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).alpha(if (dim) 0.6f else 1f),
    ) {
        Row(Modifier.padding(start = if (now) 12.dp else 10.dp, end = 4.dp, top = if (now) 12.dp else 8.dp, bottom = if (now) 12.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t, if (now) 60 else 48, if (now) playing else false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, style = if (now) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = if (now) warm.brick else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(sourceIcon(t), null, Modifier.size(14.dp), tint = warm.muted)
                    Text(
                        "  " + listOf(t.artist, if (t.durationMs > 0) time(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { sourceName(t) },
                        style = MaterialTheme.typography.bodySmall, color = warm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Vote for an upcoming song to hear it sooner; tap again to take the vote back.
            if (i > index) {
                Surface(
                    onClick = tap { session?.command(Command.Vote(t.id)) }, shape = CircleShape,
                    color = if (mineVote) warm.brick.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (mineVote) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
                            if (mineVote) "Take back your vote" else "Vote for this song",
                            tint = if (mineVote) warm.brick else warm.muted, modifier = Modifier.size(16.dp),
                        )
                        if (voters.isNotEmpty()) Text(" ${voters.size}", style = MaterialTheme.typography.labelLarge, color = if (mineVote) warm.brick else warm.muted)
                    }
                }
            }
            Box {
                IconButton(onClick = tap { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${t.title}", tint = warm.muted) }
                SongMenu(menu, onDismiss = { menu = false }, t, i, index, mineVote, myId, arrived, onSave)
            }
        }
    }
}

/** The song's options, with its name on top so it's clear which one they're for. */
@Composable
private fun SongMenu(open: Boolean, onDismiss: () -> Unit, t: TrackMeta, i: Int, index: Int, mineVote: Boolean, myId: String?, arrived: Boolean, onSave: () -> Unit) {
    val warm = LocalWarm.current
    val session = AppGraph.sessions.session
    DropdownMenu(
        open, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t, 36, false)
            Column(Modifier.padding(start = 12.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist.ifBlank { sourceName(t) }, style = MaterialTheme.typography.bodySmall, color = warm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        fun act(block: () -> Unit) = tap { onDismiss(); block() }
        if (i != index) Item("Play now", Icons.Rounded.PlayArrow, act { session?.command(Command.Jump(i)) })
        if (i > index + 1) Item("Play next", Icons.Rounded.QueuePlayNext, act { session?.command(Command.Move(i, index + 1)) })
        if (i > index) Item(if (mineVote) "Take back vote" else "Vote to hear sooner", if (mineVote) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, act { session?.command(Command.Vote(t.id)) })
        when {
            t.owner == myId || t.isStream -> Unit
            arrived -> Item("Save to phone", Icons.Rounded.Download, act(onSave))
            else -> Item("Still arriving…", Icons.Rounded.HourglassTop, {}, enabled = false)
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Item("Remove from queue", Icons.Rounded.DeleteOutline, act { session?.command(Command.Remove(t.id)) }, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Item(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface) =
    DropdownMenuItem(
        text = { Text(text, color = if (enabled) tint else tint.copy(alpha = 0.4f)) },
        leadingIcon = { Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.4f)) },
        onClick = onClick, enabled = enabled,
    )

/** The cover, or the source's icon on the record colour; three dancing bars while it plays. */
@Composable
private fun Cover(t: TrackMeta, size: Int, playing: Boolean) {
    val warm = LocalWarm.current
    val art = remember(t.id) { decodeArt(t.art) }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(warm.record), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(sourceIcon(t), null, tint = warm.brick.copy(alpha = 0.8f), modifier = Modifier.size((size / 2).dp))
        if (playing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Bars(Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun Bars(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0, 180, 360).map { delay ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, delayMillis = delay), RepeatMode.Reverse), label = "bar$delay")
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(Modifier.weight(1f).fillMaxHeight(h.value).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

private fun sourceIcon(t: TrackMeta): ImageVector = when {
    t.youtube != null -> Icons.Rounded.SmartDisplay
    t.external != null -> Icons.Rounded.Apps
    else -> Icons.Rounded.MusicNote
}

private fun sourceName(t: TrackMeta) = when {
    t.youtube != null -> "YouTube"
    t.external != null -> "Another app"
    else -> "Song file"
}
