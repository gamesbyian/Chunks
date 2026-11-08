package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Classification
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Destination
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Evidence
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Kind
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Outcome
import io.github.gamesbyian.chunks.model.SmartRoutingPreview.Rule
import org.junit.Assert.*
import org.junit.Test

/** Pure planning gates. They neither instantiate the phone engine nor send RPCs. */
class SmartRoutingPreviewTest {
    private val phone = Destination("phone", true, true, true)
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
            listOf(rule("music", 100, Kind.MUSIC, phone.environmentId)),
            listOf(phone)).outcome)
        assertEquals(Outcome.ASK,
            SmartRoutingPreview.decide(Evidence("Unknown"),
                listOf(rule("archive", 1, Kind.ARCHIVE, phone.environmentId)),
                listOf(phone)).outcome)
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
            rule("z", 10, Kind.TV, phone.environmentId),
            rule("b", 20, Kind.TV, server.environmentId),
            rule("a", 20, Kind.TV, phone.environmentId))
        val result = SmartRoutingPreview.decide(evidence, rules, listOf(phone, server))
        assertEquals(Outcome.PROPOSED, result.outcome)
        assertEquals("a", result.ruleId)
        assertEquals(phone.environmentId, result.proposedEnvironmentId)
        assertTrue(result.reason.contains("review-required"))
        assertEquals(result, SmartRoutingPreview.decide(evidence, rules.reversed(),
            listOf(server, phone)))
    }

    @Test fun matchingRuleWhoseExactAccountIsUnavailableNeverFallsThrough() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val result = SmartRoutingPreview.decide(evidence,
            listOf(rule("highest", 99, Kind.TV, inaccessible.environmentId),
                rule("lower", 50, Kind.TV, phone.environmentId)),
            listOf(inaccessible, phone))
        assertEquals(Outcome.TARGET_UNAVAILABLE, result.outcome)
        assertEquals("highest", result.ruleId)
        assertNull(result.proposedEnvironmentId)
        assertEquals(Outcome.TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, listOf(rule("missing", 100, Kind.TV, "rtorrent:unknown")),
            listOf(phone)).outcome)
    }

    @Test fun explicitAccountOverridesRulesButNeverBypassesCapabilitiesOrPlace() {
        val evidence = Evidence("show", listOf("Show.S01E01.mkv"))
        val rules = listOf(rule("auto", 100, Kind.TV, server.environmentId))
        val chosen = SmartRoutingPreview.decide(evidence, rules,
            listOf(phone, server), explicitEnvironmentId = phone.environmentId)
        assertEquals(Outcome.PROPOSED, chosen.outcome)
        assertNull(chosen.ruleId)
        assertEquals(phone.environmentId, chosen.proposedEnvironmentId)
        val missingPlace = phone.copy(verifiedPlace = false)
        assertEquals(Outcome.EXPLICIT_TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, rules, listOf(missingPlace, server),
            explicitEnvironmentId = phone.environmentId).outcome)
        assertEquals(Outcome.EXPLICIT_TARGET_UNAVAILABLE, SmartRoutingPreview.decide(
            evidence, rules, listOf(phone, phone),
            explicitEnvironmentId = phone.environmentId).outcome)
    }

    @Test fun confidenceThresholdIsStrictAndNeverCausesAutomaticFallback() {
        val evidence = Evidence("film.mkv")
        val low = rule("film", 100, Kind.MOVIE, phone.environmentId, 66)
        assertEquals(Outcome.ASK, SmartRoutingPreview.decide(
            evidence, listOf(low), listOf(phone)).outcome)
        assertEquals(Outcome.PROPOSED, SmartRoutingPreview.decide(
            evidence, listOf(low.copy(minimumConfidence = 65)), listOf(phone)).outcome)
    }

    @Test fun evidenceAndRulesRejectMalformedOrUnboundedInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            Evidence("bad\u0000tracker")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Evidence("large", List(2001) { "clip.mkv" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule("invalid", 1, Kind.TV, 101, phone.environmentId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartRoutingPreview.decide(Evidence("x"), emptyList(),
                List(101) { Destination("server-${it}", true, true, true) })
        }
    }
}
