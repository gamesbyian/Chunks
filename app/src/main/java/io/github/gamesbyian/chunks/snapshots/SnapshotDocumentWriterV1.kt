package io.github.gamesbyian.chunks.snapshots

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.security.MessageDigest

/**
 * Android document-provider write-and-readback acceptance for a newly created
 * user-chosen .chunksnap. Never writes to the torrent engine's backing files.
 *
 * Providers may refuse readback: in that case the UI MUST report unverified
 * output. A completed write alone is not permission to claim a saved backup.
 * No automatic delete: keep the created document for user-driven inspection.
 */
internal object SnapshotDocumentWriterV1 {
    private const val MAX_BYTES = 64 * 1024 * 1024

    fun writeAndVerify(resolver: ContentResolver, target: Uri, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "Snapshot is empty or exceeds the document limit"
        }
        val out = resolver.openOutputStream(target, "wt")
            ?: throw IOException("The document provider refused to create a writable file")
        out.use { output ->
            output.write(bytes)
            output.flush()
        }
        // Reopen the exact document Uri, rather than trusting the successful
        // close() response alone. Bounded stream comparison avoids trusting
        // provider-supplied file length or a maliciously large readback.
        val input = resolver.openInputStream(target)
            ?: throw IOException("The saved document could not be reopened for verification")
        input.use { stream ->
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                total += count
                if (total > MAX_BYTES || total > bytes.size) {
                    throw IOException("The document provider returned excessive snapshot bytes")
                }
                digest.update(buffer, 0, count)
            }
            if (total != bytes.size || !digest.digest().contentEquals(expectedHash)) {
                throw IOException("Saved snapshot readback differs from source; do not trust this backup")
            }
        }
    }
}
