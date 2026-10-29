package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Decision
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.FileRole
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Observed
import org.junit.Assert.*
import org.junit.Test

class FinishInOrderPlannerTest {
    private fun file(index: Int, name: String, priority: Int = 4, wanted: Boolean = true) =
        LocalTransferFile(index, "Show/$name", 1000, 0, wanted, if (wanted) priority else 0)

    private fun seen(file: LocalTransferFile, verified: Long = 0, blocked: Boolean = false) =
        Observed(file, verified, blocked)

    /** 26 episodes whose native indexes are deliberately interleaved. */
    private val season = (1..26).map { episode ->
        file((episode * 7) % 29, "Show S01E${episode.toString().padStart(2, '0')}.mkv")
    }

    @Test fun suggestsNaturalEpisodeOrderAndExcludesExtrasAndSidecars() {
        val files = season + listOf(file(40, "Show S01E01.srt"), file(41, "Trailer.mkv"),
            file(42, "Show S01E05.sample.mkv"))
        val suggestion = requireNotNull(FinishInOrderPlanner.suggest(files))
        assertEquals(FinishInOrderPlanner.OrderSource.EPISODES, suggestion.source)
        assertEquals(season.map { it.index }, suggestion.order)
    }

    @Test fun suggestsDigitAwareFilenameOrderForNumberedSets() {
        val files = listOf(file(0, "Chapter 10.mp3"), file(1, "Chapter 2.mp3"),
            file(2, "Chapter 1.mp3"), file(3, "cover.jpg"), file(4, "Chapter 03.mp3"))
        val suggestion = requireNotNull(FinishInOrderPlanner.suggest(files))
        assertEquals(FinishInOrderPlanner.OrderSource.FILENAMES, suggestion.source)
        assertEquals(listOf(2, 1, 4, 0), suggestion.order)
        assertNull(FinishInOrderPlanner.suggest(listOf(file(0, "alpha.mkv"), file(1, "beta.mkv"))))
    }

    @Test fun activeFileIsHighLookaheadNormalAndRestLowWithoutTouchingSkipped() {
        val files = season.take(5) + file(30, "Show S01E06.mkv", wanted = false)
        val policy = FinishInOrderPlanner.policyFor(Mode.BALANCED, files, files.map { it.index })
        val plan = FinishInOrderPlanner.plan(policy, files.map { seen(it) }, emptyMap(), emptySet())
            as Decision.Plan
        assertEquals(files[0].index, plan.active)
        assertEquals(mapOf(files[0].index to 7, files[1].index to 4, files[2].index to 1,
            files[3].index to 1, files[4].index to 1), plan.desired)
        // Already-Normal lookahead needs no native write.
        assertEquals(plan.desired - files[1].index,
            plan.changes.associate { it.index to it.requestedPriority })
        assertEquals(FileRole.SKIPPED, plan.roles[30])
        assertTrue(plan.changes.none { it.index == 30 })
        assertTrue(plan.changes.all { it.expectedPriority == 4 })
    }

    @Test fun advancesOnlyAfterEveryPieceIsHashVerified() {
        val files = season.take(3)
        val policy = FinishInOrderPlanner.policyFor(Mode.FINISH_FIRST, files, files.map { it.index })
        val applied = mapOf(files[0].index to 7, files[1].index to 1, files[2].index to 1)
        val current = files.map { it.copy(priority = applied.getValue(it.index)) }
        val almost = FinishInOrderPlanner.plan(policy,
            listOf(seen(current[0], 999), seen(current[1]), seen(current[2])), applied, emptySet())
            as Decision.Plan
        assertEquals(files[0].index, almost.active)
        assertTrue(almost.changes.isEmpty())
        val done = FinishInOrderPlanner.plan(policy,
            listOf(seen(current[0], 1000), seen(current[1]), seen(current[2])), applied, emptySet())
            as Decision.Plan
        assertEquals(files[1].index, done.active)
        assertEquals(FileRole.DONE, done.roles[files[0].index])
        assertEquals(listOf(FinishInOrderPlanner.Change(files[1].index, 1, 7)), done.changes)
    }

    @Test fun manualPriorityChangePinsFileAndIsNeverOverwritten() {
        val files = season.take(3)
        val policy = FinishInOrderPlanner.policyFor(Mode.BALANCED, files, files.map { it.index })
        val applied = mapOf(files[0].index to 7, files[1].index to 4, files[2].index to 1)
        val userRaised = files.mapIndexed { i, f ->
            f.copy(priority = if (i == 2) 7 else applied.getValue(f.index)) }
        val plan = FinishInOrderPlanner.plan(policy, userRaised.map { seen(it) }, applied, emptySet())
            as Decision.Plan
        assertEquals(setOf(files[2].index), plan.newlyPinned)
        assertEquals(FileRole.PINNED, plan.roles[files[2].index])
        assertTrue(plan.changes.isEmpty())
    }

    @Test fun blockedActiveFileGetsExplicitStateAndAvailableFallback() {
        val files = season.take(4)
        val policy = FinishInOrderPlanner.policyFor(Mode.FINISH_FIRST, files, files.map { it.index })
        val plan = FinishInOrderPlanner.plan(policy, listOf(seen(files[0], blocked = true),
            seen(files[1], blocked = true), seen(files[2]), seen(files[3])), emptyMap(), emptySet())
            as Decision.Plan
        assertTrue(plan.activeBlocked)
        assertEquals(FileRole.ACTIVE, plan.roles[files[0].index])
        assertEquals(FileRole.FALLBACK, plan.roles[files[2].index])
        assertEquals(7, plan.changes.single { it.index == files[2].index }.requestedPriority)
        assertEquals(1, plan.changes.single { it.index == files[1].index }.requestedPriority)
    }

    @Test fun changedFileIdentityRefusesInsteadOfGuessing() {
        val files = season.take(3)
        val policy = FinishInOrderPlanner.policyFor(Mode.BALANCED, files, files.map { it.index })
        val renamed = listOf(seen(files[0].copy(path = "Show/Other.mkv")), seen(files[1]), seen(files[2]))
        assertTrue(FinishInOrderPlanner.plan(policy, renamed, emptyMap(), emptySet()) is Decision.Refused)
        assertTrue(FinishInOrderPlanner.plan(policy, listOf(seen(files[0]), seen(files[1])),
            emptyMap(), emptySet()) is Decision.Refused)
    }

    @Test fun finishesWhenEveryManagedFileIsComplete() {
        val files = season.take(2)
        val policy = FinishInOrderPlanner.policyFor(Mode.BALANCED, files, files.map { it.index })
        val plan = FinishInOrderPlanner.plan(policy, files.map { seen(it, 1000) }, emptyMap(), emptySet())
            as Decision.Plan
        assertTrue(plan.finished)
        assertTrue(plan.changes.isEmpty())
    }

    @Test fun recordRoundTripsAndRejectsMalformedState() {
        val files = listOf(file(3, "Odd|name %2F, = ok.mkv"), file(9, "Show S01E02.mkv"))
        val record = FinishInOrderRecord(
            FinishInOrderPlanner.policyFor(Mode.FINISH_FIRST, files, listOf(3, 9)),
            lastApplied = mapOf(3 to 7), pinned = setOf(9),
            pending = listOf(FinishInOrderPlanner.Change(3, 4, 7)),
            suspendedReason = "Interrupted, review: now")
        assertEquals(record, FinishInOrderRecord.decode(record.encode()))
        assertNull(FinishInOrderRecord.decode("v=2\nmode=BALANCED"))
        assertNull(FinishInOrderRecord.decode(record.encode().replace("applied=3:7", "applied=5:7")))
    }

    @Test fun reorderKeepsPinsAndBaselinesButNeverOverridesAnUnresolvedJournal() {
        val files = season.take(3)
        val ids = files.map { it.index }
        val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(Mode.BALANCED, files, ids),
            lastApplied = mapOf(ids[0] to 7, ids[1] to 4), pinned = setOf(ids[2]), finished = true)
        val swapped = requireNotNull(record.reordered(
            FinishInOrderPlanner.policyFor(Mode.FINISH_FIRST, files, listOf(ids[1], ids[2]))))
        assertEquals(listOf(ids[1], ids[2]), swapped.policy.order.map { it.index })
        assertEquals(mapOf(ids[1] to 4), swapped.lastApplied)
        assertEquals(setOf(ids[2]), swapped.pinned)
        assertFalse(swapped.finished)
        assertNull(record.copy(pending = listOf(FinishInOrderPlanner.Change(ids[0], 4, 7)))
            .reordered(swapped.policy))
    }
}
