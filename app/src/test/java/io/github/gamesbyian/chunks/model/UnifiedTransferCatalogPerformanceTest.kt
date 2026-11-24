package io.github.gamesbyian.chunks.model

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * FE-11: reproducible pure catalog query baseline, not a device/UI or network benchmark.
 * Report timings without machine-dependent pass thresholds; enforce data/identity semantics.
 */
class UnifiedTransferCatalogPerformanceTest {
    @Test fun measuresMixedClientCatalogQueriesAndOnePercentUpdates() {
        for (size in listOf(100, 1_000, 10_000, 50_000)) {
            val environments = (0 until 5).map { EnvironmentId("fixture-$it") }
            // Same hashes on different clients deliberately remain distinct.
            val sources = environments.mapIndexed { envIndex, environment ->
                val rows = (0 until size / environments.size).map { index ->
                    val hash = index.toString(16).padStart(40, '0')
                    val name = if (index % 2 == 0) "Fixture ${index % 37}" else "fixture ${index % 37}"
                    UnifiedTransferObservation(
                        UnifiedTransferKey.of(environment, hash), name, "Payload $index",
                        1000L, index % 1_000_001, index % 3 != 0,
                        (index.toLong() + envIndex).takeUnless { index % 7 == 0 },
                        0L, null)
                }.shuffled(Random(23 + envIndex))
                EnvironmentTransferSnapshot(environment,
                    if (envIndex == 4) EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
                    true, 100L, rows)
            }
            val defaultQuery = UnifiedTransferQuery()
            val groupedQuery = UnifiedTransferQuery(
                sortKey = UnifiedSortKey.DOWNLOAD_SPEED,
                direction = UnifiedSortDirection.DESCENDING,
                groupByEnvironment = true, environmentOrder = environments.reversed())
            val searchQuery = UnifiedTransferQuery(search = "Unique confirmed media")
            // Exact client/hash membership; precompute lookup as the screen does.
            val changedKeys = sources.flatMapIndexed { envIndex, source ->
                source.transfers.filter {
                    (envIndex.toLong() * (size / environments.size) + it.key.infoHash.toLong(16)) % 100L == 0L
                }.map { it.key }
            }.toSet()
            val expectedMatches = size / 100
            assertEquals(expectedMatches, changedKeys.size)
            val titles = changedKeys.associateWith { "Unique confirmed media" }
            val changedRate = 1_000_000L // deliberately outside the baseline domain
            val changed = sources.map { source ->
                source.copy(observedAtEpochMillis = 101L,
                    transfers = source.transfers.map { row ->
                        if (row.key in changedKeys) row.copy(downloadBytesPerSecond = changedRate)
                        else row
                    })
            }

            fun sample(label: String, operation: () -> UnifiedTransferQueryResult,
                       verify: (UnifiedTransferQueryResult) -> Unit) {
                verify(operation()) // warmup; do not time correctness assertions
                val times = List(5) {
                    val start = System.nanoTime()
                    val result = operation()
                    val elapsed = (System.nanoTime() - start) / 1_000
                    verify(result)
                    elapsed
                }.sorted()
                println("CHUNKS_UNIFIED_CATALOG: jobs=$size case=$label median_us=${times[2]} " +
                    "range_us=${times.first()}-${times.last()}")
            }
            sample("name_unchanged", { UnifiedTransferCatalog.query(sources, defaultQuery) }) { result ->
                assertEquals(size, result.transfers.size)
                assertEquals(size, result.transfers.map { it.key }.toSet().size)
                assertTrue(result.hasIncompleteOrStaleSource)
                assertEquals(4 * size / 5, UnifiedTransferTotals.of(result).liveRows)
                assertTrue(result.transfers.zipWithNext().all { (a, b) ->
                    val name = a.displayName.lowercase(Locale.ROOT).compareTo(b.displayName.lowercase(Locale.ROOT))
                    val environment = a.key.environment.value.compareTo(b.key.environment.value)
                    name < 0 || (name == 0 && (environment < 0 ||
                        (environment == 0 && a.key.infoHash < b.key.infoHash)))
                })
            }
            fun verifyGrouped(result: UnifiedTransferQueryResult, updated: Boolean) {
                assertEquals(size, result.transfers.size)
                assertEquals(environments.reversed(), result.groups.map { it.source.environment })
                assertTrue(result.groups.all { group ->
                    group.transfers.zipWithNext().all { (a, b) ->
                        val av = a.downloadBytesPerSecond
                        val bv = b.downloadBytesPerSecond
                        av != null && (bv == null || av >= bv) || av == null && bv == null
                    }
                })
                val changedRows = result.transfers.filter { it.downloadBytesPerSecond == changedRate }
                assertEquals(if (updated) changedKeys else emptySet<UnifiedTransferKey>(),
                    changedRows.map { it.key }.toSet())
            }
            sample("grouped_speed_unchanged", { UnifiedTransferCatalog.query(sources, groupedQuery) }) {
                verifyGrouped(it, updated = false)
            }
            sample("grouped_speed_updated", { UnifiedTransferCatalog.query(changed, groupedQuery) }) {
                verifyGrouped(it, updated = true)
            }
            var titleCalls = 0
            sample("media_search", {
                titleCalls = 0
                UnifiedTransferCatalog.query(sources, searchQuery) { key ->
                    titleCalls++
                    titles[key]
                }
            }) { result ->
                assertEquals(size, titleCalls)
                assertEquals(expectedMatches, result.transfers.size)
                assertEquals(changedKeys, result.transfers.map { it.key }.toSet())
            }
        }
    }
}
