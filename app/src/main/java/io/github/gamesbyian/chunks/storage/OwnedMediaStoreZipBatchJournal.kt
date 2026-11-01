package io.github.gamesbyian.chunks.storage

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * App-private, fsynced read-only recovery manifest for 2–8 ZIP members.
 * This log is NEVER provider write/delete/retry authority. A crash after an
 * exact-row publication but before the corresponding batch ROW record leaves
 * the batch UNCERTAIN, not falsely complete. Per-file own-row logs remain.
 */
internal class OwnedMediaStoreZipBatchJournal private constructor(
    val id: String,
    private val count: Int,
    private val log: ExclusiveFsyncJournal
) : Closeable {
    companion object {
        private const val VERSION = "CHUNKS_ZIP_BATCH1"
        private const val DIRECTORY = "owned-mediastore-zip-batches"
        private const val MAX_HISTORY = 512
        private val ID = Regex("[0-9a-f]{32}")
        private val NAME =
            Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,155}\\.(json|chunksnap|torrent|txt|bin)")

        fun begin(context: Context, names: List<String>): OwnedMediaStoreZipBatchJournal {
            require(names.size in 2..8 && names.all { NAME.matches(it) } &&
                names.distinct().size == names.size)
            val dir = root(context)
            try { Files.createDirectory(dir) }
            catch (_: FileAlreadyExistsException) { }
            check(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(context.filesDir.toPath(), StandardOpenOption.READ)
                .use { it.force(true) }
            repeat(16) {
                val id = UUID.randomUUID().toString().replace("-", "")
                val log = try { ExclusiveFsyncJournal.create(dir, id) }
                    catch (_: FileAlreadyExistsException) { return@repeat }
                try {
                    log.append(VERSION + "\tINTENT\t" + id + "\t" + names.size +
                        "\t" + names.joinToString("|"))
                    return OwnedMediaStoreZipBatchJournal(id, names.size, log)
                } catch (error: Exception) {
                    log.close()
                    throw IOException("Unable to save batch intent", error)
                }
            }
            throw IOException("Unable to create private batch journal")
        }

        private fun root(context: Context) = context.filesDir.toPath().resolve(DIRECTORY)
        data class Snapshot(val verified: Int, val expected: Int, val terminal: String?)

        private fun parse(id: String, lines: List<String>?): Snapshot? {
            if (!ID.matches(id) || lines.isNullOrEmpty()) return null
            val first = lines.first().split('\t')
            if (first.size != 5 || first[0] != VERSION ||
                first[1] != "INTENT" || first[2] != id) return null
            val count = first[3].toIntOrNull()?.takeIf { it in 2..8 } ?: return null
            val names = first[4].split('|')
            if (names.size != count || names.any { !NAME.matches(it) } ||
                names.distinct().size != count) return null
            var verified = 0
            var terminal: String? = null
            for (line in lines.drop(1)) {
                if (terminal != null) return null
                val parts = line.split('\t')
                if (parts.firstOrNull() != VERSION) return null
                when {
                    parts.size == 5 && parts[1] == "ROW" -> {
                        if (parts[2].toIntOrNull() != verified ||
                            !ID.matches(parts[3])) return null
                        val uri = try { android.net.Uri.parse(parts[4]) }
                            catch (_: Exception) { return null }
                        val root = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val rowId = try { ContentUris.parseId(uri) }
                            catch (_: Exception) { return null }
                        if (rowId <= 0L || uri != ContentUris.withAppendedId(root, rowId))
                            return null
                        verified++
                        if (verified > count) return null
                    }
                    parts.size == 3 && parts[1] in setOf("STOP", "ISSUE") -> {
                        if (parts[2].toIntOrNull() != verified || verified >= count)
                            return null
                        terminal = parts[1]
                    }
                    parts.size == 2 && parts[1] == "END" && verified == count ->
                        terminal = "END"
                    else -> return null
                }
            }
            return Snapshot(verified, count, terminal)
        }

        /**
         * Nonblocking, bounded, no-follow read through ExclusiveFsyncJournal.
         * The completed operation may later be deliberately changed by a
         * user; never reinterpret that as an interrupted Save.
         */
        fun unfinishedDescriptions(context: Context): List<String> {
            val dir = root(context)
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                return listOf("Earlier ZIP batch records cannot be checked safely.")
            val files = dir.toFile().listFiles()
                ?: return listOf("Earlier ZIP batch records cannot be checked safely.")
            val excess = files.size > MAX_HISTORY ||
                files.any { !it.name.endsWith(".log") ||
                    !ID.matches(it.name.removeSuffix(".log")) }
            val descriptions = files.asSequence()
                .filter { it.name.endsWith(".log") && ID.matches(it.name.removeSuffix(".log")) }
                .sortedBy { it.name }.take(MAX_HISTORY).mapNotNull { f ->
                    val state = parse(f.name.removeSuffix(".log"),
                        ExclusiveFsyncJournal.readCompleteLines(f.toPath()))
                        ?: return@mapNotNull "An earlier ZIP batch record needs manual review."
                    when (state.terminal) {
                        "END" -> null
                        "STOP" -> "An earlier ZIP batch was stopped after " +
                            state.verified + " of " + state.expected +
                            " verified new files. Earlier copies were preserved."
                        "ISSUE" -> "A ZIP batch needs review after " +
                            state.verified + " of " + state.expected +
                            " confirmed files; another row may be pending."
                        else -> "An interrupted ZIP batch needs review after " +
                            state.verified + " of " + state.expected +
                            " confirmed files. No automatic action was taken."
                    }
                }.toMutableList()
            if (excess) descriptions += "Some ZIP batch records could not be inspected safely."
            return descriptions
        }
    }

    @Synchronized fun rowPublished(
        index: Int,
        receipt: OwnedMediaStoreDocumentPublication.Receipt
    ) {
        require(index in 0 until count && ID.matches(receipt.operationId))
        log.append(VERSION + "\tROW\t" + index + "\t" +
            receipt.operationId + "\t" + receipt.uri)
    }
    @Synchronized fun stopped(index: Int) {
        require(index in 0 until count)
        log.append(VERSION + "\tSTOP\t" + index)
    }
    @Synchronized fun uncertain(index: Int) {
        require(index in 0 until count)
        log.append(VERSION + "\tISSUE\t" + index)
    }
    @Synchronized fun finished() = log.append(VERSION + "\tEND")
    override fun close() = log.close()
}
