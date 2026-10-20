package com.prempoddar.duet.play

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.prempoddar.duet.core.TrackMeta

/** Needed only so Android lets Duet see other apps' players; it reads no notifications. */
class MediaAccess : NotificationListenerService()

/**
 * Playing along with Spotify, Apple Music, YouTube Music and the like: each phone plays the song
 * in its own app with its own account, and Duet lines them up through the apps' own controls.
 */
object OtherApps {
    fun hasAccess(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun accessSettings() = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun controllers(context: Context): List<MediaController> = runCatching {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, MediaAccess::class.java))
    }.getOrDefault(emptyList()).filter { it.packageName != context.packageName }

    data class Playing(val app: String, val appName: String, val title: String, val artist: String, val durationMs: Long, val positionMs: Long, val playing: Boolean)

    /** What other apps are playing (or have paused), most active first. */
    fun playing(context: Context): List<Playing> = controllers(context).mapNotNull { c ->
        val m = c.metadata ?: return@mapNotNull null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Playing(
            c.packageName, appName(context, c.packageName), title,
            m.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            m.getLong(MediaMetadata.METADATA_KEY_DURATION), position(c), c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }.sortedByDescending { it.playing }

    fun appName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Where a controller's player is now: its last report carried forward while playing. */
    private fun position(c: MediaController): Long {
        val s = c.playbackState ?: return 0
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    /** The queue entry for what [p] is playing. */
    fun track(p: Playing, owner: String) = TrackMeta(
        id = java.security.MessageDigest.getInstance("SHA-256").digest("app:${p.app}:${p.title}:${p.artist}".toByteArray()).joinToString("") { "%02x".format(it) }.take(16),
        title = p.title, artist = p.artist, durationMs = p.durationMs, mime = "external/${p.app}", size = 0, owner = owner, digest = "", external = p.app,
    )

    /**
     * Asks a music app to find and play [track] — the same app if it's installed, else whichever
     * app handles "play from search". Returns false if no app can.
     */
    fun playInMyApp(context: Context, track: TrackMeta): Boolean {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_TITLE, track.title)
            .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sameApp = Intent(search).setPackage(track.external)
        return listOf(sameApp, search).any { runCatching { context.startActivity(it) }.isSuccess }
    }

    /**
     * Brings this phone's copy of [track] (the same song in the same app) to [targetMs], playing or
     * paused like everyone else. Jumps only when more than [toleranceMs] out, so it doesn't stutter.
     * Returns how far off it was, or null if this phone isn't playing that song.
     */
    fun align(context: Context, track: TrackMeta, targetMs: Long, playing: Boolean, toleranceMs: Long = 1_200): Long? {
        val c = controllers(context).firstOrNull { c ->
            c.packageName == track.external && c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.equals(track.title, ignoreCase = true) == true
        } ?: return null
        val isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing && !isPlaying) c.transportControls.play()
        if (!playing && isPlaying) c.transportControls.pause()
        val drift = targetMs - position(c)
        if (kotlin.math.abs(drift) > toleranceMs) c.transportControls.seekTo(targetMs.coerceAtLeast(0))
        return drift
    }
}
