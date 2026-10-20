package com.prempoddar.duet.core

import java.security.MessageDigest

/** YouTube links and the queue entries made from them. */
object YouTube {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * The video id in a YouTube link, whichever form was shared: watch pages, youtu.be, Shorts,
     * YouTube Music, embeds and live links, with or without extra parameters. Null if none.
     */
    fun videoId(link: String): String? {
        // Keyboards like to put a space after every dot; links never have spaces.
        val text = link.replace(Regex("\\s+"), "")
        if (ID.matches(text)) return text
        val url = runCatching { java.net.URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = url.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = url.path.orEmpty().trim('/').split('/')
        val id = when {
            host == "youtu.be" -> path.firstOrNull()
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> when (path.firstOrNull()) {
                "watch" -> url.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                "shorts", "embed", "live", "v", "e" -> path.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** The playlist in a YouTube link (playlist pages, or a video shared from one), or null. */
    fun playlistId(link: String): String? {
        val text = link.replace(Regex("\\s+"), "")
        val url = runCatching { java.net.URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = url.host?.lowercase() ?: return null
        if (!host.endsWith("youtube.com") && host != "youtu.be") return null
        val list = url.rawQuery?.split('&')?.firstOrNull { it.startsWith("list=") }?.removePrefix("list=") ?: return null
        return list.takeIf { Regex("^[A-Za-z0-9_-]{10,64}$").matches(it) }
    }

    /** Most videos taken from one playlist, so a huge one doesn't flood the queue. */
    const val PLAYLIST_LIMIT = 100

    /** A queue entry for [videoId]; its id is derived from the video, so adding it twice is the same entry. */
    fun track(videoId: String, title: String, channel: String, owner: String) = TrackMeta(
        id = MessageDigest.getInstance("SHA-256").digest("youtube:$videoId".toByteArray()).joinToString("") { "%02x".format(it) }.take(16),
        title = title.ifBlank { "YouTube video" }.take(200),
        artist = channel.take(120),
        durationMs = 0,
        mime = "video/youtube",
        size = 0,
        owner = owner,
        digest = "",
        youtube = videoId,
    )
}
