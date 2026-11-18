package io.github.gamesbyian.chunks.model

/**
 * Smart Routing's first integration with Universal Add: a *suggestion* for
 * the shared target picker, computed from the persisted policy, the reviewed
 * source's own name/file list and the exact destinations that can accept an
 * Add right now (Local, the authenticated rTorrent session, and connected
 * Transmission/qBittorrent/Deluge clients with a live session).
 *
 * Never selects a target by itself: the person taps the suggestion, and the
 * one Add submission then re-checks capability, folder and state.
 */
internal object SmartRoutingAddSuggestion {
    data class Candidate(
        val environmentId: String,
        val label: String,
        /** Authenticated live session (Local: engine running). */
        val live: Boolean,
        /** This environment can accept this kind of source now. */
        val acceptsAdd: Boolean,
        /** A default or chosen Save to folder is known for it. */
        val placeKnown: Boolean
    )

    data class Suggestion(
        val environmentId: String,
        val label: String,
        val ruleId: String,
        val kind: SmartRoutingPreview.Kind,
        val confidence: Int
    )

    sealed interface Result {
        data object Disabled : Result
        data object NoSource : Result
        /** Ask: ambiguous/unknown content or no confident rule. */
        data class Ask(val reason: String) : Result
        /** A rule matched, but its exact client cannot take the Add now. */
        data class Unavailable(val ruleId: String, val reason: String) : Result
        data class Suggested(val suggestion: Suggestion) : Result
    }

    fun evaluate(
        policy: SmartRoutingPolicyStore.Policy?,
        sourceTitle: String?,
        fileNames: List<String>,
        candidates: List<Candidate>
    ): Result {
        if (policy == null || !policy.enabled || policy.rules.isEmpty()) return Result.Disabled
        val title = sourceTitle?.takeIf { it.isNotBlank() }?.take(2048)?.filterNot { it.isISOControl() }
            ?: return Result.NoSource
        val evidence = SmartRoutingPreview.Evidence(title,
            fileNames.take(2000).map { it.take(4096).filterNot(Char::isISOControl) })
        val byId = candidates.groupBy { it.environmentId }
        val destinations = candidates.map {
            SmartRoutingPreview.Destination(it.environmentId, it.live, it.acceptsAdd, it.placeKnown)
        }.distinctBy { it.environmentId }
        // Two candidates claiming the same ID are ambiguous: never usable.
        val safe = destinations.filter { (byId[it.environmentId]?.size ?: 0) == 1 }
        val decision = SmartRoutingPreview.decide(evidence, policy.rules, safe)
        return when (decision.outcome) {
            SmartRoutingPreview.Outcome.PROPOSED -> {
                val id = requireNotNull(decision.proposedEnvironmentId)
                val label = byId[id]?.singleOrNull()?.label ?: return Result.Ask("ambiguous-target")
                Result.Suggested(Suggestion(id, label, requireNotNull(decision.ruleId),
                    requireNotNull(decision.classification.kind), decision.classification.confidence))
            }
            SmartRoutingPreview.Outcome.TARGET_UNAVAILABLE,
            SmartRoutingPreview.Outcome.EXPLICIT_TARGET_UNAVAILABLE ->
                Result.Unavailable(decision.ruleId.orEmpty(), decision.reason)
            SmartRoutingPreview.Outcome.ASK -> Result.Ask(decision.reason)
        }
    }

    /**
     * A protocol client's candidacy from its own live session: it must have
     * answered (live), support this source kind and the chosen Start mode,
     * and report a Save to folder. A client that has not answered yet is not
     * usable, so a matching rule says "unavailable" rather than guessing.
     */
    fun candidateFor(
        environmentId: String,
        label: String,
        session: io.github.gamesbyian.chunks.rpc.ClientSessionInfo?,
        magnetSource: Boolean,
        startNow: Boolean
    ): Candidate {
        val caps = session?.capabilities.orEmpty()
        val sourceOk = if (magnetSource) io.github.gamesbyian.chunks.rpc.ClientCapability.ADD_MAGNET in caps
            else io.github.gamesbyian.chunks.rpc.ClientCapability.ADD_TORRENT_FILE in caps
        val startOk = startNow || io.github.gamesbyian.chunks.rpc.ClientCapability.ADD_STOPPED in caps
        return Candidate(environmentId, label, live = session != null,
            acceptsAdd = sourceOk && startOk,
            placeKnown = session?.defaultDownloadDirectory != null)
    }

    /**
     * Whether Universal Add should pre-select [suggestion]'s client now. Only
     * with the explicit opt-in, at most once per reviewed source identity,
     * never while an Add is in flight, and never over a target the person
     * picked themselves for this source. Selection is a draft edit, not an Add.
     */
    fun shouldPreselect(
        policy: SmartRoutingPolicyStore.Policy?,
        suggestion: Suggestion?,
        sourceKey: String?,
        alreadyAppliedFor: String?,
        personChoseTargetFor: String?,
        alreadySelected: Boolean,
        addInFlight: Boolean
    ): Boolean = policy?.enabled == true && policy.autoSelect && suggestion != null &&
        sourceKey != null && alreadyAppliedFor != sourceKey && personChoseTargetFor != sourceKey &&
        !alreadySelected && !addInFlight

    fun kindLabel(kind: SmartRoutingPreview.Kind): String = when (kind) {
        SmartRoutingPreview.Kind.MOVIE -> "Movie"
        SmartRoutingPreview.Kind.TV -> "TV episode"
        SmartRoutingPreview.Kind.BOOK -> "Book"
        SmartRoutingPreview.Kind.COMIC -> "Comic"
        SmartRoutingPreview.Kind.MUSIC -> "Music"
        SmartRoutingPreview.Kind.AUDIOBOOK -> "Audiobook"
        SmartRoutingPreview.Kind.GAME -> "Game"
        SmartRoutingPreview.Kind.SOFTWARE -> "Software"
        SmartRoutingPreview.Kind.DOCUMENT -> "Document"
        SmartRoutingPreview.Kind.PICTURE -> "Pictures"
        SmartRoutingPreview.Kind.ARCHIVE -> "Archive"
    }
}
