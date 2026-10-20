package com.prempoddar.duet.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import com.prempoddar.duet.core.TrackMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class LibrarySong(val uri: Uri, val title: String, val artist: String, val album: String, val durationMs: Long, val size: Long)

/** The music on this phone, and turning a song into something the session can share. */
object Library {
    suspend fun songs(context: Context): List<LibrarySong> = withContext(Dispatchers.IO) {
        val out = ArrayList<LibrarySong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += LibrarySong(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: "Unknown", c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                        c.getString(3) ?: "", c.getLong(4), c.getLong(5),
                    )
                }
            }
        }
        out
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()

    class TooBig : Exception("Songs can be up to 50 MB")

    /** Copies a song into the session's store (named by its content hash) and describes it. */
    /**
     * A .lrc file with the song's name in the same folder (where Android lets us read it), else
     * lyrics stored in the song's own tag.
     */
    private fun lyricsFor(context: Context, uri: Uri, file: File?): String? {
        val sidecar = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.let { path -> File(path.substringBeforeLast('.') + ".lrc").takeIf { it.canRead() }?.readText() }
        }.getOrNull()
        if (!sidecar.isNullOrBlank()) return sidecar
        val head = file?.let { f -> runCatching { f.inputStream().use { it.readNBytes(512 * 1024) } }.getOrNull() } ?: return null
        return com.prempoddar.duet.core.Lyrics.fromId3(head)
    }

    suspend fun import(context: Context, uri: Uri, store: FileTrackStore, owner: String): TrackMeta = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}")
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > FileTrackStore.MAX_BYTES) {
                        tmp.delete()
                        throw TooBig()
                    }
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        val id = digest.take(16)
        store.adopt(id, tmp)

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val name = displayName(context, uri)?.substringBeforeLast('.') ?: "Untitled"
            TrackMeta(
                id = id,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: name,
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                mime = context.contentResolver.getType(uri) ?: "audio/mpeg",
                size = size,
                owner = owner,
                art = r.embeddedPicture?.let(::smallArt),
                digest = digest,
                lyrics = lyricsFor(context, uri, store.file(id))?.take(com.prempoddar.duet.core.Lyrics.MAX_CHARS),
            )
        } finally {
            r.release()
        }
    }

    /** A small cover for the queue and the record label, sent ahead of the file. */
    private fun smallArt(bytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = 240f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
