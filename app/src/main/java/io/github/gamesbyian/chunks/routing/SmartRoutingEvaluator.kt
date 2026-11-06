package io.github.gamesbyian.chunks.routing

import io.github.gamesbyian.chunks.media.MediaConfidence
import io.github.gamesbyian.chunks.media.MediaFileHint
import io.github.gamesbyian.chunks.media.MediaKind
import io.github.gamesbyian.chunks.media.MediaRecognizer
import io.github.gamesbyian.chunks.model.EnvironmentId
import java.util.Locale

/** Already authorized observations. Null inventories mean unknown, not an observed empty set. */
data class RoutingEvidence(
    val revision: String,
    val originalName: String,
    val files: List<MediaFileHint>? = null,
    /** Hostnames only; callers must strip tracker URLs, credentials and passkeys. */
    val trackerHosts: Set<String>? = null,
    /** Optional previously resolved identity; evaluating rules never contacts a provider. */
    val resolvedKind: MediaKind? = null
) {
    init {
        require(revision.isNotBlank() && revision.length <= 128 && revision.none(Char::isISOControl))
        require(trackerHosts == null || trackerHosts.all { canonicalHost(it) != null })
    }
}

sealed interface RoutingCondition {
    data class Extension(val value: String) : RoutingCondition {
        init { require(value.removePrefix(".").matches(Regex("[a-zA-Z0-9]{1,16}"))) }
    }
    data class NameContains(val value: String) : RoutingCondition {
        init { require(value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)) }
    }
    data class Folder(val value: String) : RoutingCondition {
        init { require(value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' || it.isISOControl() }) }
    }
    data class TrackerHost(val value: String) : RoutingCondition {
        init { require(canonicalHost(value) != null) }
    }
    data class Kind(val value: MediaKind) : RoutingCondition {
        init { require(value != MediaKind.UNKNOWN) }
    }
}

/** List order is precedence. A rule's conditions are ANDed; no implicit catch-all rule. */
data class RoutingRule(
    val id: String,
    val version: Int,
    val destination: EnvironmentId,
    val conditions: List<RoutingCondition>,
    val minimumConfidence: MediaConfidence = MediaConfidence.MEDIUM,
    val enabled: Boolean = true
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,96}")) && version > 0)
        require(conditions.size in 1..16)
    }
}

sealed interface RoutingFallback {
    data object Ask : RoutingFallback
    data class Target(val environment: EnvironmentId) : RoutingFallback
}

enum class RoutingEvidenceField { FILES, TRACKERS, CLASSIFICATION }
enum class RoutingRuleStatus { DISABLED, NOT_MATCHED, MISSING_EVIDENCE, LOW_CONFIDENCE, MATCHED }
enum class RoutingSelection { RULE, USER_OVERRIDE, FALLBACK, ASK }
enum class RoutingReason { MATCHED_RULE, USER_SELECTED, NO_QUALIFYING_RULE, RULE_TARGET_UNAVAILABLE, OVERRIDE_UNAVAILABLE, FALLBACK_UNAVAILABLE }

data class RoutingRuleExplanation(
    val ruleId: String,
    val ruleVersion: Int,
    val destination: EnvironmentId,
    val status: RoutingRuleStatus,
    val confidence: MediaConfidence,
    val missingEvidence: Set<RoutingEvidenceField> = emptySet()
)

/** A dry run, never an Add authorization or a move request. Contains no filenames or tracker URLs. */
data class RoutingPreview(
    val evidenceRevision: String,
    val destination: EnvironmentId?,
    val selection: RoutingSelection,
    val reason: RoutingReason,
    val selectedRuleId: String?,
    val rules: List<RoutingRuleExplanation>
)

object SmartRoutingEvaluator {
    /** Available targets are exact configured identities, supplied by the current capability/session owner. */
    fun preview(
        evidence: RoutingEvidence,
        orderedRules: List<RoutingRule>,
        fallback: RoutingFallback,
        availableTargets: Set<EnvironmentId>,
        userOverride: EnvironmentId? = null
    ): RoutingPreview {
        require(orderedRules.map { it.id }.distinct().size == orderedRules.size) { "Rule IDs must be unique" }
        val classification = MediaRecognizer.recognize(evidence.originalName, evidence.files.orEmpty())
        val resolvedKind = evidence.resolvedKind?.takeUnless { it == MediaKind.UNKNOWN }
        val explanations = orderedRules.map { rule ->
            if (!rule.enabled) RoutingRuleExplanation(rule.id, rule.version, rule.destination,
                RoutingRuleStatus.DISABLED, MediaConfidence.NONE)
            else {
                val matches = rule.conditions.map { condition ->
                    when (condition) {
                        is RoutingCondition.Extension -> evidence.files?.let { files ->
                            Match(files.any { leaf(it.path).substringAfterLast('.', "").equals(condition.value.removePrefix("."), true) }, MediaConfidence.HIGH)
                        } ?: Match(null, missing = RoutingEvidenceField.FILES)
                        is RoutingCondition.Folder -> evidence.files?.let { files ->
                            Match(files.any { file -> file.path.split('/', '\\').dropLast(1).any { it.equals(condition.value, true) } }, MediaConfidence.HIGH)
                        } ?: Match(null, missing = RoutingEvidenceField.FILES)
                        is RoutingCondition.NameContains -> Match(evidence.originalName.lowercase(Locale.ROOT)
                            .contains(condition.value.lowercase(Locale.ROOT)), MediaConfidence.MEDIUM)
                        is RoutingCondition.TrackerHost -> evidence.trackerHosts?.let { hosts ->
                            Match(hosts.any { canonicalHost(it) == canonicalHost(condition.value) }, MediaConfidence.HIGH)
                        } ?: Match(null, missing = RoutingEvidenceField.TRACKERS)
                        is RoutingCondition.Kind -> when {
                            resolvedKind != null -> Match(resolvedKind == condition.value, MediaConfidence.HIGH)
                            classification.kind == MediaKind.UNKNOWN -> Match(null, missing = RoutingEvidenceField.CLASSIFICATION)
                            else -> Match(classification.kind == condition.value, classification.confidence)
                        }
                    }
                }
                val confidence = matches.minOf { it.confidence }
                val status = when {
                    matches.any { it.matched == false } -> RoutingRuleStatus.NOT_MATCHED
                    matches.any { it.matched == null } -> RoutingRuleStatus.MISSING_EVIDENCE
                    confidence < rule.minimumConfidence -> RoutingRuleStatus.LOW_CONFIDENCE
                    else -> RoutingRuleStatus.MATCHED
                }
                RoutingRuleExplanation(rule.id, rule.version, rule.destination, status, confidence,
                    matches.mapNotNull { it.missing }.toSet())
            }
        }
        val winner = explanations.firstOrNull { it.status == RoutingRuleStatus.MATCHED }
        fun result(target: EnvironmentId?, selection: RoutingSelection, reason: RoutingReason, ruleId: String? = winner?.ruleId) =
            RoutingPreview(evidence.revision, target, selection, reason, ruleId, explanations)
        if (userOverride != null) return if (userOverride in availableTargets)
            result(userOverride, RoutingSelection.USER_OVERRIDE, RoutingReason.USER_SELECTED, null)
        else result(null, RoutingSelection.ASK, RoutingReason.OVERRIDE_UNAVAILABLE, null)
        if (winner != null && winner.destination in availableTargets)
            return result(winner.destination, RoutingSelection.RULE, RoutingReason.MATCHED_RULE)
        val reason = if (winner == null) RoutingReason.NO_QUALIFYING_RULE else RoutingReason.RULE_TARGET_UNAVAILABLE
        return when (fallback) {
            RoutingFallback.Ask -> result(null, RoutingSelection.ASK, reason)
            is RoutingFallback.Target -> if (fallback.environment in availableTargets)
                result(fallback.environment, RoutingSelection.FALLBACK, reason)
            else result(null, RoutingSelection.ASK, RoutingReason.FALLBACK_UNAVAILABLE)
        }
    }

    private data class Match(val matched: Boolean?, val confidence: MediaConfidence = MediaConfidence.NONE,
                             val missing: RoutingEvidenceField? = null)
    private fun leaf(path: String) = path.substringAfterLast('/').substringAfterLast('\\')
}

private fun canonicalHost(value: String): String? {
    val host = value.lowercase(Locale.ROOT).removeSuffix(".")
    if (host.length !in 1..253) return null
    return host.takeIf { it.split('.').all { label -> label.length in 1..63 &&
        label.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) } }
}
