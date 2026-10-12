package com.prempoddar.duet.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.prempoddar.duet.ui.design.Appearance
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where sound comes out; each has its own delay, Bluetooth most of all. */
enum class Output { SPEAKER, WIRED, BLUETOOTH }

data class DuetSettings(
    val name: String = "",
    val appearance: Appearance = Appearance(),
    val nudgeSpeaker: Int = 0,
    val nudgeWired: Int = 0,
    val nudgeBluetooth: Int = 180,
) {
    fun nudge(o: Output) = when (o) {
        Output.SPEAKER -> nudgeSpeaker
        Output.WIRED -> nudgeWired
        Output.BLUETOOTH -> nudgeBluetooth
    }

    fun withNudge(o: Output, ms: Int) = when (o) {
        Output.SPEAKER -> copy(nudgeSpeaker = ms)
        Output.WIRED -> copy(nudgeWired = ms)
        Output.BLUETOOTH -> copy(nudgeBluetooth = ms)
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("name")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = intPreferencesKey("accent")
        val black = booleanPreferencesKey("pure_black")
        val speaker = intPreferencesKey("nudge_speaker")
        val wired = intPreferencesKey("nudge_wired")
        val bt = intPreferencesKey("nudge_bt")
    }

    val settings: Flow<DuetSettings> = context.dataStore.data.map { it.toSettings() }
    suspend fun current() = settings.first()

    suspend fun update(f: (DuetSettings) -> DuetSettings) {
        context.dataStore.edit { p ->
            val n = f(p.toSettings())
            p[K.name] = n.name; p[K.theme] = n.appearance.mode.name
            p[K.dynamic] = n.appearance.dynamic; p[K.black] = n.appearance.pureBlack
            n.appearance.accent?.let { p[K.accent] = it } ?: p.remove(K.accent)
            p[K.speaker] = n.nudgeSpeaker; p[K.wired] = n.nudgeWired; p[K.bt] = n.nudgeBluetooth
        }
    }

    private fun Preferences.toSettings(): DuetSettings {
        val d = DuetSettings()
        return DuetSettings(
            name = this[K.name] ?: d.name,
            appearance = Appearance(
                mode = this[K.theme]?.let { runCatching { Appearance.Mode.valueOf(it) }.getOrNull() } ?: Appearance.Mode.SYSTEM,
                dynamic = this[K.dynamic] ?: false,
                accent = this[K.accent],
                pureBlack = this[K.black] ?: false,
            ),
            nudgeSpeaker = this[K.speaker] ?: d.nudgeSpeaker,
            nudgeWired = this[K.wired] ?: d.nudgeWired,
            nudgeBluetooth = this[K.bt] ?: d.nudgeBluetooth,
        )
    }
}
