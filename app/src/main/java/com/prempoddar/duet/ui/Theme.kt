package com.prempoddar.duet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prempoddar.duet.R
import com.prempoddar.duet.data.ThemeMode

/** Warm paper, brick and amber — a living room in the evening. */
data class Warm(val paper: Color, val card: Color, val ink: Color, val muted: Color, val brick: Color, val amber: Color, val record: Color, val dark: Boolean)

private val LightWarm = Warm(Color(0xFFF6EFE6), Color(0xFFFFF8F0), Color(0xFF2B1E16), Color(0xFF7A6556), Color(0xFFC8553D), Color(0xFFD9922E), Color(0xFF1C1512), false)
private val DarkWarm = Warm(Color(0xFF17110D), Color(0xFF221A15), Color(0xFFF2E6D8), Color(0xFFB19E8C), Color(0xFFE07A5F), Color(0xFFF0B45A), Color(0xFF0E0A08), true)

val LocalWarm = staticCompositionLocalOf { LightWarm }

@OptIn(ExperimentalTextApi::class)
private fun fraunces(w: Int) = Font(
    R.font.fraunces, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("SOFT", 100f), FontVariation.Setting("opsz", 72f)),
)

@OptIn(ExperimentalTextApi::class)
private fun dmSans(w: Int) = Font(R.font.dm_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Soft, rounded serif for titles; it carries the warmth. */
val Fraunces = FontFamily(fraunces(400), fraunces(600), fraunces(700))
val DmSans = FontFamily(dmSans(400), dmSans(500), dmSans(600), dmSans(700))

private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(600), fontSize = 52.sp, letterSpacing = (-0.02).em, lineHeight = 54.sp),
    displaySmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(600), fontSize = 36.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(600), fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(600), fontSize = 23.sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight(600), fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(600), fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(600), fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(600), fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(600), fontSize = 12.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = DmSans, fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 0.08.em),
)

@Composable
fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DuetTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val w = if (isDark(mode)) DarkWarm else LightWarm
    val scheme = if (w.dark) {
        darkColorScheme(
            primary = w.brick, onPrimary = Color(0xFF2A0F08), primaryContainer = Color(0xFF4A2A20), onPrimaryContainer = w.ink,
            secondary = w.amber, background = w.paper, onBackground = w.ink, surface = w.card, onSurface = w.ink,
            surfaceVariant = Color(0xFF2C221C), onSurfaceVariant = w.muted, surfaceContainer = w.card, surfaceContainerHigh = Color(0xFF2C221C),
            surfaceContainerLow = w.card, outline = Color(0xFF4A3B31), outlineVariant = Color(0xFF332820),
        )
    } else {
        lightColorScheme(
            primary = w.brick, onPrimary = Color.White, primaryContainer = Color(0xFFF6D8CC), onPrimaryContainer = Color(0xFF4A1A0F),
            secondary = w.amber, background = w.paper, onBackground = w.ink, surface = w.card, onSurface = w.ink,
            surfaceVariant = Color(0xFFEDE2D5), onSurfaceVariant = w.muted, surfaceContainer = w.card, surfaceContainerHigh = Color(0xFFF2E8DC),
            surfaceContainerLow = w.card, outline = Color(0xFFD7C7B7), outlineVariant = Color(0xFFE6D9CB),
        )
    }
    CompositionLocalProvider(LocalWarm provides w) {
        MaterialTheme(scheme, typography = Type, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
    }
}
