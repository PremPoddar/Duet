package com.prempoddar.duet.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
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
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext())
    }

    private fun shoot(name: String, appearance: Appearance, content: @Composable () -> Unit) {
        // Spinning records never settle: step the clock by hand instead of waiting for idle.
        rule.mainClock.autoAdvance = false
        rule.setContent { DuetTheme(appearance, content) }
        rule.mainClock.advanceTimeBy(1_000)
        val dir = File("build/screens").apply { mkdirs() }
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
    @Test fun joinDark() = shoot("join-dark", dark) { JoinScreen(DuetSettings(), initial = "apple bana", onBack = {}) }

    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun homeLandscape() = shoot("home-land", dark) { HomeScreen(DuetSettings(name = "Prem"), {}, {}) }
}
