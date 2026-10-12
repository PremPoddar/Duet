package com.prempoddar.duet.ui

import androidx.compose.runtime.rememberCoroutineScope
import com.prempoddar.duet.R
import com.prempoddar.duet.ui.design.AboutScreen
import com.prempoddar.duet.ui.design.AppearanceScreen
import com.prempoddar.duet.ui.design.Credit
import com.prempoddar.duet.ui.design.LayeredIcon
import kotlinx.coroutines.launch

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
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onAppearance = { nav.navigate("appearance") }, onAbout = { nav.navigate("about") }) }
        composable("appearance") {
            val scope = rememberCoroutineScope()
            AppearanceScreen(settings.appearance, DUET_BRAND, onChange = { a -> scope.launch { AppGraph.settings.update { it.copy(appearance = a) } } }, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(
                "Duet", "Same song, same moment — wherever you both are.",
                icon = { LayeredIcon(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground) },
                credits = listOf(
                    Credit("WebRTC (Stream build)", "BSD licence"), Credit("HiveMQ MQTT client", "Apache 2.0"),
                    Credit("Media3 ExoPlayer", "Apache 2.0"), Credit("ZXing", "Apache 2.0"),
                    Credit("Fraunces and DM Sans", "SIL Open Font License"), Credit("BIP-39 word list", "MIT"),
                ),
                onBack = { nav.popBackStack() },
                notes = listOf(
                    "How Duet connects" to "Phones connect to each other directly. To find each other across the internet they swap a few encrypted messages through a free public relay (HiveMQ); it only ever sees scrambled data, never your music.",
                    "On the same Wi-Fi" to "No internet is needed at all: phones find each other and play straight from one another.",
                    "Your songs" to "Songs are copied to listeners only for the session and deleted from their phones when it ends.",
                ),
            )
        }
    }
}
