package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Batch **Copy Transfer to server**: add each selected phone transfer's exact
 * torrent definition to the signed-in rTorrent account as a new, stopped job
 * at the server's default Place. Job-only (docs/CROSS-ENGINE-TRANSFER-JOBS.md):
 * no payload is sent, and the phone transfers are never changed.
 *
 * Items run one at a time. A transfer whose definition cannot be exported
 * with a matching identity is skipped (nothing was sent). The batch stops at
 * the first Add whose outcome is not confirmed, because that load may have
 * landed: it must be reconciled from a fresh server list, never repeated.
 */
internal object BatchServerCopy {
    const val MAX_ITEMS = 100

    data class Item(val infoHash: String, val name: String)

    enum class Result(val label: String) {
        ADDED("added stopped on the server"),
        ALREADY_ON_SERVER("already on the server (not added again)"),
        NO_DEFINITION("skipped: no exact torrent definition on the phone"),
        UNCONFIRMED("sent, but not confirmed — check the server list; not retried"),
        NOT_RUN("not attempted (the batch stopped earlier)")
    }

    data class Report(val results: List<Pair<Item, Result>>) {
        val stoppedEarly: Boolean get() = results.any { it.second == Result.NOT_RUN }
        fun count(result: Result) = results.count { it.second == result }
        fun summary(): String = buildString {
            append("${count(Result.ADDED)} added, ${count(Result.ALREADY_ON_SERVER)} already there")
            count(Result.NO_DEFINITION).takeIf { it > 0 }?.let { append(", $it skipped") }
            count(Result.UNCONFIRMED).takeIf { it > 0 }?.let { append(", $it unconfirmed") }
            count(Result.NOT_RUN).takeIf { it > 0 }?.let { append(", $it not attempted") }
            append(". Phone transfers and their files were not changed; the server jobs start ")
            append("stopped and download on their own.")
        }
    }

    fun validate(items: List<Item>): String? = when {
        items.isEmpty() -> "Select at least one transfer."
        items.size > MAX_ITEMS -> "Copy at most $MAX_ITEMS transfers at a time."
        items.any { !Regex("[0-9a-fA-F]{40}").matches(it.infoHash) } ->
            "Only transfers with a v1 identity can be copied to rTorrent."
        items.map { it.infoHash.lowercase(Locale.ROOT) }.toSet().size != items.size ->
            "The selection contains a duplicate transfer."
        else -> null
    }

    suspend fun run(
        items: List<Item>,
        /** The phone's exact original .torrent, or null. Read-only. */
        exportTorrent: suspend (String) -> ByteArray?,
        /** One stopped Add at the server default; returns its verified outcome. */
        addStopped: suspend (ByteArray) -> RemoteAddOutcome
    ): Report {
        require(validate(items) == null) { validate(items)!! }
        val results = ArrayList<Pair<Item, Result>>(items.size)
        var stopped = false
        for (item in items) {
            if (stopped) { results += item to Result.NOT_RUN; continue }
            val bytes = try { exportTorrent(item.infoHash) } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }?.takeIf { definition ->
                try {
                    TorrentMetainfoReader.preview(definition).infoHashV1
                        .equals(item.infoHash, ignoreCase = true)
                } catch (_: Exception) { false }
            }
            if (bytes == null) { results += item to Result.NO_DEFINITION; continue }
            val outcome = try { addStopped(bytes) } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { null }
            results += item to when (outcome) {
                is RemoteAddOutcome.VerifiedStopped -> Result.ADDED
                is RemoteAddOutcome.AlreadyPresent -> Result.ALREADY_ON_SERVER
                else -> { stopped = true; Result.UNCONFIRMED }
            }
        }
        return Report(results)
    }
}
