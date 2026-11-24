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
    @Test fun measuresDistinctNameSortAgainstPriorComparator() {
        // The original benchmark deliberately has only 74 raw spellings.
        // Exercise the opposite cardinality extreme as well: every row has a
        // unique raw name, including non-ASCII case mappings.
        for (size in listOf(100, 1_000, 10_000, 50_000)) {
            val sources = (0 until 5).map { client ->
                val env = EnvironmentId("distinct-$client")
                EnvironmentTransferSnapshot(env, EnvironmentAvailability.LIVE, true, 100L,
                    (0 until size / 5).map { index ->
                        val name = if (index % 2 == 0) "İtem $client $index" else "ITEM $client $index"
                        UnifiedTransferObservation(
                            UnifiedTransferKey.of(env, index.toString(16).padStart(40, '0')),
                            name, name, null, null, null, null, null, null)
                    }.shuffled(Random(71 + client)))
            }
            // Reference reproduces the old comparator and exact tie rules.
            // It times flatten+sort only; the candidate includes the full
            // query, so do not interpret the ratio as a precise UI speedup.
            fun reference() = sources.flatMap { it.transfers }.sortedWith(Comparator { a, b ->
                val name = a.displayName.lowercase(Locale.ROOT)
                    .compareTo(b.displayName.lowercase(Locale.ROOT))
                if (name != 0) name else {
                    val env = a.key.environment.value.compareTo(b.key.environment.value)
                    if (env != 0) env else a.key.infoHash.compareTo(b.key.infoHash)
                }
            })
            fun candidate() = UnifiedTransferCatalog.query(sources, UnifiedTransferQuery()).transfers
            val expected = reference()
            assertEquals(size, expected.size)
            assertEquals(expected, candidate())
            val before = mutableListOf<Long>()
            val after = mutableListOf<Long>()
            fun sample(operation: () -> List<UnifiedTransferObservation>, times: MutableList<Long>) {
                val start = System.nanoTime()
                val actual = operation()
                times.add((System.nanoTime() - start) / 1_000)
                assertEquals(expected, actual)
            }
            repeat(5) { iteration ->
                if (iteration % 2 == 0) {
                    sample(::reference, before)
                    sample(::candidate, after)
                } else {
                    sample(::candidate, after)
                    sample(::reference, before)
                }
            }
            println("CHUNKS_DISTINCT_NAME_SORT: jobs=$size old_flatten_sort_median_us=${before.sorted()[2]} " +
                "candidate_query_median_us=${after.sorted()[2]}")
        }
    }

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
