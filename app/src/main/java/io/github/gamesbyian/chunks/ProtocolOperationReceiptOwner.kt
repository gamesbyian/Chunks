package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateMapOf
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome

/** Fixed, credential-free descriptions; no arbitrary labels, paths or client responses are retained. */
internal enum class ProtocolOperationKind(val label: String) {
    START("Start"), STOP("Stop"), RECHECK("Recheck"), FILE_PRIORITIES("File priorities"),
    SET_LOCATION("Change folder"), MOVE_FILES("Move files"), REMOVE("Remove"),
    REMOVE_DATA("Remove and delete files"), COPY_TARGET("Copy job"), RETIRE_SOURCE("Finish move"),
    FINISH_IN_ORDER("Finish in Order"), SMART_SEEDING("Smart Seeding")
}

/**
 * Process-owned receipts for typed remote commands and leases for per-transfer policies.
 * A key identifies the canonical principal and hash; the ticket also freezes its
 * authenticated generation. A same-principal reconnect does not erase the old
 * unresolved receipt, but only its own fresh read may reconcile it. No
 * credentials, callbacks, adapters, transfer names, paths or raw errors retained.
 * The live map is RAM. An attached [Journal] additionally records only the unresolved
 * phases (profile id, hash, fixed command kind, phase) so a process death cannot erase
 * a possible write: the journal is saved BEFORE a write is allowed to start, and a
 * restored in-flight request becomes UNKNOWN. Not a new write executor.
 */
internal class ProtocolOperationReceiptOwner(private val capacity: Int = 256) {
    data class Key(val profileId: String, val hash: String)
    class Ticket internal constructor(val key: Key, val generation: String?,
        val kind: ProtocolOperationKind, internal val sequence: Long)

    enum class Phase { POLICY_RUNNING, PREFLIGHT, POSSIBLE_WRITE, UNKNOWN, ACK_PENDING, REGISTRATION_ABSENT,
        RECONCILED_PRESENT, RECONCILED_ABSENT }

    data class Receipt(val ticket: Ticket, val phase: Phase) {
        val blocksWrites get() = phase != Phase.RECONCILED_PRESENT && phase != Phase.RECONCILED_ABSENT
        val canReconcile get() = phase == Phase.UNKNOWN || phase == Phase.ACK_PENDING ||
            phase == Phase.REGISTRATION_ABSENT

        fun description(): String {
            val action = ticket.kind.label
            return when (phase) {
                Phase.POLICY_RUNNING -> "$action is checking this transfer."
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

    /** Credential-free durable record of one unresolved receipt. No generation, name, path or reply. */
    data class Persisted(val profileId: String, val hash: String, val kind: String, val phase: String)

    interface Journal {
        fun load(): List<Persisted>
        /** Must be durable on return; false means the record may be lost. */
        fun save(entries: List<Persisted>): Boolean
    }

    private val entries = mutableStateMapOf<Key, Receipt>()
    private var sequence = 0L
    private var journal: Journal? = null
    private val v1Hash = Regex("[0-9a-f]{40}")

    @Synchronized fun receipt(key: Key): Receipt? = entries[key]

    /** Restores unresolved receipts once per process; a request in flight at death becomes UNKNOWN. */
    @Synchronized fun attach(journal: Journal) {
        if (this.journal != null) return
        this.journal = journal
        val restored = runCatching { journal.load() }.getOrDefault(emptyList())
        for (item in restored) {
            val kind = ProtocolOperationKind.entries.firstOrNull { it.name == item.kind } ?: continue
            val phase = Phase.entries.firstOrNull { it.name == item.phase } ?: continue
            if (item.profileId.isBlank() || !v1Hash.matches(item.hash)) continue
            val restoredPhase = when (phase) {
                Phase.POSSIBLE_WRITE -> Phase.UNKNOWN
                Phase.UNKNOWN, Phase.ACK_PENDING, Phase.REGISTRATION_ABSENT -> phase
                else -> continue
            }
            val key = Key(item.profileId, item.hash)
            if (key in entries || entries.size >= capacity) continue
            entries[key] = Receipt(Ticket(key, null, kind, ++sequence), restoredPhase)
        }
        // Rewrite so a restored POSSIBLE_WRITE is stored as the UNKNOWN it now is.
        saveJournal()
    }

    private fun saveJournal(): Boolean {
        val target = journal ?: return true
        val unresolved = entries.values.filter {
            it.phase == Phase.POSSIBLE_WRITE || it.phase == Phase.UNKNOWN ||
                it.phase == Phase.ACK_PENDING || it.phase == Phase.REGISTRATION_ABSENT
        }.map { Persisted(it.ticket.key.profileId, it.ticket.key.hash, it.ticket.kind.name, it.phase.name) }
        return runCatching { target.save(unresolved) }.getOrDefault(false)
    }

    @Synchronized fun begin(key: Key, generation: String, kind: ProtocolOperationKind): Ticket? {
        require(generation.isNotBlank() && key.profileId.isNotBlank() && v1Hash.matches(key.hash))
        return acquire(key, generation, kind, Phase.PREFLIGHT)
    }

    /** Policies keep uncertainty in their own durable journals. This lease only prevents overlapping commands. */
    @Synchronized fun reservePolicy(profileId: String, hash: String, kind: ProtocolOperationKind): AutoCloseable? {
        val key = Key(profileId, hash.lowercase(java.util.Locale.ROOT))
        if (profileId.isBlank() || !v1Hash.matches(key.hash)) return null
        val ticket = acquire(key, null, kind, Phase.POLICY_RUNNING) ?: return null
        return AutoCloseable { cancel(ticket) }
    }

    private fun acquire(key: Key, generation: String?, kind: ProtocolOperationKind, phase: Phase): Ticket? {
        if (entries[key]?.blocksWrites == true) return null
        if (key !in entries && entries.size >= capacity) {
            val evict = entries.values.filterNot { it.blocksWrites }.minByOrNull { it.ticket.sequence }
                ?: return null
            entries.remove(evict.ticket.key)
        }
        return Ticket(key, generation, kind, ++sequence).also { entries[key] = Receipt(it, phase) }
    }

    @Synchronized fun possibleWrite(ticket: Ticket): Boolean {
        val current = entries[ticket.key] ?: return false
        if (current.ticket !== ticket || current.phase != Phase.PREFLIGHT) return false
        entries[ticket.key] = Receipt(ticket, Phase.POSSIBLE_WRITE)
        // Journal first: without a durable record the write must not start.
        if (!saveJournal()) {
            entries[ticket.key] = current
            return false
        }
        return true
    }

    @Synchronized fun complete(ticket: Ticket, outcome: ClientOperationOutcome) {
        val current = entries[ticket.key] ?: return
        if (current.ticket !== ticket || current.phase == Phase.POLICY_RUNNING ||
            current.canReconcile || !current.blocksWrites) return
        val phase = if (!outcome.hash.equals(ticket.key.hash, ignoreCase = true)) Phase.UNKNOWN
        else when (outcome) {
            is ClientOperationOutcome.Verified, is ClientOperationOutcome.Refused -> null
            is ClientOperationOutcome.Uncertain -> Phase.UNKNOWN
            is ClientOperationOutcome.Pending ->
                if (outcome.registrationAbsent) Phase.REGISTRATION_ABSENT else Phase.ACK_PENDING
        }
        if (phase == null) entries.remove(ticket.key)
        else entries[ticket.key] = Receipt(ticket, phase)
        saveJournal()
    }

    @Synchronized fun cancel(ticket: Ticket) {
        val current = entries[ticket.key] ?: return
        if (current.ticket !== ticket) return
        when (current.phase) {
            Phase.POLICY_RUNNING, Phase.PREFLIGHT -> entries.remove(ticket.key)
            Phase.POSSIBLE_WRITE -> entries[ticket.key] = Receipt(ticket, Phase.UNKNOWN)
            else -> Unit
        }
        saveJournal()
    }

    /** A departing screen can revoke preflight, but only the operation can settle an in-flight write. */
    @Synchronized fun abandonPreflight(ticket: Ticket) {
        val current = entries[ticket.key] ?: return
        if (current.ticket === ticket && current.phase == Phase.PREFLIGHT) entries.remove(ticket.key)
    }

    /** The caller must have just made an exact read through its still-current authenticated generation. */
    @Synchronized fun reconcile(ticket: Ticket, present: Boolean): Boolean {
        val current = entries[ticket.key] ?: return false
        if (current.ticket !== ticket || !current.canReconcile) return false
        entries[ticket.key] = Receipt(ticket, if (present) Phase.RECONCILED_PRESENT else Phase.RECONCILED_ABSENT)
        saveJournal()
        return true
    }
}

internal object ProtocolOperationReceipts {
    val owner = ProtocolOperationReceiptOwner()

    /** Idempotent; call from every entry point that can start a typed command in a fresh process. */
    fun install(context: android.content.Context) =
        owner.attach(SharedPreferencesReceiptJournal(context.applicationContext))
}

/** Device-local journal of unresolved typed-command receipts. Holds no credentials, names, paths or replies. */
internal class SharedPreferencesReceiptJournal(context: android.content.Context) :
    ProtocolOperationReceiptOwner.Journal {
    private val preferences = context.getSharedPreferences("chunks_protocol_receipts_v1",
        android.content.Context.MODE_PRIVATE)

    override fun load(): List<ProtocolOperationReceiptOwner.Persisted> {
        val raw = preferences.getString("entries", null) ?: return emptyList()
        val array = org.json.JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            ProtocolOperationReceiptOwner.Persisted(
                row.optString("p"), row.optString("h"), row.optString("k"), row.optString("s"))
        }
    }

    override fun save(entries: List<ProtocolOperationReceiptOwner.Persisted>): Boolean {
        val array = org.json.JSONArray()
        entries.forEach {
            array.put(org.json.JSONObject().put("p", it.profileId).put("h", it.hash).put("k", it.kind).put("s", it.phase))
        }
        return preferences.edit().putString("entries", array.toString()).commit()
    }
}
