package io.github.gamesbyian.chunks

import android.content.Context
import android.net.Uri
import io.github.gamesbyian.chunks.model.ZipArchiveInventory
import io.github.gamesbyian.chunks.model.ZipPayloadStatus
import io.github.gamesbyian.chunks.model.ZipPayloadVerifier
import io.github.gamesbyian.chunks.model.ZipPreflightStatus
import io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublication
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * First deliberately limited #809 production adapter: one regular ZIP member
 * to a *new* owned MediaStore Downloads/Chunks Exports row (Android 10+).
 *
 * No generic SAF tree mutation, replace, rename, deletion, or archive cleanup.
 * The existing MediaStore adapter establishes new-row identity, fsyncs, rereads
 * the exact bytes, publishes and journals uncertain postconditions.
 */
internal object LocalSingleZipMediaStoreExport {
    const val MAX_MEMBER_BYTES = 8 * 1024 * 1024
    private val supportedName =
        Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,155}\\.(json|chunksnap|torrent|txt|bin)")

    enum class State { EXPORTED, BLOCKED, NEEDS_ATTENTION }
    data class Result(
        val state: State,
        val message: String,
        val publishedName: String? = null,
        val publishedUri: Uri? = null
    )

    /** Read-only post-process reconciliation. No row update, deletion or replay. */
    fun unfinishedPublications(context: Context): List<String> {
        val dir = io.github.gamesbyian.chunks.storage.OwnedMediaStorePublicationJournal.journalRoot(context).toFile()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { Regex("[a-f0-9]{32}\\.log").matches(it.name) }
            .map { it.name.removeSuffix(".log") }
            .sorted()
            .map { id ->
                val assessment = io.github.gamesbyian.chunks.storage.OwnedMediaStorePublicationJournal.assess(context, id)
                when (assessment.verdict) {
                    io.github.gamesbyian.chunks.storage.OwnedMediaStorePublicationJournal.Verdict.VERIFIED_PUBLISHED -> null
                    io.github.gamesbyian.chunks.storage.OwnedMediaStorePublicationJournal.Verdict.VERIFIED_PENDING ->
                        "A verified new Downloads file is still pending publication; no automatic action was taken."
                    io.github.gamesbyian.chunks.storage.OwnedMediaStorePublicationJournal.Verdict.NEEDS_ATTENTION ->
                        "An interrupted Downloads export needs review; Chunks did not retry or remove any file."
                }
            }.filterNotNull().toList()
    }

    fun eligible(review: LocalZipReview): Boolean =
        review.inventory?.status != null &&
            review.inventory.status != ZipPreflightStatus.BLOCKED &&
            review.verification?.status == ZipPayloadStatus.VERIFIED_READ_ONLY &&
            review.verification.verifiedFiles == 1 &&
            (review.verification.actualOutputBytes ?: -1L) in 1L..MAX_MEMBER_BYTES.toLong() &&
            review.inventory.names.size == 1 &&
            supportedName.matches(review.inventory.names.single())

    fun extractNew(
        context: Context,
        source: Uri,
        cancelled: () -> Boolean = { false }
    ): Result {
        if (source.scheme != "content" || cancelled())
            return Result(State.BLOCKED, "No export was started.")
        val staged = LocalZipDocumentInspector.stage(context, source)
        return staged.use { owned ->
            val file = owned.file
                ?: return@use Result(State.BLOCKED,
                    owned.failure ?: "The ZIP could not be opened for extraction.")
            // Reinspect the actual newly staged bytes, not the earlier UI review.
            val inventory = ZipArchiveInventory.inspect(file)
            val verified = ZipPayloadVerifier.verify(file)
            if (inventory.status == ZipPreflightStatus.BLOCKED ||
                verified.status != ZipPayloadStatus.VERIFIED_READ_ONLY ||
                verified.verifiedFiles != 1 ||
                (verified.actualOutputBytes ?: -1L) !in 1L..MAX_MEMBER_BYTES.toLong() ||
                inventory.names.size != 1 ||
                !supportedName.matches(inventory.names.single()))
                return@use Result(State.BLOCKED,
                    "This adapter supports only one verified, nonempty, flat .txt, .json, .bin, .torrent or .chunksnap member up to 8 MiB. Nothing was published.")

            val name = inventory.names.single()
            val mime = when (name.substringAfterLast('.')) {
                "txt" -> "text/plain"
                "json" -> "application/json"
                "torrent" -> "application/x-bittorrent"
                else -> "application/octet-stream"
            }
            // The bounded extraction is independently checked against member
            // size and CRC before any user-visible destination row is created.
            val payload = try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    if (!entries.hasMoreElements()) throw IOException("Missing member")
                    val entry = entries.nextElement()
                    if (entries.hasMoreElements() || entry.isDirectory || entry.name != name ||
                        entry.size !in 1L..MAX_MEMBER_BYTES.toLong() ||
                        entry.crc < 0) throw IOException("Unexpected ZIP structure")
                    val output = ByteArrayOutputStream(entry.size.toInt())
                    val buffer = ByteArray(32 * 1024)
                    val crc = CRC32()
                    var countTotal = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            if (cancelled()) throw IOException("Cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0 || count.toLong() > MAX_MEMBER_BYTES - countTotal)
                                throw IOException("ZIP expanded beyond byte budget")
                            output.write(buffer, 0, count)
                            crc.update(buffer, 0, count)
                            countTotal += count
                        }
                    }
                    if (countTotal != entry.size || crc.value != entry.crc)
                        throw IOException("ZIP bytes changed after verification")
                    output.toByteArray()
                }
            } catch (_: Exception) {
                return@use Result(State.BLOCKED,
                    "Extraction was cancelled or the verified ZIP changed. No destination was created.")
            }
            if (cancelled()) return@use Result(State.BLOCKED, "Cancelled before destination creation.")
            try {
                val receipt = OwnedMediaStoreDocumentPublication.publish(
                    context, name, mime, payload, cancelled)
                Result(State.EXPORTED, "Verified new Downloads file; original ZIP preserved.",
                    receipt.publishedName, receipt.uri)
            } catch (_: Exception) {
                // An interrupted insert/write/update may leave a pending row.
                // Keep the exact-row forensic journal; never guess a path for
                // deletion or claim that no destination was modified.
                Result(State.NEEDS_ATTENTION,
                    "Publication is uncertain. Review Downloads/Chunks Exports and the saved operation record; Chunks did not automatically delete or retry anything.")
            }
        }
    }
}
