package io.github.gamesbyian.chunks.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Read only a bounded, existing app-private resume file. File attributes can
 * change between the size probe and open/read, so the stream is independently
 * bounded too. A missing, unreadable, truncated or growing file is not a valid
 * resume witness; never delete or rewrite it during recovery.
 */
internal object LocalResumeFileReader {
    fun read(
        file: File,
        maxBytes: Long,
        open: (File) -> InputStream = { it.inputStream() }
    ): ByteArray? = try {
        if (maxBytes <= 0L || !file.isFile || file.length() !in 1L..maxBytes) null
        else open(file).use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0 || bytes.size().toLong() + count > maxBytes) return null
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
}
