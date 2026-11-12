package io.github.gamesbyian.chunks.routing

import io.github.gamesbyian.chunks.media.*
import io.github.gamesbyian.chunks.model.EnvironmentId
import org.junit.Assert.*
import org.junit.Test

class SmartRoutingEvaluatorTest {
    private val local = EnvironmentId("local")
    private val films = EnvironmentId("server-films")
    private val books = EnvironmentId("server-books")
    private val targets = setOf(local, films, books)
    private fun evidence(name: String = "Example.mkv", files: List<MediaFileHint>? = listOf(MediaFileHint("Movies/Example.MKV")),
                         trackers: Set<String>? = emptySet()) = RoutingEvidence("fixture-v1", name, files, trackers)
    private fun rule(id: String, target: EnvironmentId, vararg conditions: RoutingCondition,
                     confidence: MediaConfidence = MediaConfidence.LOW, enabled: Boolean = true) =
        RoutingRule(id, 1, target, conditions.toList(), confidence, enabled)
    private val video = RoutingCondition.Extension("mkv")

    @Test fun firstQualifyingRuleWinsAndEveryCompetingRuleIsExplained() {
        val result = SmartRoutingEvaluator.preview(evidence(), listOf(
            rule("first", films, video), rule("second", local, video)), RoutingFallback.Ask, targets)
        assertEquals(films, result.destination)
        assertEquals("first", result.selectedRuleId)
        assertEquals(listOf(RoutingRuleStatus.MATCHED, RoutingRuleStatus.MATCHED), result.rules.map { it.status })
        assertEquals(RoutingSelection.RULE, result.selection)
        assertEquals("fixture-v1", result.evidenceRevision)
    }

    @Test fun explicitUserOverrideWinsAndUnavailableOverrideDoesNotSilentlyFallBack() {
        val rules = listOf(rule("video", films, video))
        val overridden = SmartRoutingEvaluator.preview(evidence(), rules, RoutingFallback.Ask, targets, local)
        assertEquals(local, overridden.destination)
        assertNull(overridden.selectedRuleId)
        val missing = EnvironmentId("removed-profile")
        val result = SmartRoutingEvaluator.preview(evidence(), rules, RoutingFallback.Target(books), targets, missing)
        assertNull(result.destination)
        assertEquals(RoutingSelection.ASK, result.selection)
        assertEquals(RoutingReason.OVERRIDE_UNAVAILABLE, result.reason)
    }

    @Test fun incompleteMagnetEvidenceRequiresAskAndDoesNotInventFileMatches() {
        val result = SmartRoutingEvaluator.preview(evidence("magnet title", files = null, trackers = null),
            listOf(rule("video", films, video)), RoutingFallback.Ask, targets)
        assertNull(result.destination)
        assertEquals(RoutingRuleStatus.MISSING_EVIDENCE, result.rules.single().status)
        assertEquals(setOf(RoutingEvidenceField.FILES), result.rules.single().missingEvidence)
    }

    @Test fun knownEmptyFilesAreANonMatchRatherThanMissingMetadata() {
        val result = SmartRoutingEvaluator.preview(evidence(files = emptyList()), listOf(rule("video", films, video)),
            RoutingFallback.Target(local), targets)
        assertEquals(RoutingRuleStatus.NOT_MATCHED, result.rules.single().status)
        assertEquals(local, result.destination)
        assertEquals(RoutingSelection.FALLBACK, result.selection)
    }

    @Test fun allConditionsMustMatchAndDefiniteMismatchWinsOverMissingEvidence() {
        val result = SmartRoutingEvaluator.preview(evidence("unrelated", files = null),
            listOf(rule("both", films, video, RoutingCondition.NameContains("movie"))), RoutingFallback.Ask, targets)
        assertEquals(RoutingRuleStatus.NOT_MATCHED, result.rules.single().status)
        assertNull(result.destination)
    }

    @Test fun extensionsUseActualLeafSuffixAndFoldersUseWholeSegments() {
        val input = evidence(files = listOf(MediaFileHint("NotMovies/fake.mkv.exe")))
        val result = SmartRoutingEvaluator.preview(input, listOf(
            rule("suffix", films, video), rule("folder", films, RoutingCondition.Folder("movies"))), RoutingFallback.Ask, targets)
        assertTrue(result.rules.all { it.status == RoutingRuleStatus.NOT_MATCHED })
        val proper = evidence(files = listOf(MediaFileHint("MOVIES\\feature.MKV")))
        assertEquals(films, SmartRoutingEvaluator.preview(proper, listOf(rule("both", films, video,
            RoutingCondition.Folder("movies"))), RoutingFallback.Ask, targets).destination)
    }

    @Test fun trackerIdentityIsExactHostAndNeverASubstringOrPasskeyUrl() {
        val rules = listOf(rule("tracker", films, RoutingCondition.TrackerHost("tracker.example.org")))
        assertNull(SmartRoutingEvaluator.preview(evidence(trackers = setOf("tracker.example.org.attacker.test")), rules,
            RoutingFallback.Ask, targets).destination)
        assertEquals(films, SmartRoutingEvaluator.preview(evidence(trackers = setOf("TRACKER.EXAMPLE.ORG")), rules,
            RoutingFallback.Ask, targets).destination)
        assertThrows(IllegalArgumentException::class.java) { RoutingCondition.TrackerHost("https://tracker.example.org/secret") }
    }

    @Test fun localClassifierIsReusedWithoutProviderConsentOrNetwork() {
        val input = evidence("Babylon.5.S02E07.mkv", listOf(MediaFileHint("Babylon.5.S02E07.mkv")))
        val result = SmartRoutingEvaluator.preview(input, listOf(rule("tv", films,
            RoutingCondition.Kind(MediaKind.TV_EPISODE), confidence = MediaConfidence.MEDIUM)), RoutingFallback.Ask, targets)
        assertEquals(films, result.destination)
    }

    @Test fun insufficientConfidenceUsesExplicitFallbackAndDisabledRulesAreInert() {
        val result = SmartRoutingEvaluator.preview(evidence(), listOf(
            rule("disabled", films, video, enabled = false),
            rule("weak-title", films, RoutingCondition.NameContains("example"), confidence = MediaConfidence.HIGH)),
            RoutingFallback.Target(local), targets)
        assertEquals(local, result.destination)
        assertEquals(listOf(RoutingRuleStatus.DISABLED, RoutingRuleStatus.LOW_CONFIDENCE), result.rules.map { it.status })
    }

    @Test fun unavailableWinningTargetUsesDeclaredFallbackAndNeverALowerRule() {
        val result = SmartRoutingEvaluator.preview(evidence(), listOf(rule("first", films, video), rule("second", books, video)),
            RoutingFallback.Target(local), setOf(local, books))
        assertEquals(local, result.destination)
        assertEquals("first", result.selectedRuleId)
        assertEquals(RoutingReason.RULE_TARGET_UNAVAILABLE, result.reason)
    }

    @Test fun unavailableFallbackAsksAndPreviewIsDeterministic() {
        val rules = listOf(rule("books", books, RoutingCondition.Extension("epub")))
        val first = SmartRoutingEvaluator.preview(evidence(), rules, RoutingFallback.Target(books), setOf(local))
        assertNull(first.destination)
        assertEquals(RoutingReason.FALLBACK_UNAVAILABLE, first.reason)
        repeat(10) { assertEquals(first, SmartRoutingEvaluator.preview(evidence(), rules, RoutingFallback.Target(books), setOf(local))) }
    }

    @Test fun duplicateRuleIdsAndUnconditionalRulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { rule("empty", local) }
        val same = rule("same", local, video)
        assertThrows(IllegalArgumentException::class.java) {
            SmartRoutingEvaluator.preview(evidence(), listOf(same, same), RoutingFallback.Ask, targets)
        }
    }
    @Test fun unknownResolvedKindDoesNotSuppressUsableLocalClassification() {
        val input = evidence("Babylon.5.S02E07.mkv").copy(resolvedKind = MediaKind.UNKNOWN)
        val result = SmartRoutingEvaluator.preview(input, listOf(rule("tv", films,
            RoutingCondition.Kind(MediaKind.TV_EPISODE))), RoutingFallback.Ask, targets)
        assertEquals(films, result.destination)
    }

}
