package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.FilePriorityOutcome
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnapshotRemotePriorityApplyTest {
    private val hash = "a".repeat(40)
    private val account = "https://example.invalid/RPC2\u0000alice"
    private val transfer = RemoteTransfer(hash, "Movie", 50, 20, false, "/verified")
    private val entry = SnapshotRestorePreviewV1.Entry(hash, "Movie",
        SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT, false, "/obsolete",
        fileMappings = listOf(
            SnapshotFilePriorities.Mapping(
                SnapshotFilePriorities.File(0, "Movie/one.mkv", 30),
                SnapshotFilePriorities.Level.HIGH),
            SnapshotFilePriorities.Mapping(
                SnapshotFilePriorities.File(1, "Movie/two.srt", 20),
                SnapshotFilePriorities.Level.SKIP)
        ))
    private val initial = listOf(RemoteFile("one.mkv", 30, 1), RemoteFile("two.srt", 20, 1))
    private fun review() = requireNotNull(
        SnapshotRemotePriorityComparison.plan(entry, transfer, initial))

    private class Fake(
        var target: RemoteTransfer,
        var files: List<RemoteFile>,
        var reply: FilePriorityOutcome? = null
    ) : SnapshotRemotePriorityApply.Port {
        val writes = mutableListOf<Int>()
        var beforeWrite: (suspend () -> Unit)? = null
        override suspend fun completeSession(): List<RemoteTransfer> = listOf(target)
        override suspend fun readFiles(hash: String): List<RemoteFile> = files.toList()
        override suspend fun setFilePriority(
            hash: String, index: Int, path: String, sizeBytes: Long,
            expected: Int, desired: Int
        ): FilePriorityOutcome {
            writes += index
            beforeWrite?.invoke()
            val row = files[index]
            check(row.path == path && row.sizeBytes == sizeBytes && row.priority == expected)
            files = files.toMutableList().also { it[index] = row.copy(priority = desired) }
            return reply ?: FilePriorityOutcome.Verified(hash, index, desired)
        }
    }

    private suspend fun apply(fake: Fake, accepted: Boolean = true, current: () -> Boolean = { true }) =
        SnapshotRemotePriorityApply.apply(account, entry, review(), accepted, current, fake)

    @Test fun explicitConfirmationWritesOnlyChangedExactFilesAndReadsBackEntireVector() = runBlocking {
        val fake = Fake(transfer, initial)
        assertEquals(SnapshotRemotePriorityApply.Outcome.REFUSED, apply(fake, false).outcome)
        assertTrue(fake.writes.isEmpty())
        val result = apply(fake)
        assertEquals(SnapshotRemotePriorityApply.Outcome.VERIFIED, result.outcome)
        assertEquals(2, result.requestedWrites)
        assertEquals(2, result.verifiedWrites)
        assertEquals(listOf(0, 1), fake.writes)
        assertEquals(listOf(2, 0), fake.files.map { it.priority })
    }

    @Test fun staleAccountOrUnrelatedFilePathRefusesWithoutAnyWrite() = runBlocking {
        val fake = Fake(transfer, initial)
        assertEquals(SnapshotRemotePriorityApply.Outcome.REFUSED,
            apply(fake, current = { false }).outcome)
        fake.target = transfer.copy(name = "Not Movie")
        assertEquals(SnapshotRemotePriorityApply.Outcome.STALE, apply(fake).outcome)
        fake.target = transfer
        fake.files = listOf(RemoteFile("not-the-reviewed-file.mkv", 30, 1), initial[1])
        assertEquals(SnapshotRemotePriorityApply.Outcome.STALE, apply(fake).outcome)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun unsupportedLowAndStaleNoncontiguousFileIndexRefuseBeforeWrite() = runBlocking {
        val unsupported = entry.copy(fileMappings = entry.fileMappings.mapIndexed { i, mapping ->
            if (i == 1) mapping.copy(level = SnapshotFilePriorities.Level.LOW) else mapping
        })
        val unsupportedReview = SnapshotRemotePriorityComparison.plan(
            unsupported, transfer, initial)!!
        val fake = Fake(transfer, initial)
        val result = SnapshotRemotePriorityApply.apply(
            account, unsupported, unsupportedReview, true, { true }, fake)
        assertEquals(SnapshotRemotePriorityApply.Outcome.REFUSED, result.outcome)
        val skippedIndex = entry.copy(fileMappings = entry.fileMappings.mapIndexed { i, row ->
            if (i == 1) row.copy(file = row.file.copy(index = 2)) else row
        })
        val skippedReview = SnapshotRemotePriorityComparison.plan(skippedIndex, transfer, initial)!!
        assertEquals(SnapshotRemotePriorityApply.Outcome.REFUSED,
            SnapshotRemotePriorityApply.apply(
                account, skippedIndex, skippedReview, true, { true }, fake).outcome)
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun lostReplyHaltsRemainingChangesAndNeverRetries() = runBlocking {
        val fake = Fake(transfer, initial,
            reply = FilePriorityOutcome.Uncertain(hash, 0))
        val result = apply(fake)
        assertEquals(SnapshotRemotePriorityApply.Outcome.NEEDS_READ_ONLY_REVIEW, result.outcome)
        assertEquals(0, result.verifiedWrites)
        assertEquals(listOf(0), fake.writes)
        assertEquals(2, fake.files[0].priority) // Server may already have mutated.
        assertEquals(1, fake.files[1].priority) // No second write.
    }

    @Test fun secondSubmissionOnSameAccountAndHashIsRejectedWhileFirstRuns() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = Fake(transfer, initial)
        fake.beforeWrite = { entered.complete(Unit); release.await() }
        val first = async { apply(fake) }
        entered.await()
        val second = apply(Fake(transfer, initial))
        assertEquals(SnapshotRemotePriorityApply.Outcome.REFUSED, second.outcome)
        release.complete(Unit)
        assertEquals(SnapshotRemotePriorityApply.Outcome.VERIFIED, first.await().outcome)
    }
}
