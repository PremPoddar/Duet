package com.prempoddar.duet.ui

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import com.prempoddar.duet.ui.design.PillTextField
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.core.Command
import com.prempoddar.duet.core.SessionCode
import com.prempoddar.duet.core.TrackMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val QUICK = listOf("❤️", "🔥", "😂", "😭", "🎶", "👏")

private enum class Sheet { CODE, QUEUE, CHAT, PEOPLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(onAddSong: () -> Unit) {
    val warm = LocalWarm.current
    val sessions = AppGraph.sessions
    val state by sessions.state.collectAsStateWithLifecycle()
    val members by sessions.members.collectAsStateWithLifecycle()
    val chat by sessions.chat.collectAsStateWithLifecycle()
    val progress by sessions.progress.collectAsStateWithLifecycle()
    val code by sessions.code.collectAsStateWithLifecycle()
    val requests by sessions.pairRequests.collectAsStateWithLifecycle()
    val drift by AppGraph.player.drift.collectAsStateWithLifecycle()
    val session = sessions.session
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var seenChat by remember { mutableIntStateOf(0) }
    val track = state.current
    val art = remember(track?.id) { decodeArt(track?.art) }
    val glow = remember(art) { glowOf(art, warm.brick) }

    // The position shown on the slider follows the shared clock.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { now = session?.hostNow() ?: 0L; delay(250) } }
    val position = state.positionAt(now)
    var dragging by remember { mutableStateOf<Float?>(null) }

    val others = members.filter { it.id != session?.myId }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    if (sheet == Sheet.CHAT) seenChat = chat.size

    val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LISTENING TOGETHER", style = MaterialTheme.typography.labelSmall, color = warm.muted)
                    Text(
                        if (others.isEmpty()) "Waiting for someone to join" else "with " + others.joinToString(" & ") { it.name },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { sheet = Sheet.CODE }) { Icon(Icons.Rounded.Share, "Invite someone", tint = if (others.isEmpty()) warm.brick else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { sheet = Sheet.PEOPLE }) { Icon(Icons.Rounded.Group, "People") }
                IconButton(onClick = { confirmLeave = true }) { Icon(Icons.Rounded.Close, "Leave") }
            }

    }
    val record: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                Vinyl(art, playing = state.playing && track != null, label = track?.title ?: "♥", modifier = Modifier.fillMaxWidth(0.8f))
                FloatingReactions()
                HeartPulse()
            }
    }
    val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                if (track == null) {
                    Text("Nothing's playing yet", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onAddSong, modifier = Modifier.padding(top = 16.dp).align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Rounded.Add, null); Text("  Add the first song")
                    }
                } else {
                    Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val adder = members.firstOrNull { it.id == track.owner }?.name
                    Text(
                        listOf(track.artist, adder?.let { "added by $it" }).filter { !it.isNullOrBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = warm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val have = sessions.store.has(track.id)
                    val p = progress[track.id]
                    Text(
                        when {
                            !have -> "Getting the song… ${((p ?: 0f) * 100).toInt()}%"
                            !state.playing -> "Paused"
                            kotlin.math.abs(drift) < 40 -> "In sync"
                            else -> "Catching up…"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
                    )
                    Slider(
                        value = dragging ?: (position.toFloat() / track.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { session?.command(Command.Seek((it * track.durationMs).toLong())) }; dragging = null },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(time(dragging?.let { (it * track.durationMs).toLong() } ?: position), style = MaterialTheme.typography.labelMedium, color = warm.muted)
                        Spacer(Modifier.weight(1f))
                        Text(time(track.durationMs), style = MaterialTheme.typography.labelMedium, color = warm.muted)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { session?.heart() }) { Icon(Icons.Rounded.Favorite, "Send a heartbeat", tint = warm.brick, modifier = Modifier.size(28.dp)) }
                    IconButton(enabled = track != null, onClick = { session?.command(Command.Previous) }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(enabled = track != null) {
                            session?.command(if (state.playing) Command.Pause else Command.Play)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause for everyone" else "Play for everyone", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp))
                    }
                    IconButton(enabled = track != null, onClick = { session?.command(Command.Next) }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(36.dp)) }
                    ReactButton { session?.react(it) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { sheet = Sheet.QUEUE }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Text("  Queue · ${state.queue.size}") }
                    TextButton(onClick = onAddSong) { Icon(Icons.Rounded.Add, null); Text("  Add") }
                    TextButton(onClick = { sheet = Sheet.CHAT }) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null)
                        val unread = (chat.size - seenChat).coerceAtLeast(0)
                        Text(if (unread > 0) "  Chat · $unread" else "  Chat")
                    }
                }
            }
    }
    WarmBackground(glow) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())) {
            if (maxWidth > maxHeight && maxWidth >= 560.dp) {
                // Landscape: the record on the left, everything else beside it.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    record(Modifier.weight(1f).fillMaxHeight().padding(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        header()
                        controls()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    header()
                    record(Modifier.fillMaxWidth().weight(1f))
                    controls()
                }
            }
        }
    }

    when (sheet) {
        Sheet.CODE -> ModalBottomSheet(onDismissRequest = { sheet = null }) { code?.let { CodeSheet(it) } }
        Sheet.QUEUE -> ModalBottomSheet(onDismissRequest = { sheet = null }) { QueueSheet(state.queue, state.index) }
        Sheet.CHAT -> ModalBottomSheet(onDismissRequest = { sheet = null }) { ChatSheet() }
        Sheet.PEOPLE -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("In this session", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                members.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(warm.brick.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Text(m.name.take(1).uppercase(), fontFamily = Fraunces) }
                        Spacer(Modifier.width(12.dp))
                        Text(m.name + if (m.id == session?.myId) " (you)" else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (m.isHost) Text("HOST", style = MaterialTheme.typography.labelSmall, color = warm.brick, modifier = Modifier.padding(end = 8.dp))
                        Text(if (m.ready) "ready" else "getting the song", style = MaterialTheme.typography.bodySmall, color = if (m.ready) warm.muted else warm.amber)
                    }
                }
            }
        }
        null -> Unit
    }

    requests.firstOrNull()?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${req.name} wants to join") },
            text = { Text("They're on the same Wi-Fi as you. Let them listen along?") },
            confirmButton = { TextButton(onClick = { sessions.approve(req) }) { Text("Let them in") } },
            dismissButton = { TextButton(onClick = { sessions.deny(req) }) { Text("Not now") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (session?.isHost == true) "End the session?" else "Leave the session?") },
            text = { Text(if (session?.isHost == true) "The music stops for everyone." else "The others keep listening.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; sessions.leave() }) { Text(if (session?.isHost == true) "End" else "Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun ReactButton(onReact: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Text("😊", fontSize = 24.sp) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                QUICK.forEach { e ->
                    Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { open = false; onReact(e); LocalReactions.emit("You", e) }.padding(8.dp))
                }
            }
        }
    }
}

/** Reactions I send show on my own screen straight away; others' arrive from the session. */
private object LocalReactions {
    val mine = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    fun emit(name: String, emoji: String) { mine.tryEmit(name to emoji) }
}

private data class Floater(val id: Long, val emoji: String, val x: Float)

@Composable
private fun FloatingReactions() {
    val items = remember { mutableStateListOf<Floater>() }
    LaunchedEffect(Unit) {
        launch { AppGraph.sessions.reactions.collect { items += Floater(it.id, it.emoji, (Math.random() * 160 - 80).toFloat()) } }
        LocalReactions.mine.collect { items += Floater(System.nanoTime(), it.second, (Math.random() * 160 - 80).toFloat()) }
    }
    items.forEach { f ->
        androidx.compose.runtime.key(f.id) {
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                rise.animateTo(1f, tween(2600))
                items.remove(f)
            }
            Text(
                f.emoji, fontSize = 34.sp,
                modifier = Modifier.offset(x = f.x.dp, y = (120 - 320 * rise.value).dp).alpha(1f - rise.value).scale(0.8f + rise.value * 0.6f),
            )
        }
    }
}

@Composable
private fun HeartPulse() {
    val warm = LocalWarm.current
    var who by remember { mutableStateOf<String?>(null) }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        AppGraph.sessions.hearts.collect { name ->
            who = name
            repeat(2) {
                pulse.snapTo(0f); pulse.animateTo(1f, tween(380)); pulse.animateTo(0f, tween(420))
            }
            delay(1200)
            who = null
        }
    }
    who?.let { name ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Favorite, null, tint = warm.brick, modifier = Modifier.size(96.dp).scale(1f + pulse.value * 0.35f))
            Text(
                "$name is thinking of you", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CodeSheet(code: SessionCode) {
    val context = LocalContext.current
    val warm = LocalWarm.current
    val qr = remember(code) { qr(code.link()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Invite someone", style = MaterialTheme.typography.headlineSmall)
        Text("Read these words out, let them scan the code, or send the link.", style = MaterialTheme.typography.bodyMedium, color = warm.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Text(code.words.take(3).joinToString("  "), style = MaterialTheme.typography.headlineMedium, color = warm.brick)
        Text(code.words.drop(3).joinToString("  "), style = MaterialTheme.typography.headlineMedium, color = warm.brick)
        Box(Modifier.padding(vertical = 18.dp).clip(MaterialTheme.shapes.medium).background(Color(0xFFFFF8F0)).padding(14.dp)) { Image(qr, "Session QR code", Modifier.size(190.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Duet code", code.toString()))
            }) { Text("Copy words") }
            Button(onClick = {
                val text = "Listen with me on Duet 🎶\nOpen: ${code.link()}\nor type these words in Duet: $code"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite"))
            }) { Text("Share invite") }
        }
        Text("Anyone with these words can join, so share them only with people you want here.", style = MaterialTheme.typography.bodySmall, color = warm.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
    }
}

private fun qr(text: String, size: Int = 600): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF2B1E16.toInt() else 0 }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun QueueSheet(queue: List<TrackMeta>, index: Int) {
    val warm = LocalWarm.current
    val session = AppGraph.sessions.session
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(bottom = 24.dp)) {
        Text("Up next", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (queue.isEmpty()) Text("The queue is empty. Anyone can add songs from their own phone.", modifier = Modifier.padding(24.dp), color = warm.muted)
        LazyColumn {
            itemsIndexed(queue, key = { _, t -> t.id }) { i, t ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().clickable { session?.command(Command.Jump(i)) }.background(if (i == index) warm.brick.copy(alpha = 0.1f) else Color.Transparent)
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val art = remember(t.id) { decodeArt(t.art) }
                        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(warm.record), contentAlignment = Alignment.Center) {
                            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, color = if (i == index) warm.brick else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist.ifBlank { time(t.durationMs) }, style = MaterialTheme.typography.bodySmall, color = warm.muted, maxLines = 1)
                        }
                        if (i == index) Text("NOW", style = MaterialTheme.typography.labelSmall, color = warm.brick)
                        IconButton(onClick = { menu = true }) { Text("⋯") }
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (i > index + 1) DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; session?.command(Command.Move(i, index + 1)) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; session?.command(Command.Remove(t.id)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSheet() {
    val warm = LocalWarm.current
    val chat by AppGraph.sessions.chat.collectAsStateWithLifecycle()
    val session = AppGraph.sessions.session
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.lastIndex) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding()) {
        Text("Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f, fill = false), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            if (chat.isEmpty()) item { Text("Say something about the song.", color = warm.muted, modifier = Modifier.padding(8.dp)) }
            items(chat, key = { it.id.ifEmpty { it.at.toString() } }) { line ->
                val mine = line.from == session?.myId
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (!mine) Text(line.name, style = MaterialTheme.typography.labelMedium, color = warm.brick, modifier = Modifier.padding(start = 10.dp))
                    Text(
                        line.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clip(MaterialTheme.shapes.medium).background(if (mine) warm.brick.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillTextField(
                text, { text = it.take(500) }, "Message", modifier = Modifier.weight(1f), clearable = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { session?.say(text); text = "" } }),
            )
            IconButton(enabled = text.isNotBlank(), onClick = { session?.say(text); text = "" }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = warm.brick) }
        }
    }
}
