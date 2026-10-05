package com.prempoddar.duet.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.core.SessionCode
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.session.Phase

@Composable
fun DuetNav(settings: DuetSettings, linkCode: SessionCode?, onLinkUsed: () -> Unit) {
    val nav = rememberNavController()
    val phase by AppGraph.sessions.phase.collectAsStateWithLifecycle()

    // Being in a session decides the main screen; everything else stacks on top.
    LaunchedEffect(phase is Phase.Live) {
        if (phase is Phase.Live) nav.navigate("session") { popUpTo("home"); launchSingleTop = true }
        else if (nav.currentDestination?.route in setOf("session", "library")) nav.popBackStack("home", inclusive = false)
    }
    LaunchedEffect(linkCode) {
        if (linkCode != null) {
            nav.navigate("join?code=${linkCode.words.joinToString("-")}") { launchSingleTop = true }
            onLinkUsed()
        }
    }
    NavHost(nav, startDestination = "home", enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
        composable("home") {
            HomeScreen(
                settings,
                onJoin = { nav.navigate("join?code=") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("join?code={code}") { e ->
            JoinScreen(settings, initial = e.arguments?.getString("code").orEmpty().replace('-', ' '), onBack = { nav.popBackStack() })
        }
        composable("session") { SessionScreen(onAddSong = { nav.navigate("library") }) }
        composable("library") { LibraryScreen(onDone = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
