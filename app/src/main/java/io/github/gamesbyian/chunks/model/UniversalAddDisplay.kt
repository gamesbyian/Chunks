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
        val stage = when {
            submitting -> UniversalAddDisplayStage.SUBMITTING
            phase == UniversalAddCoordinator.Phase.VERIFIED ->
                UniversalAddDisplayStage.VERIFIED
            phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                UniversalAddDisplayStage.SESSION_CONFIRMED
            phase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                UniversalAddDisplayStage.START_UNVERIFIED
            knownNoWrite -> UniversalAddDisplayStage.NO_NEW_ADD
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
            UniversalAddDisplayStage.SESSION_CONFIRMED -> "Confirmed on $client"
            UniversalAddDisplayStage.START_UNVERIFIED -> "Transfer registered on $client"
            UniversalAddDisplayStage.UNCONFIRMED -> "Couldn't confirm Add on $client"
            UniversalAddDisplayStage.NO_NEW_ADD -> "No new Add on $client"
        }
        val explanation = when (stage) {
            UniversalAddDisplayStage.SUBMITTING ->
                "Submitting the reviewed transfer. Do not submit it again."
            UniversalAddDisplayStage.REGISTERED_CHECKING ->
                "This submission registered a transfer. Checking its exact location and requested Start state."
            UniversalAddDisplayStage.VERIFIED ->
                "This submission's exact transfer, destination and requested Start state are verified. Peer activity and payload bytes are separate."
            UniversalAddDisplayStage.SESSION_CONFIRMED ->
                "The exact transfer and requested state exist, but which client registered it is unknown. No Add was resent."
            UniversalAddDisplayStage.START_UNVERIFIED ->
                "The exact transfer and location exist, but requested Start remains unverified. No Add or Start was resent."
            UniversalAddDisplayStage.UNCONFIRMED ->
                "The Add may have happened. Check this exact client's transfer state; never retry an uncertain write automatically."
            UniversalAddDisplayStage.NO_NEW_ADD ->
                "No new transfer was submitted by this attempt. Review the reason before choosing another Add."
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
