package com.prempoddar.duet.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.prempoddar.duet.AppGraph
import com.prempoddar.duet.data.DuetSettings
import com.prempoddar.duet.ui.design.Appearance
import com.prempoddar.duet.ui.design.AppearanceScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws real screens to PNGs in build/screens so the design can be checked in light, dark and
 * landscape without a phone. Fails only if a screen can't be drawn at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
open class ScreensTest {
    /** Where the pictures go; the tablet runs below draw the same screens into their own folders. */
    protected open val out = "build/screens"

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext())
    }

    private fun shoot(name: String, appearance: Appearance, content: @Composable () -> Unit) {
        // Spinning records never settle: step the clock by hand instead of waiting for idle.
        rule.mainClock.autoAdvance = false
        rule.setContent { DuetTheme(appearance, content) }
        rule.mainClock.advanceTimeBy(1_000)
        val dir = File(out).apply { mkdirs() }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val light = Appearance(mode = Appearance.Mode.LIGHT)
    private val dark = Appearance(mode = Appearance.Mode.DARK)

    @Test fun homeLight() = shoot("home-light", light) { HomeScreen(DuetSettings(name = "Prem"), {}, {}) }
    @Test fun homeDark() = shoot("home-dark", dark) { HomeScreen(DuetSettings(name = "Prem"), {}, {}) }
    @Test fun settingsDark() = shoot("settings-dark", dark) { SettingsScreen({}, {}, {}) }
    @Test fun appearanceLight() = shoot("appearance-light", light) { AppearanceScreen(light, DUET_BRAND, {}, {}) }
    private fun song(id: String, title: String, artist: String, ms: Long, youtube: String? = null) =
        com.prempoddar.duet.core.TrackMeta(id.padEnd(16, '0'), title, artist, durationMs = ms, mime = "audio/mpeg", size = 1, owner = "x", digest = "", youtube = youtube)

    @Test fun queueDark() = shoot("queue-dark", dark) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 24.dp)) {
            QueueSheet(
                listOf(
                    song("a1", "Golden Hour", "The Lanterns", 214_000), song("a2", "Slow Tides", "Mira Vale", 248_000),
                    song("a3", "Paper Planes", "June & the Coast", 197_000), song("a4", "Night Drive", "Neon Coast", 0, youtube = "x"),
                    song("a5", "Late Bloom", "Ola Reyes", 231_000),
                ),
                index = 1, votes = mapOf("a4".padEnd(16, '0') to listOf("p", "q")), playing = true,
            )
        }
    }

    /** A session in full swing: a song playing for three people, with chat waiting. Made-up data. */
    private fun liveSession() {
        val sessions = AppGraph.sessions
        fun <T> flow(name: String) = @Suppress("UNCHECKED_CAST") (sessions.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(sessions) as kotlinx.coroutines.flow.MutableStateFlow<T>)
        val cover = javaClass.classLoader!!.getResourceAsStream("media/cover.jpg")!!.use { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }
        val playing = com.prempoddar.duet.core.TrackMeta("a1".padEnd(16, '0'), "Night Drive", "Neon Coast", durationMs = 215_000, mime = "audio/mpeg", size = 1, owner = "x", art = cover, digest = "",
            lyrics = "[00:58.00]Headlights on the open road\n[01:04.00]Singing every word we know\n[01:10.00]Night drive, you and me")
        // The song is already on this phone, so the screen shows it playing rather than arriving.
        sessions.store.adopt(playing.id, File.createTempFile("song", ".mp3").apply { writeText("x") })
        flow<com.prempoddar.duet.core.PlaybackState>("_state").value = com.prempoddar.duet.core.PlaybackState(
            queue = listOf(playing, song("a2", "Slow Tides", "Mira Vale", 248_000), song("a3", "Paper Planes", "June & the Coast", 197_000)),
            index = 0, positionMs = 74_000, anchor = 0, playing = true,
        )
        flow<List<com.prempoddar.duet.core.Member>>("_members").value = listOf(
            com.prempoddar.duet.core.Member("h", "Asha", isHost = true, ready = true), com.prempoddar.duet.core.Member("b", "Ben", isHost = false, ready = true),
        )
        flow<List<com.prempoddar.duet.core.ChatLine>>("_chat").value = listOf(
            com.prempoddar.duet.core.ChatLine("c1", "h", "Asha", "this one!! \uD83D\uDE0D", 1), com.prempoddar.duet.core.ChatLine("c2", "b", "Ben", "turn it up", 2),
        )
    }

    @Test fun sessionDark() { liveSession(); shoot("session-dark", dark) { SessionScreen {} } }
    @Test fun sessionLight() { liveSession(); shoot("session-light", light) { SessionScreen {} } }

    @Test fun joinDark() = shoot("join-dark", dark) { JoinScreen(DuetSettings(), initial = "apple bana", onBack = {}) }

    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun homeLandscape() = shoot("home-land", dark) { HomeScreen(DuetSettings(name = "Prem"), {}, {}) }
}
