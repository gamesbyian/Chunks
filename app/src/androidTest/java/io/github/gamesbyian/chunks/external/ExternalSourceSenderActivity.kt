package io.github.gamesbyian.chunks.external

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle

/**
 * Installed in the androidTest APK, not Chunks. The real Android system
 * launches this Activity under the test APK's own UID. Only that UID creates
 * the source document and sends a one-time URI grant to Chunks.
 *
 * It never has access to Chunks' account settings or torrent engine, and
 * neither Open With nor Share authorizes Add/Start.
 */
class ExternalSourceSenderActivity : Activity() {
    companion object {
        const val MODE = "fixture_mode"
        const val VIEW = "view"
        const val SHARE = "share"
        const val CLEANUP = "cleanup"
        const val TARGET = "fixture_target"
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val kind = intent.getStringExtra(MODE)
        if (kind == CLEANUP) {
            for (slot in listOf("first", "second"))
                ExternalTorrentTestProvider.file(this, slot).delete()
            finish()
            return
        }
        val slot = when (kind) {
            VIEW -> "first"
            SHARE -> "second"
            else -> { finish(); return }
        }
        val target = intent.getStringExtra(TARGET)?.takeIf {
            it.startsWith("io.github.gamesbyian.chunks") && !it.endsWith(".test")
        } ?: run { finish(); return }
        val name = if (slot == "first") "external-first.bin" else "external-second.bin"
        ExternalTorrentTestProvider.file(this, slot).writeBytes(
            ExternalTorrentTestProvider.torrent(name))
        val uri = ExternalTorrentTestProvider.uri(this, slot)
        val handoff = Intent(if (kind == VIEW) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            setClassName(target, "io.github.gamesbyian.chunks.MainActivity")
            type = "application/octet-stream"
            if (kind == VIEW) {
                setDataAndType(uri, "application/octet-stream")
            } else {
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            clipData = ClipData.newUri(contentResolver, "External torrent", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(handoff)
        finish()
    }
}
