package io.github.gamesbyian.chunks

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.model.ZipArchiveInventory
import io.github.gamesbyian.chunks.model.ZipPayloadStatus
import io.github.gamesbyian.chunks.model.ZipPayloadVerification
import io.github.gamesbyian.chunks.model.ZipPayloadVerifier
import io.github.gamesbyian.chunks.model.ZipPreflightResult
import io.github.gamesbyian.chunks.model.ZipPreflightStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result contains only bounded metadata. The temporary ZIP is never retained. */
internal data class LocalZipReview(
    val label: String,
    val stagedBytes: Long,
    val inventory: ZipPreflightResult? = null,
    val verification: ZipPayloadVerification? = null,
    val failure: String? = null
)

/**
 * An Android document-URI bridge for the existing conservative ZIP verifier.
 * No filesystem destination, extraction, remote request or torrent mutation.
 *
 * The caller supplies a user-selected content URI. Copying to a new app-private
 * temporary file prevents a provider changing archive bytes between central
 * directory inspection and streaming verification. Bound bytes while copying
 * even when providers lie about their length or stream indefinitely.
 */
internal object LocalZipDocumentInspector {
    const val MAX_INPUT_BYTES = ZipArchiveInventory.DEFAULT_MAX_INPUT_BYTES

    fun inspect(context: Context, uri: Uri, maxInputBytes: Long = MAX_INPUT_BYTES): LocalZipReview {
        require(maxInputBytes in 1..MAX_INPUT_BYTES)
        if (uri.scheme != "content")
            return LocalZipReview("Selected document", 0,
                failure = "Choose a document using Android's file picker.")

        val resolver = context.contentResolver
        val label = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) cursor.getString(column) else null
                } else null
            }
        } catch (_: Exception) { null }
        val safeLabel = label?.take(160)?.filter { !it.isISOControl() && it != '/' && it != '\\' }
            ?.trim()?.takeIf { it.isNotBlank() } ?: "Selected document"

        var temporary: File? = null
        var copied = 0L
        try {
            temporary = File.createTempFile("chunks-zip-review-", ".zip", context.cacheDir)
            val staged = temporary
            val stream = resolver.openInputStream(uri)
                ?: return LocalZipReview(safeLabel, 0, failure = "Android could not open this document.")
            stream.use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted)
                            return LocalZipReview(safeLabel, copied, failure = "Review interrupted.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0)
                            return LocalZipReview(safeLabel, copied, failure = "Document stream made no progress.")
                        if (count.toLong() > maxInputBytes - copied)
                            return LocalZipReview(safeLabel, copied,
                                failure = "Local ZIP exceeds the 256 MiB review input limit.")
                        output.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            val inventory = ZipArchiveInventory.inspect(staged, maxInputBytes = maxInputBytes)
            if (inventory.status == ZipPreflightStatus.BLOCKED)
                return LocalZipReview(safeLabel, copied, inventory = inventory)
            val verified = ZipPayloadVerifier.verify(staged)
            return LocalZipReview(safeLabel, copied, inventory, verified)
        } catch (_: IOException) {
            return LocalZipReview(safeLabel, copied,
                failure = "This document could not be read completely.")
        } catch (_: SecurityException) {
            return LocalZipReview(safeLabel, copied,
                failure = "Android did not grant access to this document.")
        } finally {
            // Only the randomized app-private staging copy is ever deleted.
            // The original SAF document, torrent bytes and external files stay untouched.
            temporary?.delete()
        }
    }
}

@Composable
internal fun LocalZipReviewScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<LocalZipReview?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            review = null
            busy = true
            scope.launch {
                // Fail closed on unexpected providers, but don't print private URI
                // or local file paths in the interface or a public diagnostic.
                val result = withContext(Dispatchers.IO) {
                    try { LocalZipDocumentInspector.inspect(context, selected) }
                    catch (_: Exception) {
                        LocalZipReview("Selected document", 0,
                            failure = "This document could not be inspected.")
                    }
                }
                review = result
                busy = false
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) item {
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Back")
            }
        }
        item {
            Text("Local ZIP review", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
        }
        item {
            Text("Choose a ZIP saved on this phone. Chunks checks archive paths, member types and actual decompressed bytes before any extraction feature is offered. Nothing is extracted or deleted from your selected file.")
        }
        item {
            Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (busy) "Checking ZIP…" else "Choose local ZIP")
            }
        }
        item {
            Text("Read-only limits: up to 256 MiB ZIP input and 64 MiB expanded output. Larger or unsupported archives are not verified. The private temporary copy is discarded after the review.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (review != null) item { LocalZipReviewResults(requireNotNull(review)) }
    }
}

@Composable
internal fun LocalZipReviewResults(review: LocalZipReview) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(review.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Copied for inspection: " + Units.bytes(review.stagedBytes))
            val preflight = review.inventory
            when {
                review.failure != null -> Text("Not verified: " + review.failure,
                    color = MaterialTheme.colorScheme.error)
                preflight == null -> Text("No ZIP result available.",
                    color = MaterialTheme.colorScheme.error)
                preflight.status == ZipPreflightStatus.BLOCKED ->
                    Text("Unsafe or unsupported ZIP: " + preflight.reasons.joinToString("; "),
                        color = MaterialTheme.colorScheme.error)
                review.verification?.status == ZipPayloadStatus.VERIFIED_READ_ONLY -> {
                    val outcome = requireNotNull(review.verification)
                    Text("Read-only integrity checks passed for " + outcome.verifiedFiles +
                        " files (" + Units.bytes(outcome.actualOutputBytes ?: 0L) +
                        " decompressed).", color = MaterialTheme.colorScheme.primary)
                }
                else -> Text("ZIP not verified: " +
                    (review.verification?.reason ?: "No streamed integrity result"),
                    color = MaterialTheme.colorScheme.error)
            }
            if (preflight != null && preflight.status != ZipPreflightStatus.BLOCKED) {
                Text("Contents preview: " + preflight.fileCount + " files")
                preflight.names.take(8).forEach { name ->
                    Text(name.take(160), style = MaterialTheme.typography.bodySmall)
                }
                if (preflight.names.size > 8) {
                    Text("And " + (preflight.names.size - 8) + " more entries…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("A successful review does not authorize extraction, deletion, or changes to seeding. Archives stored on a remote rTorrent server are not accessible through this phone-only picker.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
