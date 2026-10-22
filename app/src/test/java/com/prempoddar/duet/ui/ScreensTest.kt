package com.prempoddar.duet.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
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
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)) {
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

    @Test fun joinDark() = shoot("join-dark", dark) { JoinScreen(DuetSettings(), initial = "apple bana", onBack = {}) }

    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun homeLandscape() = shoot("home-land", dark) { HomeScreen(DuetSettings(name = "Prem"), {}, {}) }
}
