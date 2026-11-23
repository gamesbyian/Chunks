package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalStoppedFilePriorityReview

/** The immutable choices from the archive paired with one exact stopped Local observation. */
internal class LocalSnapshotPriorityRestore private constructor(
    val target: LocalStoppedFilePriorityReview,
    val files: List<SnapshotFilePriorities.Mapping>
) {
    val changeCount: Int get() = files.zip(target.files).count { (saved, actual) ->
        saved.level.local != actual.priority
    }

    val desiredByIndex: Map<Int, Int> get() = files.associate { it.file.index to it.level.local }

    val wouldReselectSkippedFile: Boolean get() = files.zip(target.files).any { (saved, actual) ->
        actual.priority == 0 && saved.level != SnapshotFilePriorities.Level.SKIP
    }

    companion object {
        fun plan(
            entry: SnapshotRestorePreviewV1.Entry,
            target: LocalStoppedFilePriorityReview?
        ): LocalSnapshotPriorityRestore? {
            if (target == null || entry.decision != SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ||
                target.infoHash != entry.infoHashV1 || target.name != entry.originalName ||
                entry.fileMappings.size !in 1..SnapshotFilePriorities.MAX_FILES)
                return null
            val actual = target.files.map { SnapshotFilePriorities.File(it.index, it.path, it.sizeBytes) }
            if (entry.fileMappings.map { it.file } != actual) return null
            return LocalSnapshotPriorityRestore(target.copy(files = target.files.toList()),
                entry.fileMappings.toList())
        }
    }
}
