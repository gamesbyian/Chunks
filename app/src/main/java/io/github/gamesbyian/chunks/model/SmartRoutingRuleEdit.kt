package io.github.gamesbyian.chunks.model

/** Pure rule edit: changing destination or kind never reassigns ID, priority or confidence. */
internal object SmartRoutingRuleEdit {
    fun replace(
        rules: List<SmartRoutingPreview.Rule>,
        ruleId: String,
        kind: SmartRoutingPreview.Kind,
        targetEnvironmentId: String
    ): List<SmartRoutingPreview.Rule>? {
        if (rules.none { it.id == ruleId }) return null
        return rules.map { rule ->
            if (rule.id == ruleId) rule.copy(kind = kind, targetEnvironmentId = targetEnvironmentId)
            else rule
        }
    }
}
