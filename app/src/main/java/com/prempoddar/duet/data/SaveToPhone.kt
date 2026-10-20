package com.prempoddar.duet.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.prempoddar.duet.core.TrackMeta
import java.io.File

/**
 * Keeps a song from the session on this phone, in Music/Duet, where every music app finds it.
 * Songs only live in Duet's cache during a session otherwise. No storage permission is needed.
 */
object SaveToPhone {
    /** A file name people recognise: "Artist - Title.mp3", minus characters storage won't take. */
    fun fileName(meta: TrackMeta): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(meta.mime) ?: "mp3"
        val base = listOf(meta.artist, meta.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Duet song" }
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120) + "." + ext
    }

    /** Copies [file] in; true if it's now on the phone. */
    fun save(context: Context, meta: TrackMeta, file: File): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName(meta))
            put(MediaStore.Audio.Media.MIME_TYPE, meta.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Duet")
            put(MediaStore.Audio.Media.TITLE, meta.title)
            if (meta.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, meta.artist)
            if (meta.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, meta.album)
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }.getOrDefault(false)
}
