package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class StorageCleanupPreviewTest {
    private fun objectAt(
        kind: CleanupObjectKind = CleanupObjectKind.ENGINE_BACKING,
        references: Set<String> = setOf("A".repeat(40)),
        bytes: Long? = 500,
        owner: Boolean = true,
        fresh: Boolean = true,
        writer: Boolean = false,
        shared: Int = 0,
        id: String = "inode:101"
    ) = CleanupPhysicalObject("volume-A", id, kind, bytes, owner, fresh, writer,
        shared, references)

    @Test fun extractedArchiveSourceStillWarnsThatSeedingWillBreak() {
        val result = StorageCleanupPreview.inspect(objectAt())
        assertEquals(CleanupDisposition.SEEDING_IMPACT, result.disposition)
        assertTrue(result.explanation.contains("sharing"))
        assertTrue(result.explanation.contains("Extracted"))
        assertEquals(500L, result.potentialReleasedBytes)
    }

    @Test fun independentResultMayBeCandidateButCannotPromiseExactSavings() {
        val result = StorageCleanupPreview.inspect(
            objectAt(kind = CleanupObjectKind.EXTRACTED_RESULT, references = emptySet()))
        assertEquals(CleanupDisposition.SAFE_CANDIDATE, result.disposition)
        assertEquals(500L, result.potentialReleasedBytes)
        assertTrue(result.explanation.contains("verify"))
    }

    @Test fun missingOwnershipStaleInventoryBusyWritersAndSharedInodesBlock() {
        for (candidate in listOf(
            objectAt(owner = false), objectAt(fresh = false),
            objectAt(writer = true), objectAt(shared = 1),
            objectAt(references = emptySet())
        )) {
            val result = StorageCleanupPreview.inspect(candidate)
            assertEquals(CleanupDisposition.BLOCKED, result.disposition)
            assertNull(result.potentialReleasedBytes)
        }
    }

    @Test fun multipleTorrentsStillRequireExplicitSeedingImpact() {
        val result = StorageCleanupPreview.inspect(objectAt(references = setOf("A", "B")))
        assertEquals(CleanupDisposition.SEEDING_IMPACT, result.disposition)
    }

    @Test fun unknownSizeIsNotAssumedToBeZero() {
        val result = StorageCleanupPreview.inspect(objectAt(
            kind = CleanupObjectKind.APP_STAGING, references = emptySet(), bytes = null))
        assertEquals(CleanupDisposition.SAFE_CANDIDATE, result.disposition)
        assertNull(result.potentialReleasedBytes)
        assertNull(StorageCleanupPreview.inspectBatch(listOf(
            objectAt(kind = CleanupObjectKind.APP_STAGING, references = emptySet(), bytes = null)
        )).potentialReleasedBytes)
    }

    @Test fun batchAvoidsCountingSamePhysicalIdentityTwice() {
        val same = objectAt(kind = CleanupObjectKind.EXTRACTED_RESULT, references = emptySet())
        assertThrows(IllegalArgumentException::class.java) {
            StorageCleanupPreview.inspectBatch(listOf(same, same))
        }
    }

    @Test fun batchHandlesArithmeticOverflowWithoutInventingReclaimedSpace() {
        val a = objectAt(kind = CleanupObjectKind.EXTRACTED_RESULT,
            references = emptySet(), bytes = Long.MAX_VALUE, id = "inode:a")
        val b = objectAt(kind = CleanupObjectKind.APP_STAGING,
            references = emptySet(), bytes = 1, id = "inode:b")
        val result = StorageCleanupPreview.inspectBatch(listOf(a, b))
        assertNull(result.potentialReleasedBytes)
        assertEquals(2, result.items.size)
    }

    @Test fun rejectsInvalidMeasurementsAndIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            objectAt(bytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            objectAt(shared = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            objectAt(id = "")
        }
    }
}
