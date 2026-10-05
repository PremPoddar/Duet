package com.prempoddar.duet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.data.Output
import com.prempoddar.duet.data.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val warm = LocalWarm.current
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(DuetSettings())
    val output by AppGraph.player.output.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(f: (DuetSettings) -> DuetSettings) = scope.launch { AppGraph.settings.update(f) }
    var name by remember { mutableStateOf(settings.name) }
    LaunchedEffect(settings.name) { if (name.isBlank()) name = settings.name }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    Column(
        Modifier.fillMaxSize().background(warm.paper).verticalScroll(rememberScrollState())
            .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Card("Your name") {
            OutlinedTextField(name, { name = it.take(32); if (it.isNotBlank()) update { s -> s.copy(name = it.trim().take(32)) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Card("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                options.forEachIndexed { i, (m, l) -> SegmentedButton(settings.theme == m, { update { it.copy(theme = m) } }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(l) } }
            }
        }
        Card("Sync nudge") {
            Text(
                "Headphones and speakers add their own delay — Bluetooth most of all. If you hear the song slightly behind (or ahead of) the others, move the slider for that kind of output until it lines up.",
                style = MaterialTheme.typography.bodySmall, color = warm.muted,
            )
            Nudge("Phone speaker", settings.nudgeSpeaker, output == Output.SPEAKER) { v -> update { it.copy(nudgeSpeaker = v) } }
            Nudge("Wired headphones", settings.nudgeWired, output == Output.WIRED) { v -> update { it.copy(nudgeWired = v) } }
            Nudge("Bluetooth", settings.nudgeBluetooth, output == Output.BLUETOOTH) { v -> update { it.copy(nudgeBluetooth = v) } }
        }
        Card("How Duet connects") {
            Text(
                "Phones connect to each other directly. To find each other across the internet they swap a few encrypted messages through a free public relay (HiveMQ); it only ever sees scrambled data, never your music. On the same Wi-Fi no internet is needed at all. Songs are deleted from listeners' phones when the session ends.",
                style = MaterialTheme.typography.bodySmall, color = warm.muted,
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable
private fun Nudge(label: String, value: Int, active: Boolean, onChange: (Int) -> Unit) {
    val warm = LocalWarm.current
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(top = 12.dp)) {
        Row {
            Text(label + if (active) " · in use" else "", style = MaterialTheme.typography.titleSmall, color = if (active) warm.brick else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("${if (v > 0) "+" else ""}${v.toInt()} ms", style = MaterialTheme.typography.labelMedium, color = warm.muted)
        }
        Slider(v, { v = (it / 10).toInt() * 10f }, valueRange = -400f..400f, onValueChangeFinished = { onChange(v.toInt()) })
    }
}
