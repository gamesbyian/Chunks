package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SnapshotRemotePriorityComparisonTest {
    private val hash = "a".repeat(40)
    private val observed = RemoteTransfer(hash, "Movie", 50, 20, false, "/verified")
    private val first = SnapshotFilePriorities.File(0, "Movie/one.mkv", 30)
    private val second = SnapshotFilePriorities.File(1, "Movie/two.srt", 20)
    private val saved = SnapshotRestorePreviewV1.Entry(
        infoHashV1 = hash,
        originalName = "Movie",
        decision = SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
        selectedForReview = false,
        historicalDirectoryHint = "/untrusted",
        fileMappings = listOf(
            SnapshotFilePriorities.Mapping(first, SnapshotFilePriorities.Level.HIGH),
            SnapshotFilePriorities.Mapping(second, SnapshotFilePriorities.Level.LOW))
    )
    @Test fun exactConnectedFilesAreComparedWithoutApplyingUnsupportedLevel() {
        val files = listOf(RemoteFile("one.mkv", 30, 1), RemoteFile("two.srt", 20, 0))
        val result = SnapshotRemotePriorityComparison.plan(saved, observed, files)
        assertNotNull(result)
        assertEquals(2, result!!.changeCount)
        assertEquals(1, result.unsupportedCount)
        assertEquals(SnapshotFilePriorities.Level.NORMAL, result.rows.first().current)
        assertEquals(SnapshotFilePriorities.Level.LOW, result.rows.last().saved)
    }
    @Test fun exactFullPathsAreSupportedButMixedPathsAreRefused() {
        val full = listOf(RemoteFile("Movie/one.mkv",30,2),
            RemoteFile("Movie/two.srt",20,0))
        assertNotNull(SnapshotRemotePriorityComparison.plan(saved, observed, full))
        val mixed = listOf(full.first(), RemoteFile("two.srt",20,0))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed, mixed))
    }
    @Test fun rejectsWrongAccountOrIdentityOrUnknownFilesOrIncompleteRead() {
        val good = listOf(RemoteFile("one.mkv",30,1), RemoteFile("two.srt",20,0))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed.copy(hash="b".repeat(40)), good))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed.copy(name="Renamed"), good))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed, good.dropLast(1)))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed, good.mapIndexed { index, f ->
            if (index == 0) f.copy(sizeBytes=31) else f
        }))
        assertNull(SnapshotRemotePriorityComparison.plan(saved, observed, good.mapIndexed { index, f ->
            if (index == 1) f.copy(priority=9) else f
        }))
        assertNull(SnapshotRemotePriorityComparison.plan(
            saved.copy(decision=SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW),
            observed, good))
    }
}
