package com.prempoddar.duet.data

import com.prempoddar.duet.core.TrackMeta
import com.prempoddar.duet.core.TrackStore
import com.prempoddar.duet.core.Wire
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Songs for the current session, in the app's cache. Cleared when the session ends. */
class FileTrackStore(private val dir: File) : TrackStore {
    private val received = HashMap<String, BooleanArray>()

    init { dir.mkdirs() }

    fun file(id: String): File? = File(dir, safe(id)).takeIf { it.isFile }

    /** Puts an imported file in place under its id. */
    @Synchronized fun adopt(id: String, from: File) {
        val target = File(dir, safe(id))
        if (!target.exists()) from.renameTo(target) || from.copyTo(target, overwrite = true).let { from.delete() }
        else from.delete()
    }

    @Synchronized override fun has(id: String) = file(id) != null
    @Synchronized override fun size(id: String) = file(id)?.length()

    @Synchronized override fun read(id: String, offset: Long, length: Int): ByteArray? {
        val f = file(id) ?: return null
        if (offset >= f.length()) return null
        return RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            val out = ByteArray(minOf(length.toLong(), f.length() - offset).toInt())
            raf.readFully(out)
            out
        }
    }

    @Synchronized override fun receive(meta: TrackMeta, index: Int, total: Int, bytes: ByteArray): Boolean {
        if (has(meta.id)) return true
        if (meta.size > MAX_BYTES || total != Wire.chunks(meta.size)) return false
        val marks = received.getOrPut(meta.id) { BooleanArray(total) }
        if (marks.size != total || index !in marks.indices) return false
        val part = File(dir, safe(meta.id) + ".part")
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(index.toLong() * Wire.CHUNK)
            raf.write(bytes)
        }
        marks[index] = true
        if (!marks.all { it }) return false
        received.remove(meta.id)
        val ok = part.length() == meta.size && digest(part) == meta.digest
        if (!ok) {
            part.delete()
            return false
        }
        part.renameTo(File(dir, safe(meta.id)))
        return true
    }

    @Synchronized override fun progress(id: String): Float =
        if (has(id)) 1f else received[id]?.let { m -> m.count { it }.toFloat() / m.size } ?: 0f

    @Synchronized fun clear() {
        received.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() }.take(32)

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024

        fun digest(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
