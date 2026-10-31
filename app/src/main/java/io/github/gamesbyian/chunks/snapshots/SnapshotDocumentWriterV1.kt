package io.github.gamesbyian.chunks.snapshots

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

/**
 * Legacy SAF CreateDocument writer, retired under storage G0 #809.
 *
 * A picker-returned content URI is NOT exclusive-creation proof. Opening it in
 * "wt" could truncate a provider-returned existing document. No generic SAF
 * document writer may open it for mutation until a source-independent
 * ownership-bound adapter with no-replace publication has been accepted.
 *
 * User-facing report/snapshot export now prepares an exclusively created,
 * app-private, read-only share via OwnedDiagnosticExportProvider. The receiving
 * app, selected explicitly in Android's share UI, owns any final Save action.
 */
internal object SnapshotDocumentWriterV1 {
    private const val MAX_BYTES = 64 * 1024 * 1024

    fun writeAndVerify(resolver: ContentResolver, target: Uri, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "Snapshot is empty or exceeds the document limit"
        }
        throw IOException("Unproven SAF document ownership; direct write refused")
    }
}
