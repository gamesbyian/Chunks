package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Read-only exact-hash diagnostics for the owner-observed Chunks Add stall.
 * d.message is UNTRUSTED: it may embed private tracker URLs, passkeys,
 * account names or payload paths. The caller receives only a fixed category,
 * never a raw server message. No auto-fix, Add, Start, retry or Erase occurs.
 */
internal data class RemoteStallEvidence(
    val hash: String,
    val started: Boolean,
    val active: Boolean,
    val open: Boolean,
    val hashingPhase: Int?,
    val hashCheckFailed: Boolean?,
    val hashChecking: Boolean?,
    val awaitingMetadata: Boolean?,
    /** 0=off, 1=scheduler controlled, 2=forced on, 3=forced off. */
    val controlMode: Int?,
    /** Blocks d.try_* commands; does not by itself explain a failed d.start. */
    val ignoresConditionalCommands: Boolean?,
    val messageClass: MessageClass
) {
    enum class MessageClass { NONE, STORAGE, TRACKER_OR_NETWORK, HASH_CHECK, OTHER, UNKNOWN }

    fun explanation(): String = when {
        controlMode == 3 ->
            "rTorrent marks this torrent as forced off (d.mode=3). Compare with a working torrent and inspect server scheduler policy before changing anything."
        messageClass == MessageClass.STORAGE ->
            "The server reports a storage-related problem. Inspect the exact destination, available space and account permissions in ruTorrent."
        hashCheckFailed == true || messageClass == MessageClass.HASH_CHECK ->
            "The server reports a hash-check problem. Inspect this transfer's recheck state in ruTorrent."
        hashingPhase != null && hashingPhase > 0 || hashChecking == true ->
            "rTorrent is checking pieces. This read alone does not establish when activation will complete."
        awaitingMetadata == true ->
            "This transfer is still a metadata placeholder. An inactive engine cannot retrieve magnet metadata."
        started && !active ->
            "Start is requested but this exact torrent engine remains inactive. Inspect the server's storage, queue and per-transfer error state."
        !open -> "This torrent is not open. Check server-side destination and storage conditions."
        active -> "This torrent engine is active. Confirm actual received and hash-verified byte growth separately."
        else -> "This torrent is stopped. No Start or download activity is implied."
    }
}

/** Strictly a newly initiated read-only inspection of ONE explicitly selected hash. */
internal object RemoteStallInspector {
    private val identity = Regex("[0-9a-fA-F]{40}")

    suspend fun inspect(remote: RTorrentRemote, hash: String): RemoteStallEvidence {
        require(identity.matches(hash)) { "Invalid v1 transfer identity" }
        suspend fun guard() {
            check((remote.call("d.hash", listOf(hash)) as? String)
                ?.equals(hash, ignoreCase = true) == true) {
                "Selected transfer identity changed during inspection"
            }
        }
        fun flag(value: Any?, field: String): Boolean {
            val n = value as? Long
            require(n != null && n in 0L..1L) { "Invalid remote $field flag" }
            return n == 1L
        }
        suspend fun optional(method: String): Any? = try {
            remote.call(method, listOf(hash))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (fault: RpcFault) {
            // Unsupported older-build getters are unknown. Auth rejection,
            // vanished target, permission and server-busy faults stop the
            // entire probe, rather than generating a misleading partial result.
            val reason = fault.message.orEmpty().lowercase(Locale.ROOT)
            if (fault.code in setOf("-32601", "-506") && "method" in reason &&
                listOf("not found", "not defined", "unknown", "unsupported").any { it in reason }) {
                null
            } else throw fault
        }
        suspend fun optionalFlag(method: String): Boolean? =
            optional(method)?.let { flag(it, method) }
        guard()
        val started = flag(remote.call("d.state", listOf(hash)), "state")
        val active = flag(remote.call("d.is_active", listOf(hash)), "active")
        val open = flag(remote.call("d.is_open", listOf(hash)), "open")
        val phase = optional("d.hashing")?.let {
            val n = it as? Long
            require(n != null && n in 0L..3L) { "Invalid remote hashing phase" }
            n.toInt()
        }
        val hashFailure = optionalFlag("d.hashing_failed")
        val checking = optionalFlag("d.is_hash_checking")
        val metadata = optionalFlag("d.is_meta")
        val controlMode = optional("d.mode")?.let {
            val n = it as? Long
            require(n != null && n in 0L..3L) { "Invalid remote control mode" }
            n.toInt()
        }
        val ignoresConditionalCommands = optionalFlag("d.ignore_commands")
        val message = optional("d.message")
        val category = when (message) {
            null -> RemoteStallEvidence.MessageClass.UNKNOWN
            is String -> classify(message)
            else -> error("Invalid remote message reply")
        }
        guard()
        return RemoteStallEvidence(hash, started, active, open, phase, hashFailure,
            checking, metadata, controlMode, ignoresConditionalCommands, category)
    }

    /** No raw message, snippet, URL, path or tracker error is returned/logged. */
    internal fun classify(raw: String): RemoteStallEvidence.MessageClass {
        if (raw.isBlank()) return RemoteStallEvidence.MessageClass.NONE
        val s = raw.take(4096).lowercase(Locale.ROOT)
        val trackerContext = listOf("tracker", "announce", "http://", "https://")
            .any { it in s }
        val storageContext = listOf("file", "directory", "disk", "storage", "filesystem")
            .any { it in s }
        return when {
            // A tracker can itself reply "permission denied". That must not
            // falsely indict the owner's download directory or file rights.
            trackerContext -> RemoteStallEvidence.MessageClass.TRACKER_OR_NETWORK
            listOf("no space left", "disk full", "read-only file system",
                "read only file system", "could not create", "failed to open file",
                "file open failed", "storage error").any { it in s } ||
                (storageContext && listOf("permission denied", "access denied").any { it in s }) ->
                RemoteStallEvidence.MessageClass.STORAGE
            listOf("hash check", "hashing failed", "hash check failed", "piece hash").any { it in s } ->
                RemoteStallEvidence.MessageClass.HASH_CHECK
            listOf("connection refused", "timed out", "timeout",
                "host not found", "could not resolve").any { it in s } ->
                RemoteStallEvidence.MessageClass.TRACKER_OR_NETWORK
            else -> RemoteStallEvidence.MessageClass.OTHER
        }
    }
}
