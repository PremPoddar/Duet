package com.prempoddar.duet.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Headphones
import com.prempoddar.duet.ui.design.TonalBigButton
import com.prempoddar.duet.ui.design.PillTextField
import com.prempoddar.duet.ui.design.BigButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.core.SessionCode
import com.prempoddar.duet.core.Words
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.session.Phase
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JoinScreen(settings: DuetSettings, initial: String, onBack: () -> Unit) {
    val warm = LocalWarm.current
    var text by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(settings.name) }
    val scope = rememberCoroutineScope()
    val phase by AppGraph.sessions.phase.collectAsStateWithLifecycle()
    val scan = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { c -> (SessionCode.fromLink(c) ?: SessionCode.parse(c))?.let { text = it.toString() } }
    }
    val tokens = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    val code = SessionCode.parse(text)
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    WarmBackground(warm.brick) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(top = insets.calculateTopPadding()).padding(horizontal = 24.dp).widthIn(max = 560.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Join a session", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Type the six words from the person hosting, or scan their code.",
                style = MaterialTheme.typography.bodyMedium, color = warm.muted, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            PillTextField(
                text, { text = it.take(120) }, "Six words", singleLine = false, minLines = 2,
                supporting = "The first four letters of each word are enough",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            )
            // Each word turns brick once it's recognised; the first four letters are enough.
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tokens.take(6).forEach { t ->
                    val word = Words.resolve(t)
                    Text(
                        word ?: t,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (word != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(CircleShape).background(if (word != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (settings.name.isBlank()) {
                PillTextField(name, { name = it.take(32) }, "Your name", modifier = Modifier.padding(top = 16.dp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(
                    "Join", enabled = code != null && name.isNotBlank(), icon = Icons.Rounded.Headphones,
                    onClick = {
                        scope.launch {
                            if (settings.name.isBlank()) AppGraph.settings.update { it.copy(name = name.trim()) }
                            AppGraph.sessions.browseNearby()
                            AppGraph.sessions.join(code!!, name.trim())
                        }
                    },
                )
                TonalBigButton("Scan their code", icon = Icons.Rounded.QrCodeScanner, onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Duet code").setBeepEnabled(false).setOrientationLocked(true))
                })
            }
        }
    }
    (phase as? Phase.Connecting)?.let { ConnectingDialog(it.message) }
    when (val p = phase) {
        is Phase.Failed -> InfoDialog("Couldn't connect", p.message) { AppGraph.sessions.dismissEnd() }
        is Phase.Ended -> InfoDialog("Session ended", p.message) { AppGraph.sessions.dismissEnd() }
        else -> Unit
    }
}
