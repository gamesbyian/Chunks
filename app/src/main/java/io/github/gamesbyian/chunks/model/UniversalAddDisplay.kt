package io.github.gamesbyian.chunks.model

/**
 * Client-neutral projection of an Add ticket for the user-facing receipt.
 *
 * The coordinator, never this projection, owns mutation authorization and
 * exact-session proof. Callers supply positive registration evidence only
 * when their adapter can attribute it to this submission. In particular,
 * finding a matching infohash after a lost RPC acknowledgement is NOT such
 * evidence. No network calls, engine casts, timers or retries belong here.
 */
internal enum class UniversalAddDisplayStage {
    SUBMITTING,
    REGISTERED_CHECKING,
    VERIFIED,
    SESSION_CONFIRMED,
    START_UNVERIFIED,
    UNCONFIRMED,
    NO_NEW_ADD
}

internal data class UniversalAddDisplay(
    val stage: UniversalAddDisplayStage,
    val title: String,
    val explanation: String,
    /** May show an exact-client transfer link only when the caller has the verified key. */
    val verifiedJobObserved: Boolean
)

internal object UniversalAddDisplayProjector {
    fun from(
        phase: UniversalAddCoordinator.Phase,
        clientLabel: String,
        submitting: Boolean = false,
        registrationFromThisSubmission: Boolean = false,
        knownNoWrite: Boolean = false
    ): UniversalAddDisplay? {
        val client = clientLabel.trim().ifEmpty { "this client" }
        // Coordinator evidence always outranks a presentation-only busy flag.
        // In particular, Local's busy flag can describe another mutation after
        // Add was verified. Never regress that receipt to "Adding" or manufacture
        // a submitted command before beginSubmission actually issued a ticket.
        val stage = when {
            phase == UniversalAddCoordinator.Phase.VERIFIED ->
                UniversalAddDisplayStage.VERIFIED
            phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                UniversalAddDisplayStage.SESSION_CONFIRMED
            phase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                UniversalAddDisplayStage.START_UNVERIFIED
            knownNoWrite && phase == UniversalAddCoordinator.Phase.REVIEWED ->
                UniversalAddDisplayStage.NO_NEW_ADD
            submitting && phase in setOf(
                UniversalAddCoordinator.Phase.WRITE_ATTEMPTED,
                UniversalAddCoordinator.Phase.READBACK_REQUIRED,
                UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN
            ) -> UniversalAddDisplayStage.SUBMITTING
            registrationFromThisSubmission &&
                phase in setOf(
                    UniversalAddCoordinator.Phase.WRITE_ATTEMPTED,
                    UniversalAddCoordinator.Phase.READBACK_REQUIRED,
                    UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN
                ) -> UniversalAddDisplayStage.REGISTERED_CHECKING
            phase in setOf(
                UniversalAddCoordinator.Phase.WRITE_ATTEMPTED,
                UniversalAddCoordinator.Phase.READBACK_REQUIRED,
                UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN
            ) -> UniversalAddDisplayStage.UNCONFIRMED
            else -> return null
        }
        val title = when (stage) {
            UniversalAddDisplayStage.SUBMITTING -> "Adding to $client"
            UniversalAddDisplayStage.REGISTERED_CHECKING -> "Added to $client"
            UniversalAddDisplayStage.VERIFIED -> "Added to $client"
            UniversalAddDisplayStage.SESSION_CONFIRMED -> "Found on $client"
            UniversalAddDisplayStage.START_UNVERIFIED -> "Start not confirmed on $client"
            UniversalAddDisplayStage.UNCONFIRMED -> "Couldn't confirm Add on $client"
            UniversalAddDisplayStage.NO_NEW_ADD -> "Nothing added to $client"
        }
        val explanation = when (stage) {
            UniversalAddDisplayStage.SUBMITTING ->
                "Sending the transfer and checking the result. Don't tap Add again."
            UniversalAddDisplayStage.REGISTERED_CHECKING ->
                "The transfer is on this client. Checking its folder and Start setting."
            UniversalAddDisplayStage.VERIFIED ->
                "The transfer is in the chosen folder, with your Start setting confirmed. Downloading depends on available peers."
            UniversalAddDisplayStage.SESSION_CONFIRMED ->
                "This transfer and its settings are present. Chunks couldn't tell whether this attempt added it, so it wasn't sent again."
            UniversalAddDisplayStage.START_UNVERIFIED ->
                "The transfer is in the chosen folder, but Chunks couldn't confirm whether it started. Add and Start weren't repeated."
            UniversalAddDisplayStage.UNCONFIRMED ->
                "Chunks couldn't tell whether the transfer was added. Check this client's transfers before trying again. Chunks won't retry automatically."
            UniversalAddDisplayStage.NO_NEW_ADD ->
                "Chunks didn't send a new transfer. Check the message below before trying again."
        }
        return UniversalAddDisplay(
            stage = stage,
            title = title,
            explanation = explanation,
            verifiedJobObserved = stage in setOf(
                UniversalAddDisplayStage.VERIFIED,
                UniversalAddDisplayStage.SESSION_CONFIRMED,
                UniversalAddDisplayStage.START_UNVERIFIED
            )
        )
    }
}
