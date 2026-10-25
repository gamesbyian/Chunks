package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/** A preview-only, Chunks-local display label; no RPC or filesystem authority. */
data class AliasReviewProposal(
    val hash: String,
    val originalName: String,
    val suggestedName: String,
    val explanation: String,
    val alternatives: List<DisplayNameSuggestion>
)

/** A specifically reviewed name, scoped to the selected account at storage time. */
data class AliasReviewWrite(val hash: String, val originalName: String, val alias: String)

object AliasReviewPlanner {
    const val MAX_REVIEW_ITEMS = 100
    private val v1Hash = Regex("[0-9a-fA-F]{40}")

    /**
     * Current-view suggestions only. Never replace a user alias, never silently
     * infer metadata, and never allow a duplicate hash to enter a batch.
     */
    fun discover(
        transfers: List<RemoteTransfer>,
        hasAlias: (RemoteTransfer) -> Boolean,
        limit: Int = MAX_REVIEW_ITEMS
    ): List<AliasReviewProposal> {
        require(limit in 1..MAX_REVIEW_ITEMS)
        val seen = HashSet<String>()
        val results = ArrayList<AliasReviewProposal>()
        for (item in transfers) {
            if (!v1Hash.matches(item.hash) ||
                !seen.add(item.hash.uppercase(java.util.Locale.ROOT)) ||
                hasAlias(item)) continue
            val choices = TransferDisplayNames.suggestions(item.name)
            val first = choices.firstOrNull() ?: continue
            results += AliasReviewProposal(
                item.hash, item.name, first.name, first.explanation, choices
            )
            if (results.size == limit) break
        }
        return results
    }

    /**
     * Validate the entire batch before any persistence. Every selected identity
     * must originate in this exact preview; no silent mass-apply or empty draft.
     */
    fun prepare(
        proposals: List<AliasReviewProposal>,
        selectedNames: Map<String, String>
    ): List<AliasReviewWrite> {
        require(selectedNames.isNotEmpty() && selectedNames.size <= MAX_REVIEW_ITEMS) {
            "Choose between 1 and 100 names to review."
        }
        val allowed = proposals.associateBy { it.hash }
        require(allowed.size == proposals.size) { "Duplicate transfer identity in review." }
        require(selectedNames.keys.all { it in allowed }) { "Review contains a stale transfer." }
        return selectedNames.map { (hash, text) ->
            val proposal = requireNotNull(allowed[hash])
            require(v1Hash.matches(hash)) { "Unsupported transfer hash." }
            val alias = TransferDisplayNames.validateAlias(text)
            require(alias != proposal.originalName) {
                "A proposed name matches the original; leave it unchecked instead."
            }
            AliasReviewWrite(hash, proposal.originalName, alias)
        }
    }
}
