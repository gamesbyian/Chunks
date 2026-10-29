package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalAddResult
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Batch **Copy Transfer to phone**: add each selected server transfer to the
 * phone engine as a new magnet job in metadata-only mode at the phone's
 * verified default Place. Job-only: no payload is fetched until the user
 * starts it, and the server transfers are never changed. Magnets carry the
 * v1 identity and only *public* tracker URLs (private transfers: identity
 * only). Stops at the first Add whose outcome is unknown; nothing retried.
 */
internal object BatchPhoneCopy {
    const val MAX_ITEMS = 100

    data class Item(val infoHash: String, val name: String)

    enum class Result(val label: String) {
        ADDED("added to the phone (metadata only; start it when ready)"),
        ALREADY_ON_PHONE("already on the phone (not added again)"),
        REFUSED("not added (the phone refused it)"),
        NO_MAGNET("skipped: no v1 identity"),
        UNCONFIRMED("not confirmed — check the phone list; not retried"),
        NOT_RUN("not attempted (the batch stopped earlier)")
    }

    data class Report(val results: List<Pair<Item, Result>>) {
        fun count(r: Result) = results.count { it.second == r }
        fun summary(): String = "${count(Result.ADDED)} added to the phone, ${count(Result.ALREADY_ON_PHONE)} " +
            "already there" + listOf(Result.REFUSED, Result.NO_MAGNET, Result.UNCONFIRMED, Result.NOT_RUN)
                .filter { count(it) > 0 }.joinToString("") { ", ${count(it)} ${it.name.lowercase(Locale.ROOT).replace('_', ' ')}" } +
            ". Server transfers were not changed; phone jobs wait for metadata and download only after you start them."
    }

    fun validate(items: List<Item>): String? = when {
        items.isEmpty() -> "Select at least one transfer."
        items.size > MAX_ITEMS -> "Copy at most $MAX_ITEMS transfers at a time."
        items.map { it.infoHash.lowercase(Locale.ROOT) }.toSet().size != items.size ->
            "The selection contains a duplicate transfer."
        else -> null
    }

    suspend fun run(
        items: List<Item>,
        /** The handoff magnet for one item, or null when it has no v1 identity. Read-only. */
        magnetFor: suspend (Item) -> String?,
        addMetadataOnly: suspend (String) -> LocalAddResult
    ): Report {
        require(validate(items) == null) { validate(items)!! }
        val results = ArrayList<Pair<Item, Result>>()
        var stopped = false
        for (item in items) {
            if (stopped) { results += item to Result.NOT_RUN; continue }
            val magnet = try { magnetFor(item) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (magnet == null) { results += item to Result.NO_MAGNET; continue }
            val outcome = try { addMetadataOnly(magnet) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            results += item to when (outcome) {
                is LocalAddResult.FetchingMetadata, is LocalAddResult.AddedStopped -> Result.ADDED
                is LocalAddResult.Refused ->
                    if (outcome.reason.contains("already", ignoreCase = true)) Result.ALREADY_ON_PHONE
                    else Result.REFUSED
                null -> { stopped = true; Result.UNCONFIRMED }
            }
        }
        return Report(results)
    }
}
