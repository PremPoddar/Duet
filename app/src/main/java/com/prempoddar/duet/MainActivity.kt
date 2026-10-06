package com.prempoddar.duet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.duet.core.SessionCode
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.ui.DuetNav
import com.prempoddar.duet.ui.DuetTheme

class MainActivity : ComponentActivity() {
    /** A code from a duet:// link, waiting to be joined. */
    private val linkCode = mutableStateOf<SessionCode?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val settings: DuetSettings? by AppGraph.settings.settings.collectAsStateWithLifecycle(null)
            val s = settings ?: return@setContent
            DuetTheme(s.appearance) { DuetNav(s, linkCode.value, onLinkUsed = { linkCode.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.data?.toString()?.let(SessionCode::fromLink)?.let { linkCode.value = it }
    }
}
