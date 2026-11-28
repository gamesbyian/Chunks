package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedFileSearchTest {
    private fun key(client: String, hash: Char = 'a') =
        UnifiedTransferKey.of(EnvironmentId(client), hash.toString().repeat(40))

    private fun file(index: Int, path: String, completed: Long? = null) =
        ClientFile(index, path, 100L, completed, ClientFilePriority.NORMAL)

    private fun target(client: String, files: List<ClientFile>, hash: Char = 'a',
                       pending: Boolean? = false) =
        SelectedFileSearch.Target(key(client, hash), client, "Transfer $client", pending,
            read = { files }, authorized = { true })

    private fun result(outcome: SelectedFileSearch.Outcome) =
        (outcome as SelectedFileSearch.Outcome.Observed).result

    @Test fun duplicateNamesAndHashesRetainExactClientAndOriginalFileIndices() = runBlocking {
        val a = target("Local", listOf(file(1, "a/Match.mkv"), file(9, "a/Match.mkv"),
            file(15, "skip.txt")))
        val b = target("Remote", listOf(file(7, "a/match.mkv", 42)))
        val found = result(SelectedFileSearch.scan(" MATCH.MKV ", listOf(a, b), { true }, { 77L }))
        assertEquals(listOf(a.key to 1, a.key to 9, b.key to 7),
            found.matches.map { it.key to it.index })
        assertEquals(listOf(null, null, 42L), found.matches.map { it.completedBytes })
        assertTrue(found.matches.all { it.observedAtMillis == 77L })
        assertFalse(found.hasCoverageGap)
    }

    @Test fun missingPendingAndInvalidListsNeverBecomeExhaustiveNoMatch() = runBlocking {
        var calls = 0
        val pending = target("pending", emptyList(), pending = true).copy(read = { calls++; emptyList() })
        val unknown = target("empty", emptyList())
        val offline = target("offline", emptyList()).copy(read = null)
        val duplicate = target("duplicate", listOf(file(0, "match"), file(0, "other")))
        val failed = target("failed", emptyList()).copy(read = { throw java.io.IOException("not available") })
        val found = result(SelectedFileSearch.scan("match", listOf(pending, unknown, offline, duplicate, failed), { true }))
        assertEquals(0, calls)
        assertTrue(found.matches.isEmpty())
        assertTrue(found.hasCoverageGap)
        assertEquals(listOf(SelectedFileSearch.Coverage.METADATA_PENDING,
            SelectedFileSearch.Coverage.EMPTY_OR_UNKNOWN, SelectedFileSearch.Coverage.UNAVAILABLE,
            SelectedFileSearch.Coverage.INVALID, SelectedFileSearch.Coverage.UNAVAILABLE),
            found.sources.map { it.coverage })
    }

    @Test fun boundsAndPagingPreserveEveryExaminedMatchWithoutSilentTruncation() = runBlocking {
        val large = target("big", List(SelectedFileSearch.MAX_FILES_PER_TRANSFER + 1) { file(it, "match/$it") })
        val found = result(SelectedFileSearch.scan("match", listOf(large), { true }))
        assertEquals(SelectedFileSearch.MAX_FILES_PER_TRANSFER, found.matches.size)
        assertEquals(SelectedFileSearch.Coverage.LIMITED, found.sources.single().coverage)
        assertEquals(found.matches, (0 until found.pageCount).flatMap(found::page))
        assertTrue(found.page(0).size <= SelectedFileSearch.PAGE_SIZE)
        var reads = 0
        val tooMany = List(SelectedFileSearch.MAX_TRANSFERS + 1) { i ->
            target("client-$i", emptyList()).copy(read = { reads++; emptyList() })
        }
        assertEquals(SelectedFileSearch.Outcome.InvalidRequest,
            SelectedFileSearch.scan("match", tooMany, { true }))
        assertEquals(0, reads)
    }

    @Test fun revokingOwnerDuringReadDiscardsAllEarlierResultsAndDoesNotReadLaterSources() = runBlocking {
        val first = target("first", listOf(file(0, "match")))
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var permitted = true
        val second = target("second", emptyList()).copy(
            read = { waiting.complete(Unit); release.await(); listOf(file(1, "match")) },
            authorized = { permitted })
        var laterReads = 0
        val third = target("third", emptyList()).copy(read = { laterReads++; emptyList() })
        val attempt = async { SelectedFileSearch.scan("match", listOf(first, second, third), { true }) }
        waiting.await()
        permitted = false
        release.complete(Unit)
        assertEquals(SelectedFileSearch.Outcome.Revoked, attempt.await())
        assertEquals(0, laterReads)
    }

    @Test fun viewRevocationAndDuplicateSelectionPreventAnyFurtherReads() = runBlocking {
        var current = true
        var laterReads = 0
        val first = target("first", emptyList()).copy(read = { current = false; listOf(file(0, "match")) })
        val second = target("second", emptyList()).copy(read = { laterReads++; emptyList() })
        assertEquals(SelectedFileSearch.Outcome.Revoked,
            SelectedFileSearch.scan("match", listOf(first, second), { current }))
        assertEquals(0, laterReads)
        assertEquals(SelectedFileSearch.Outcome.InvalidRequest,
            SelectedFileSearch.scan("match", listOf(second, second), { true }))
        assertEquals(0, laterReads)
    }
}
