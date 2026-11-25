package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.rpc.ClientOperations
import kotlinx.coroutines.CancellationException

/** Result of one explicit read-only check of a retained typed-client receipt. */
internal enum class ProtocolReceiptCheck { RELEASED, READ_FAILED, OWNER_CHANGED, NOT_CHECKABLE }

/**
 * The ONE read-only reconciliation of a retained receipt (#2026), shared by
 * Details and the Transfers card for receipts whose job is no longer listed.
 * It performs exactly one exact read of the receipt's own hash, never a write,
 * and releases only that receipt, only while the same owner, credentials,
 * privacy policy and lifecycle are still current.
 */
internal suspend fun checkRetainedProtocolReceipt(
    owner: ProtocolOperationReceiptOwner,
    receipt: ProtocolOperationReceiptOwner.Receipt,
    operations: ClientOperations,
    isOwnerCurrent: () -> Boolean
): ProtocolReceiptCheck {
    if (!receipt.canReconcile || !isOwnerCurrent()) return ProtocolReceiptCheck.NOT_CHECKABLE
    val policyEpoch = TransferPrivacyPolicyStore.revision
    val lifecycleEpoch = TransferPrivacyRuntime.lifecycleRevision
    val credentialEpoch = RemoteCredentialLock.capture()
    val hash = receipt.ticket.key.hash
    val row = try {
        operations.client.torrent(hash)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        return ProtocolReceiptCheck.READ_FAILED
    }
    if (!isOwnerCurrent() || !RemoteCredentialLock.current(credentialEpoch) ||
        policyEpoch != TransferPrivacyPolicyStore.revision ||
        lifecycleEpoch != TransferPrivacyRuntime.lifecycleRevision) return ProtocolReceiptCheck.OWNER_CHANGED
    // A reply for another job proves nothing about this receipt.
    if (row != null && !row.hash.equals(hash, ignoreCase = true)) return ProtocolReceiptCheck.READ_FAILED
    return if (owner.reconcile(receipt.ticket, row != null)) ProtocolReceiptCheck.RELEASED
        else ProtocolReceiptCheck.NOT_CHECKABLE
}
