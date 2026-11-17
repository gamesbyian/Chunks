package io.github.gamesbyian.chunks.model

/**
 * The single, engine-neutral admission boundary for a user-reviewed Add.
 *
 * It chooses no destination, performs no native/RPC work, and never retries.
 * Local and remote callers supply their own independently verified capability
 * snapshot and existing mutation gate. The frozen coordinator ticket is the
 * only authority for the subsequent engine-specific write.
 *
 * No source or credential is ever persisted or logged here.
 */
internal object UniversalAddAdmission {
    enum class Refusal {
        REVIEW, AUTHORIZATION, MUTATION_GATE, TICKET
    }

    data class Decision(
        val ticket: UniversalAddCoordinator.Ticket?,
        /** The caller must settle its existing engine mutation gate if true. */
        val mutationGateAcquired: Boolean,
        val refusal: Refusal? = null,
        val explanation: String = ""
    ) {
        val accepted: Boolean get() = ticket != null
    }

    /**
     * Must run on the explicit Add click's serialized admission path, before
     * entering a suspending native/RPC write. An already attempted ticket can
     * never pass [UniversalAddCoordinator.review] again by accident.
     */
    fun authorize(
        coordinator: UniversalAddCoordinator,
        intent: UniversalAddIntent,
        capabilities: EnvironmentCapabilitySnapshot,
        availability: EnvironmentAvailability,
        acquireMutationGate: () -> Boolean,
        submittedAtEpochMillis: Long
    ): Decision {
        val review = coordinator.review(intent, capabilities, availability)
        if (!review.accepted)
            return Decision(null, false, Refusal.REVIEW, review.reason)
        if (!coordinator.authorizeByUser())
            return Decision(null, false, Refusal.AUTHORIZATION,
                "The Add review has changed. Review it again.")
        if (!acquireMutationGate())
            return Decision(null, false, Refusal.MUTATION_GATE,
                "Another client operation must settle before adding.")
        val ticket = coordinator.beginSubmission(submittedAtEpochMillis)
        return if (ticket != null) Decision(ticket, true)
        else Decision(null, true, Refusal.TICKET,
            "The Add could not acquire a one-time submission ticket.")
    }
}
