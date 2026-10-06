package com.prempoddar.duet.ui

import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette

/** Album art from a track's base64 cover. */
fun decodeArt(art: String?): ImageBitmap? = art?.let {
    runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
}

/** The cover's warmest strong colour, for the room's glow. */
fun glowOf(art: ImageBitmap?, fallback: Color): Color {
    val bmp = art ?: return fallback
    val p = Palette.from(bmp.asAndroidBitmap()).generate()
    return (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb?.let(::Color) ?: fallback
}

/**
 * The signature: a record that turns at 33⅓ while the song plays, easing to a stop on pause, with
 * the album cover as its label.
 */
@Composable
fun Vinyl(art: ImageBitmap?, playing: Boolean, label: String, modifier: Modifier = Modifier) {
    val warm = LocalWarm.current
    val rpm by animateFloatAsState(if (playing) 33.3f else 0f, tween(if (playing) 900 else 1600), label = "rpm")
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle = (angle + rpm * 6f * (now - last) / 1e9f) % 360f
                last = now
            }
        }
    }
    BoxWithConstraints(modifier.aspectRatio(1f).semantics { contentDescription = if (playing) "Record playing" else "Record stopped" }, contentAlignment = Alignment.Center) {
        val size = maxWidth
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                drawCircle(warm.record, r)
                // Grooves.
                var g = r * 0.97f
                while (g > r * 0.38f) {
                    drawCircle(Color.White.copy(alpha = 0.035f), g, style = Stroke(1f))
                    g -= r * 0.022f
                }
                // A soft sheen that turns with the record.
                drawCircle(
                    Brush.sweepGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                        center,
                    ),
                    r * 0.96f,
                )
            }
            Box(Modifier.size(size * 0.36f).clip(CircleShape).background(warm.brick), contentAlignment = Alignment.Center) {
                if (art != null) {
                    Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(label.take(1).uppercase(), fontFamily = Fraunces, fontSize = (size.value * 0.12f).sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Box(Modifier.size(size * 0.03f).clip(CircleShape).background(warm.paper))
        }
    }
}

/** Fine film grain over the whole screen, generated once. */
@Composable
fun grainBrush(): ShaderBrush {
    val dark = LocalWarm.current.dark
    return remember(dark) {
        val n = 160
        val pixels = IntArray(n * n) {
            val v = (Math.random() * 255).toInt()
            val a = (Math.random() * (if (dark) 18 else 14)).toInt()
            (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, n, n, android.graphics.Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
}

/** The room: paper colour lit from above by the album's glow, with grain. */
@Composable
fun WarmBackground(glow: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val warm = LocalWarm.current
    Box(
        modifier.fillMaxSize().background(warm.paper)
            .background(Brush.radialGradient(listOf(glow.copy(alpha = if (warm.dark) 0.35f else 0.28f), Color.Transparent), center = Offset(540f, 300f), radius = 1400f))
            .background(grainBrush()),
    ) { content() }
}

@Composable
fun SmallVinyl(modifier: Modifier = Modifier) = Vinyl(null, playing = false, label = "D", modifier = modifier.fillMaxWidth())
