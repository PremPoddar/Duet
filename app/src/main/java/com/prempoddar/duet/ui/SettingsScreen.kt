package com.prempoddar.duet.ui

import com.prempoddar.duet.ui.design.sliding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.data.Output
import com.prempoddar.duet.ui.design.ExpressiveScaffold
import com.prempoddar.duet.ui.design.IconTile
import com.prempoddar.duet.ui.design.PillTextField
import com.prempoddar.duet.ui.design.SettingsGroup
import com.prempoddar.duet.ui.design.SettingsRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onAppearance: () -> Unit, onAbout: () -> Unit) {
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(DuetSettings())
    val output by AppGraph.player.output.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(f: (DuetSettings) -> DuetSettings) = scope.launch { AppGraph.settings.update(f) }
    var name by remember { mutableStateOf(settings.name) }
    LaunchedEffect(settings.name) { if (name.isBlank()) name = settings.name }

    ExpressiveScaffold("Settings", onBack = onBack) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PillTextField(
                        name, { name = it.take(32); if (it.isNotBlank()) update { s -> s.copy(name = it.trim().take(32)) } }, "Your name",
                        supporting = "What others see in a session", clearable = false,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            }
            item {
                SettingsGroup("General") {
                    SettingsRow("Appearance", "Theme, colours, font, shapes and haptics", Icons.Rounded.Palette, onClick = onAppearance) { Chevron() }
                }
            }
            item {
                SettingsGroup("Sync nudge") {
                    SettingsRow(
                        "Line up your headphones",
                        "Headphones and speakers add their own delay — Bluetooth most of all. If you hear the song slightly behind or ahead of the others, move the slider for that output until it lines up.",
                    )
                    Nudge("Phone speaker", Icons.Rounded.Speaker, settings.nudgeSpeaker, output == Output.SPEAKER) { v -> update { it.copy(nudgeSpeaker = v) } }
                    Nudge("Wired headphones", Icons.Rounded.Headphones, settings.nudgeWired, output == Output.WIRED) { v -> update { it.copy(nudgeWired = v) } }
                    Nudge("Bluetooth", Icons.Rounded.Bluetooth, settings.nudgeBluetooth, output == Output.BLUETOOTH) { v -> update { it.copy(nudgeBluetooth = v) } }
                }
            }
            item {
                SettingsGroup("About") {
                    SettingsRow("About Duet", "Privacy, version, licences", Icons.Rounded.Info, onClick = onAbout) { Chevron() }
                }
            }
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/** One output's delay, in steps of 10 ms. */
@Composable
private fun Nudge(label: String, icon: ImageVector, value: Int, active: Boolean, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${if (v > 0) "+" else ""}${v.toInt()} ms" + if (active) " · in use now" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(v, sliding(-400f..400f) { v = (it / 10).toInt() * 10f }, valueRange = -400f..400f, onValueChangeFinished = { onChange(v.toInt()) }, modifier = Modifier.padding(start = 56.dp))
    }
}
