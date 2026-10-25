package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class AliasReviewPlannerTest {
    private val a = "A".repeat(40)
    private val b = "B".repeat(40)
    private val c = "C".repeat(40)
    private val episode = "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv"

    private fun transfer(hash: String, title: String) =
        RemoteTransfer(hash, title, 100, 50, true, "/server/library")

    @Test fun detectsConservativeSuggestionsWithoutTouchingRemoteTransferFields() {
        val original = transfer(a, episode)
        val plans = AliasReviewPlanner.discover(listOf(
            original,
            transfer(b, "file.unknown"),
            transfer(c, "A.Movie.2024.1080p.mkv")
        ), { false })
        assertEquals(listOf(a, c), plans.map { it.hash })
        assertEquals("Celebrity Jeopardy S04E02", plans.first().suggestedName)
        assertFalse(plans.any { it.suggestedName.contains("Chalamet") })
        assertEquals(episode, original.name)
        assertEquals("/server/library", original.directory)
    }

    @Test fun skipsExistingAliasesAndDuplicateOrInvalidServerHashes() {
        val plans = AliasReviewPlanner.discover(listOf(
            transfer(a, episode),
            transfer(a.lowercase(), "Different.Movie.2020.mp4"),
            transfer("bad", "Show.S01E01.mkv"),
            transfer(b, "Series.S01E02.mkv")
        ), { it.hash == b })
        assertEquals(listOf(a), plans.map { it.hash })
    }

    @Test fun explicitBatchValidatesEveryDraftBeforeAnyWrite() {
        val proposals = AliasReviewPlanner.discover(
            listOf(transfer(a, episode), transfer(b, "A.Movie.2024.1080p.mkv")),
            { false }
        )
        val writes = AliasReviewPlanner.prepare(proposals, linkedMapOf(
            a to " Celeb Jeopardy S04E02 ",
            b to "A Movie (2024)"
        ))
        assertEquals(2, writes.size)
        assertEquals("Celeb Jeopardy S04E02", writes.first().alias)
        assertEquals(a, writes.first().hash)
        assertEquals(episode, writes.first().originalName)
        for (bad in listOf("", "  ", "x".repeat(161), "trick\u202Ename", "a\nb")) {
            assertThrows(IllegalArgumentException::class.java) {
                AliasReviewPlanner.prepare(proposals, mapOf(a to bad))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AliasReviewPlanner.prepare(proposals, mapOf(c to "Unknown"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AliasReviewPlanner.prepare(proposals, emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AliasReviewPlanner.prepare(proposals, mapOf(a to episode))
        }
    }

    @Test fun boundedReviewAndUnsupportedUnknownNamesAbstain() {
        val many = (0 until 120).map { i ->
            transfer(i.toString(16).padStart(40, '0'), "Example.Series.S01E02.1080p.mkv")
        }
        assertEquals(100, AliasReviewPlanner.discover(many, { false }).size)
        assertEquals(3, AliasReviewPlanner.discover(many, { false }, limit = 3).size)
        assertThrows(IllegalArgumentException::class.java) {
            AliasReviewPlanner.discover(many, { false }, limit = 101)
        }
    }
}
