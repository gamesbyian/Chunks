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
 * Bounded #809 second adapter: 2–8 flat ZIP members to fresh independent
 * platform-owned Downloads rows. A batch is NOT atomic. Failures never
 * delete previously published rows, an uncertain row or the original ZIP.
 * Generic SAF, nested paths and Replace remain unavailable.
 */
internal object LocalMultiZipMediaStoreExport {
    const val MAX_FILES = 8
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    const val MAX_EACH_BYTES = LocalSingleZipMediaStoreExport.MAX_MEMBER_BYTES
    private val safeName =
        Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,155}\\.(json|chunksnap|torrent|txt|bin)")

    enum class State { EXPORTED, PARTIAL, BLOCKED, NEEDS_ATTENTION }
    data class Published(val originalName: String, val publishedName: String, val uri: Uri)
    data class Result(
        val state: State,
        val message: String,
        val published: List<Published> = emptyList(),
        val remaining: Int = 0
    )
    private data class Member(val name: String, val bytes: ByteArray, val mime: String)

    private fun mime(name: String): String = when (name.substringAfterLast('.')) {
        "txt" -> "text/plain"
        "json" -> "application/json"
        "torrent" -> "application/x-bittorrent"
        else -> "application/octet-stream"
    }

    /** The preview is informative only. Actual bytes are restaged and checked. */
    fun eligible(review: LocalZipReview): Boolean {
        val inventory = review.inventory ?: return false
        val checked = review.verification ?: return false
        return inventory.status != ZipPreflightStatus.BLOCKED &&
            checked.status == ZipPayloadStatus.VERIFIED_READ_ONLY &&
            inventory.fileCount in 2..MAX_FILES &&
            inventory.names.size == inventory.fileCount &&
            inventory.names.all { safeName.matches(it) } &&
            checked.verifiedFiles == inventory.fileCount &&
            checked.actualOutputBytes in 1L..MAX_TOTAL_BYTES.toLong()
    }

    fun extractNew(
        context: Context,
        source: Uri,
        cancelled: () -> Boolean = { false }
    ): Result {
        if (source.scheme != "content" || cancelled())
            return Result(State.BLOCKED, "No ZIP publication was started.")
        val staged = LocalZipDocumentInspector.stage(context, source)
        return staged.use { owned ->
            val file = owned.file ?: return@use Result(State.BLOCKED,
                owned.failure ?: "The ZIP cannot be reopened safely.")
            val inventory = ZipArchiveInventory.inspect(file)
            val verified = ZipPayloadVerifier.verify(file)
            val names = inventory.names
            if (inventory.status == ZipPreflightStatus.BLOCKED ||
                inventory.fileCount !in 2..MAX_FILES ||
                names.size != inventory.fileCount ||
                names.any { !safeName.matches(it) } ||
                verified.status != ZipPayloadStatus.VERIFIED_READ_ONLY ||
                verified.verifiedFiles != inventory.fileCount ||
                verified.actualOutputBytes !in 1L..MAX_TOTAL_BYTES.toLong())
                return@use Result(State.BLOCKED,
                    "Only 2–8 verified flat files up to 8 MiB each and 16 MiB total are supported. No destination was created.")

            // Check EVERY decompressed member before inserting ANY provider row.
            // A bad ZIP never generates a misleading partial publication.
            val members: List<Member> = try {
                ZipFile(file).use { zip ->
                    val out = ArrayList<Member>(inventory.fileCount)
                    var budget = MAX_TOTAL_BYTES.toLong()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        if (cancelled()) throw IOException("Cancelled")
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !safeName.matches(entry.name) ||
                            entry.size !in 1L..MAX_EACH_BYTES.toLong() ||
                            entry.size > budget || entry.crc < 0 || entry.compressedSize < 0)
                            throw IOException("Unsafe ZIP member")
                        val buffer = ByteArray(32 * 1024)
                        val output = ByteArrayOutputStream(entry.size.toInt())
                        val crc = CRC32()
                        var received = 0L
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                if (cancelled()) throw IOException("Cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0 || count.toLong() > entry.size - received)
                                    throw IOException("Unexpected ZIP length")
                                output.write(buffer, 0, count)
                                crc.update(buffer, 0, count)
                                received += count
                            }
                        }
                        if (received != entry.size || crc.value != entry.crc)
                            throw IOException("ZIP CRC/length changed")
                        budget -= received
                        out += Member(entry.name, output.toByteArray(), mime(entry.name))
                        if (out.size > MAX_FILES) throw IOException("Too many members")
                    }
                    if (out.map { it.name } != names || out.size != inventory.fileCount ||
                        out.sumOf { it.bytes.size.toLong() } != verified.actualOutputBytes)
                        throw IOException("ZIP inventory changed")
                    out
                }
            } catch (_: Exception) {
                return@use Result(State.BLOCKED,
                    "The ZIP changed, was cancelled or failed verification. No destination was created.")
            }
            if (cancelled()) return@use Result(State.BLOCKED,
                "Cancelled before any Downloads creation.")
            val receipts = ArrayList<Published>(members.size)
            for ((index, member) in members.withIndex()) {
                if (cancelled()) return@use Result(State.PARTIAL,
                    "Stopped after " + receipts.size + " verified new file(s). Existing publications were preserved.",
                    receipts.toList(), members.size - index)
                try {
                    val row = OwnedMediaStoreDocumentPublication.publish(
                        context, member.name, member.mime, member.bytes, cancelled)
                    receipts += Published(member.name, row.publishedName, row.uri)
                } catch (_: Exception) {
                    // This row may be pending or already published. Never
                    // delete/retry based on original or generated filenames.
                    return@use Result(State.NEEDS_ATTENTION,
                        "Earlier copies were preserved. The current new row may be incomplete: review its journal. No automatic retry or cleanup.",
                        receipts.toList(), members.size - index)
                }
            }
            Result(State.EXPORTED, "Created " + receipts.size +
                " distinct verified Downloads files. Original ZIP preserved.",
                receipts.toList(), 0)
        }
    }
}
