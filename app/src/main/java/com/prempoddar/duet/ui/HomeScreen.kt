package com.prempoddar.duet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.session.Phase
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
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp).padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
            Text("Duet", style = MaterialTheme.typography.displayMedium)
            Text("Same song, same moment —\nwherever you both are.", style = MaterialTheme.typography.bodyLarge, color = warm.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(28.dp))
            Vinyl(null, playing = true, label = "♥", modifier = Modifier.fillMaxWidth(0.82f).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(32.dp))
            Button(onClick = { withName { name -> sessions.host(name) } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Start listening together")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Join with a code") }

            if (nearby.isNotEmpty()) {
                Text("ON THIS WI-FI", style = MaterialTheme.typography.labelSmall, color = warm.muted, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
                nearby.forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
                            .clickable { withName { name -> sessions.joinNearby(n, name) } }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Wifi, null, tint = warm.brick)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.name, style = MaterialTheme.typography.titleMedium)
                            Text("Tap to ask to join", style = MaterialTheme.typography.bodySmall, color = warm.muted)
                        }
                    }
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
fun NameDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should people call you?") },
        text = { OutlinedTextField(name, { name = it.take(32) }, singleLine = true, placeholder = { Text("Your name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        confirmButton = { TextButton(onClick = { AppGraph.sessions.leave() }) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}
