package io.github.gamesbyian.chunks.exports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** User-initiated handoff only. The receiving app controls any final Save. */
internal object OwnedDiagnosticShare {
    fun openChooser(context: Context, uri: Uri, mime: String, label: String) {
        require(uri.scheme == "content" && uri.authority == OwnedDiagnosticExportProvider.authority(context))
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share $label"))
    }
}
