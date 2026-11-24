package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateMapOf
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome

/** Fixed, credential-free descriptions; no arbitrary labels, paths or client responses are retained. */
internal enum class ProtocolOperationKind(val label: String) {
    START("Start"), STOP("Stop"), RECHECK("Recheck"), FILE_PRIORITIES("File priorities"),
    SET_LOCATION("Change folder"), MOVE_FILES("Move files"), REMOVE("Remove"),
    REMOVE_DATA("Remove and delete files"), RETIRE_SOURCE("Finish move")
}

/**
 * Process-owned receipt/lease for explicit, single-client typed remote commands.
 * A key identifies the canonical principal and hash; the ticket also freezes its
 * authenticated generation. A same-principal reconnect does not erase the old
 * unresolved receipt, but only its own fresh read may reconcile it. No
 * credentials, callbacks, adapters, transfer names, paths or raw errors retained.
 * RAM only; not a durable process-death receipt or a new write executor.
 */
internal class ProtocolOperationReceiptOwner(private val capacity: Int = 256) {
    data class Key(val profileId: String, val hash: String)
    class Ticket internal constructor(val key: Key, val generation: String,
        val kind: ProtocolOperationKind, internal val sequence: Long)

    enum class Phase { PREFLIGHT, POSSIBLE_WRITE, UNKNOWN, ACK_PENDING, REGISTRATION_ABSENT,
        RECONCILED_PRESENT, RECONCILED_ABSENT }

    data class Receipt(val ticket: Ticket, val phase: Phase) {
        val blocksWrites get() = phase != Phase.RECONCILED_PRESENT && phase != Phase.RECONCILED_ABSENT
        val canReconcile get() = phase == Phase.UNKNOWN || phase == Phase.ACK_PENDING ||
            phase == Phase.REGISTRATION_ABSENT

        fun description(): String {
            val action = ticket.kind.label
            return when (phase) {
                Phase.PREFLIGHT -> "$action: checking the client before a request."
                Phase.POSSIBLE_WRITE -> "$action: request in progress."
                Phase.UNKNOWN -> "$action: the previous request may have reached this client. Its result is unknown."
                Phase.ACK_PENDING -> "$action: acknowledged, but the requested effect was not confirmed."
                Phase.REGISTRATION_ABSENT -> "Transfer removed. Downloaded-file deletion was requested but could not be independently verified. Check the files on the server."
                Phase.RECONCILED_PRESENT, Phase.RECONCILED_ABSENT -> {
                    val registration = if (phase == Phase.RECONCILED_PRESENT) "still registered" else "not registered"
                    val payload = if (ticket.kind == ProtocolOperationKind.REMOVE_DATA)
                        " Downloaded-file deletion is still not independently verified." else ""
                    "$action: read-only check found the transfer $registration. Earlier effects may remain unverified. Nothing was retried.$payload"
                }
            }
        }
    }

    private val entries = mutableStateMapOf<Key, Receipt>()
    private var sequence = 0L

    @Synchronized fun receipt(key: Key): Receipt? = entries[key]

    @Synchronized fun begin(key: Key, generation: String, kind: ProtocolOperationKind): Ticket? {
        require(generation.isNotBlank() && key.profileId.isNotBlank() && key.hash.matches(Regex("[0-9a-f]{40}")))
        if (entries[key]?.blocksWrites == true) return null
        if (key !in entries && entries.size >= capacity) {
            val evict = entries.values.filterNot { it.blocksWrites }.minByOrNull { it.ticket.sequence }
                ?: return null
            entries.remove(evict.ticket.key)
        }
        return Ticket(key, generation, kind, ++sequence).also { entries[key] = Receipt(it, Phase.PREFLIGHT) }
    }

    @Synchronized fun possibleWrite(ticket: Ticket): Boolean {
        val current = entries[ticket.key] ?: return false
        if (current.ticket !== ticket || current.phase != Phase.PREFLIGHT) return false
        entries[ticket.key] = Receipt(ticket, Phase.POSSIBLE_WRITE)
        return true
    }

    @Synchronized fun complete(ticket: Ticket, outcome: ClientOperationOutcome) {
        val current = entries[ticket.key] ?: return
        if (current.ticket !== ticket || current.canReconcile || !current.blocksWrites) return
        val phase = if (!outcome.hash.equals(ticket.key.hash, ignoreCase = true)) Phase.UNKNOWN
        else when (outcome) {
            is ClientOperationOutcome.Verified, is ClientOperationOutcome.Refused -> null
            is ClientOperationOutcome.Uncertain -> Phase.UNKNOWN
            is ClientOperationOutcome.Pending ->
                if (outcome.registrationAbsent) Phase.REGISTRATION_ABSENT else Phase.ACK_PENDING
        }
        if (phase == null) entries.remove(ticket.key)
        else entries[ticket.key] = Receipt(ticket, phase)
    }

    @Synchronized fun cancel(ticket: Ticket) {
        val current = entries[ticket.key] ?: return
        if (current.ticket !== ticket) return
        when (current.phase) {
            Phase.PREFLIGHT -> entries.remove(ticket.key)
            Phase.POSSIBLE_WRITE -> entries[ticket.key] = Receipt(ticket, Phase.UNKNOWN)
            else -> Unit
        }
    }

    /** The caller must have just made an exact read through its still-current authenticated generation. */
    @Synchronized fun reconcile(ticket: Ticket, present: Boolean): Boolean {
        val current = entries[ticket.key] ?: return false
        if (current.ticket !== ticket || !current.canReconcile) return false
        entries[ticket.key] = Receipt(ticket, if (present) Phase.RECONCILED_PRESENT else Phase.RECONCILED_ABSENT)
        return true
    }
}

internal object ProtocolOperationReceipts {
    val owner = ProtocolOperationReceiptOwner()
}
