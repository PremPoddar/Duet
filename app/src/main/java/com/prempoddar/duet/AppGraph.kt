package com.prempoddar.duet

import android.app.Application
import com.prempoddar.duet.data.SettingsRepository
import com.prempoddar.duet.play.PlayerController
import com.prempoddar.duet.session.SessionManager

object AppGraph {
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    val settings by lazy { SettingsRepository(app) }
    val sessions by lazy { SessionManager(app) }
    /** Created on the main thread (ExoPlayer requires it). */
    val player by lazy { PlayerController(app, sessions) }
}
