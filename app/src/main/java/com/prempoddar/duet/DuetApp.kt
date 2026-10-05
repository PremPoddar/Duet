package com.prempoddar.duet

import android.app.Application
import android.content.Intent
import com.prempoddar.duet.play.DuetService
import com.prempoddar.duet.session.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DuetApp : Application() {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        main.launch {
            AppGraph.player.start()
            launch { AppGraph.settings.settings.collect { AppGraph.player.settings = it } }
            // The media service runs while a session is live, so music keeps going in the background.
            AppGraph.sessions.phase.collect { phase ->
                if (phase is Phase.Live) runCatching { startService(Intent(this@DuetApp, DuetService::class.java)) }
            }
        }
    }
}
