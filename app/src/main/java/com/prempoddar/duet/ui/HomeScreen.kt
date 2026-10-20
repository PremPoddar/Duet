package com.prempoddar.duet.ui

import com.prempoddar.duet.ui.design.tap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.net.NearbySession
import com.prempoddar.duet.session.Phase
import com.prempoddar.duet.ui.design.BigButton
import com.prempoddar.duet.ui.design.PillTextField
import com.prempoddar.duet.ui.design.SettingsGroup
import com.prempoddar.duet.ui.design.SettingsRow
import com.prempoddar.duet.ui.design.TonalBigButton
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(settings: DuetSettings, onJoin: () -> Unit, onSettings: () -> Unit) {
    val warm = LocalWarm.current
    val sessions = AppGraph.sessions
    val nearby by sessions.nearby.collectAsStateWithLifecycle()
    val phase by sessions.phase.collectAsStateWithLifecycle()
    var askName by remember { mutableStateOf<((String) -> Unit)?>(null) }
    // Owned by the screen, not the dialog: closing the dialog must not cancel what it started.
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        sessions.browseNearby()
        onDispose { sessions.stopBrowsing() }
    }
    fun withName(then: (String) -> Unit) = if (settings.name.isBlank()) askName = then else then(settings.name)
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    WarmBackground(warm.brick) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight && maxWidth >= 560.dp
            val actions: @Composable ColumnScope.() -> Unit = {
                BigButton("Start listening together", onClick = { withName { name -> sessions.host(name) } }, icon = Icons.Rounded.Headphones)
                Spacer(Modifier.height(12.dp))
                TonalBigButton("Join with a code", onClick = onJoin, icon = Icons.Rounded.QrCodeScanner)
                NearbyList(nearby) { n -> withName { name -> sessions.joinNearby(n, name) } }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding())
                        .padding(start = insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Hero(Modifier.weight(1f).fillMaxHeight().padding(vertical = 16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.Center) {
                        Header(onSettings)
                        Spacer(Modifier.height(20.dp))
                        actions()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Header(onSettings)
                    Spacer(Modifier.height(16.dp))
                    Hero(Modifier.fillMaxWidth().widthIn(max = 520.dp))
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.widthIn(max = 520.dp)) { actions() }
                }
            }
        }
    }

    (phase as? Phase.Connecting)?.let { ConnectingDialog(it.message) }
    when (val p = phase) {
        is Phase.Failed -> InfoDialog("Couldn't connect", p.message) { sessions.dismissEnd() }
        is Phase.Ended -> InfoDialog("Session ended", p.message) { sessions.dismissEnd() }
        else -> Unit
    }
    askName?.let { then ->
        NameDialog(onDone = { name ->
            askName = null
            scope.launch {
                AppGraph.settings.update { it.copy(name = name) }
                then(name)
            }
        }, onDismiss = { askName = null })
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Duet", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Same song, same moment — wherever you both are.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = tap(onSettings), modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

/** The record on a soft tonal card. */
@Composable
private fun Hero(modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)) {
        Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
            Vinyl(null, playing = true, label = "♥", modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp))
        }
    }
}

@Composable
private fun NearbyList(nearby: List<NearbySession>, onPick: (NearbySession) -> Unit) {
    if (nearby.isEmpty()) {
        Text(
            "Sessions on this Wi-Fi show up here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        return
    }
    Spacer(Modifier.height(8.dp))
    SettingsGroup("On this Wi-Fi", Modifier.padding(horizontal = 0.dp)) {
        nearby.forEach { n ->
            SettingsRow(n.name, "Tap to ask to join", Icons.Rounded.Wifi, onClick = { onPick(n) }) {
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NameDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should people call you?") },
        text = {
            PillTextField(
                name, { name = it.take(32) }, "Your name",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onDone(name.trim()) }),
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = tap { onDone(name.trim()) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = tap(onDismiss)) { Text("Cancel") } },
    )
}

@Composable
fun ConnectingDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Connecting") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Vinyl(null, playing = true, label = "", modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Text(message)
            }
        },
        confirmButton = { TextButton(onClick = tap { AppGraph.sessions.leave() }) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = tap(onDismiss)) { Text("OK") } })
}
