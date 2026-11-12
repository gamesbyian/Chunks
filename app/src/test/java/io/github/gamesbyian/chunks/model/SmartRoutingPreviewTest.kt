package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Classification
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Destination
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Evidence
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Kind
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Outcome
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Rule
import org.junit.Assert.*
import org.junit.Test

/** Pure planning gates. They neither instantiate the local device engine nor send RPCs. */
class SmartRoutingPreviewTest {
    private val local = Destination("local", true, true, true)
    private val server = Destination("rtorrent:account-a", true, true, true)
    private val inaccessible = Destination("rtorrent:account-b", false, true, true)
    private fun rule(id: String, priority: Int, kind: Kind, target: String,
                     minimum: Int = 80) = Rule(id, priority, kind, minimum, target)

    @Test fun knownEpisodeIsClassifiedLocallyWithoutExternalMetadata() {
        val result = SmartRoutingPreview.classify(Evidence("Unrelated title",
            listOf("Show.S02E05.1080p.mkv")))
        assertEquals(Kind.TV, result.kind)
        assertEquals(96, result.confidence)
        assertEquals(listOf("file-extensions", "episode-pattern"), result.signals)
    }

    @Test fun ambiguousAndUnknownTorrentsAskInsteadOfInventingDestination() {
        val mixed = SmartRoutingPreview.classify(Evidence("bundle",
            listOf("music.flac", "readme.pdf")))
        assertNull(mixed.kind)
        assertTrue(mixed.ambiguous)
        assertEquals(Outcome.ASK, SmartRoutingPreview.decide(Evidence("bundle",
            listOf("music.flac", "readme.pdf")),
            listOf(rule("music", 100, Kind.MUSIC, local.environmentId)),
            listOf(local)).outcome)
        assertEquals(Outcome.ASK,
            SmartRoutingPreview.decide(Evidence("Unknown"),
                listOf(rule("archive", 1, Kind.ARCHIVE, local.environmentId)),
                listOf(local)).outcome)
    }

    @Test fun classificationDistinguishesConfidentComicsFromGenericVideo() {
        assertEquals(90, SmartRoutingPreview.classify(Evidence("Comic", listOf("01.cbz"))).confidence)
        assertEquals(Kind.COMIC,
            SmartRoutingPreview.classify(Evidence("Comic", listOf("01.cbz"))).kind)
        assertEquals(65,
            SmartRoutingPreview.classify(Evidence("movie", listOf("movie.mkv"))).confidence)
        assertEquals(Outcome.ASK, SmartRoutingPreview.decide(
            Evidence("movie", listOf("movie.mkv")),
            listOf(rule("movie", 1, Kind.MOVIE, server.environmentId, 80)),
            listOf(server)).outcome)
    }

    @Test fun higherPriorityRuleWinsAndSamePriorityUsesStableId() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val rules = listOf(
            rule("z", 10, Kind.TV, local.environmentId),
            rule("b", 20, Kind.TV, server.environmentId),
            rule("a", 20, Kind.TV, local.environmentId))
        val result = SmartRoutingPreview.decide(evidence, rules, listOf(local, server))
        assertEquals(Outcome.PROPOSED, result.outcome)
        assertEquals("a", result.ruleId)
        assertEquals(local.environmentId, result.proposedEnvironmentId)
        assertTrue(result.reason.contains("review-required"))
        assertEquals(result, SmartRoutingPreview.decide(evidence, rules.reversed(),
            listOf(server, local)))
    }

    @Test fun matchingRuleWhoseExactAccountIsUnavailableNeverFallsThrough() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val result = SmartRoutingPreview.decide(evidence,
            listOf(rule("highest", 99, Kind.TV, inaccessible.environmentId),
                rule("lower", 50, Kind.TV, local.environmentId)),
            listOf(inaccessible, local))
        assertEquals(Outcome.TARGET_UNAVAILABLE, result.outcome)
        assertEquals("highest", result.ruleId)
        assertNull(result.proposedEnvironmentId)
        assertEquals(Outcome.TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, listOf(rule("missing", 100, Kind.TV, "rtorrent:unknown")),
            listOf(local)).outcome)
    }

    @Test fun explicitAccountOverridesRulesButNeverBypassesCapabilitiesOrPlace() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val rules = listOf(rule("auto", 100, Kind.TV, server.environmentId))
        val chosen = SmartRoutingPreview.decide(evidence, rules,
            listOf(local, server), explicitEnvironmentId = local.environmentId)
        assertEquals(Outcome.PROPOSED, chosen.outcome)
        assertNull(chosen.ruleId)
        assertEquals(local.environmentId, chosen.proposedEnvironmentId)
        val missingPlace = local.copy(verifiedPlace = false)
        assertEquals(Outcome.EXPLICIT_TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, rules, listOf(missingPlace, server),
            explicitEnvironmentId = local.environmentId).outcome)
        assertEquals(Outcome.EXPLICIT_TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, rules, listOf(local, local),
            explicitEnvironmentId = local.environmentId).outcome)
    }

    @Test fun confidenceThresholdIsStrictAndNeverCausesAutomaticFallback() {
        val evidence = Evidence("film.mkv")
        val low = rule("film", 100, Kind.MOVIE, local.environmentId, 66)
        assertEquals(Outcome.ASK, SmartRoutingPreview.decide(
            evidence, listOf(low), listOf(local)).outcome)
        assertEquals(Outcome.PROPOSED, SmartRoutingPreview.decide(
            evidence, listOf(low.copy(minimumConfidence = 65)), listOf(local)).outcome)
    }

    @Test fun audiobookContainerIsRecognizedButGenericDiscImageIsNotAProvenGame() {
        val audio = SmartRoutingPreview.classify(Evidence("Chapter one",
            listOf("Chapter 01.m4b")))
        assertEquals(Kind.AUDIOBOOK, audio.kind)
        assertEquals(95, audio.confidence)
        val disk = SmartRoutingPreview.classify(Evidence("unknown", listOf("image.iso")))
        assertNull(disk.kind)
        assertEquals(0, disk.confidence)
        assertEquals(Outcome.ASK, SmartRoutingPreview.decide(
            Evidence("unknown", listOf("image.iso")),
            listOf(rule("games", 100, Kind.GAME, local.environmentId)),
            listOf(local)).outcome)
    }

    @Test fun duplicateRuleIdsCannotSelectDifferentAccountsByCallerOrder() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val left = rule("ambiguous", 50, Kind.TV, local.environmentId)
        val right = rule("ambiguous", 50, Kind.TV, server.environmentId)
        assertThrows(IllegalArgumentException::class.java) {
            SmartRoutingPreview.decide(evidence, listOf(left, right), listOf(local, server))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartRoutingPreview.decide(evidence, listOf(right, left), listOf(local, server))
        }
    }

    @Test fun evidenceAndRulesRejectMalformedOrUnboundedInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            Evidence("bad\u0000tracker")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Evidence("large", List(2001) { "clip.mkv" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule("invalid", 1, Kind.TV, 101, local.environmentId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Destination("remote:\u0000account", true, true, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule("tab\tidentity", 1, Kind.TV, 95, local.environmentId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule("id", 1, Kind.TV, 95, "remote:\u0000secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartRoutingPreview.decide(Evidence("x"), emptyList(),
                List(101) { Destination("server-${it}", true, true, true) })
        }
    }
}
