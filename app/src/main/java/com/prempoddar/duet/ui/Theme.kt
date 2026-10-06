package com.prempoddar.duet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prempoddar.duet.R
import com.prempoddar.duet.ui.design.AppTheme
import com.prempoddar.duet.ui.design.Appearance
import com.prempoddar.duet.ui.design.isDark

/**
 * Duet's named colours, taken from the theme's colour roles so they follow light, dark, black,
 * the chosen accent and the wallpaper — and always contrast with what they sit on.
 */
data class Warm(val paper: Color, val card: Color, val ink: Color, val muted: Color, val brick: Color, val amber: Color, val record: Color, val dark: Boolean)

/** Duet's own colour: brick, a living room in the evening. */
const val DUET_BRAND = 0xC8553D

val LocalWarm = staticCompositionLocalOf { Warm(Color.White, Color.White, Color.Black, Color.Gray, Color.Red, Color.Yellow, Color.Black, false) }

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
fun DuetTheme(appearance: Appearance, content: @Composable () -> Unit) {
    AppTheme(appearance, DUET_BRAND, Type) {
        val c = MaterialTheme.colorScheme
        val dark = isDark(appearance)
        val warm = Warm(
            paper = c.surface, card = c.surfaceContainer, ink = c.onSurface, muted = c.onSurfaceVariant,
            brick = c.primary, amber = c.tertiary,
            // The record stays black vinyl in every theme.
            record = Color(0xFF1C1512), dark = dark,
        )
        CompositionLocalProvider(LocalWarm provides warm, content = content)
    }
}
