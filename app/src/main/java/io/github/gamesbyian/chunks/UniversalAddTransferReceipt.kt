package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddDisplay
import io.github.gamesbyian.chunks.model.UniversalAddDisplayProjector

/** In-memory projection of an authorized attempt into the exact client's Transfers context. */
internal class UniversalAddTransferReceipt(
    val key: UnifiedTransferKey,
    val clientLabel: String,
    val display: UniversalAddDisplay
) {
    override fun toString(): String = "UniversalAddTransferReceipt(private)"
}

internal fun UniversalAddCoordinator.transferReceipt(
    environment: EnvironmentId,
    clientLabel: String,
    submitting: Boolean,
    registrationFromThisSubmission: Boolean = false
): UniversalAddTransferReceipt? {
    val intent = retainedTicket?.intent?.takeIf { it.target.environment == environment } ?: return null
    val display = UniversalAddDisplayProjector.from(phase, clientLabel, submitting,
        registrationFromThisSubmission) ?: return null
    return UniversalAddTransferReceipt(UnifiedTransferKey.of(environment, intent.source.expectedInfoHash),
        clientLabel, display)
}
