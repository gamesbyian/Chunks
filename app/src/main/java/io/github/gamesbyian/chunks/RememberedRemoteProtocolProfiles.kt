package io.github.gamesbyian.chunks

import android.content.Context
import android.util.Base64
import io.github.gamesbyian.chunks.model.CapabilityEvidence
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.RemoteBackendIdentity
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-bound encrypted credentials for typed non-rTorrent remote clients.
 * Never reads/migrates/replaces the legacy rTorrent vault. A remembered
 * profile alone is NOT a verified live session or mutation authorization.
 */
internal class RememberedRemoteProtocolProfiles(
    context: Context,
    private val keys: SealingKeys = AndroidKeystoreSealingKeys(KEY_ALIAS)
) {
    internal data class Profile(
        val id: String,
        val backend: RemoteBackendKind,
        val label: String,
        val credentials: RemoteCredentials,
        val delugeDaemonHostId: String?,
        val lastVerifiedAtMillis: Long
    ) {
        override fun toString(): String = "Typed remote profile (<redacted>)"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var unreadableRoster = false

    companion object {
        internal const val MAX_PROFILES = 12
        private const val MAX_ENCODED = 196_608
        private const val MAX_RAW = 100_000
        private const val FRESH_PROOF_MS = 120_000L
        /** Persisted alias and preference file; changing either strands saved profiles. */
        internal const val KEY_ALIAS = "io.github.gamesbyian.chunks.other-remote-profile-roster-v1"
        internal const val PREFS = "chunks_other_remote_profiles_encrypted_v1"
        private val SHA_ID = Regex("[0-9a-f]{64}")

        internal fun validLabel(value: String): Boolean =
            value.length in 1..100 && value.none { it.isISOControl() }
    }

    /** A damaged ciphertext or lost key must not turn into an empty writable roster. */
    fun list(): List<Profile> = synchronized(RemoteCredentialLock.monitor) {
        unreadableRoster = false
        if (!RememberedRemoteLogin(appContext).rememberingEnabled()) return emptyList()
        if (!prefs.contains("roster")) return emptyList()
        try {
            val value = prefs.getString("roster", null)
                ?: throw IllegalArgumentException("Invalid saved-client record")
            require(value.length in 20..MAX_ENCODED)
            val packed = Base64.decode(value, Base64.NO_WRAP)
            require(packed.size in 29..131_072)
            val key = keys.decryptionKey()
                ?: throw IllegalArgumentException("Saved-client key unavailable")
            val raw = SealedBlob.open(key, packed)
            require(raw.size in 2..MAX_RAW)
            val document = JSONArray(String(raw, Charsets.UTF_8))
            require(document.length() == 2 && document.optInt(0, -1) == 1)
            val entries = document.opt(1) as? JSONArray
                ?: throw IllegalArgumentException("Invalid profile roster version")
            require(entries.length() <= MAX_PROFILES)
            val seen = HashSet<String>()
            (0 until entries.length()).map { i ->
                val row = entries.opt(i) as? JSONArray
                    ?: throw IllegalArgumentException("Invalid saved client row")
                require(row.length() == 8)
                val id = strictString(row, 0)
                val kind = RemoteBackendKind.valueOf(strictString(row, 1))
                require(kind != RemoteBackendKind.RTORRENT && SHA_ID.matches(id))
                val label = strictString(row, 2)
                require(validLabel(label))
                val endpoint = strictString(row, 3)
                val username = strictString(row, 4)
                val password = strictString(row, 5)
                val daemon = if (row.isNull(6)) null else strictString(row, 6)
                val time = (row.opt(7) as? Number)?.toString()?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid verification time")
                require(time > 0 && time <= System.currentTimeMillis() + FRESH_PROOF_MS)
                require(password.length in 1..4096)
                val exact = RemoteBackendIdentity.of(kind, endpoint, username, daemon).value
                require(id == exact && seen.add(id))
                Profile(id, kind, label, RemoteCredentials(endpoint, username, password), daemon, time)
            }
        } catch (_: Exception) {
            unreadableRoster = true
            emptyList()
        }
    }

    /**
     * Only call after the exact adapter has authenticated and returned a full
     * read. A stale/mismatched receipt or an opted-out/newer Connect cannot
     * persist a different server's credentials.
     */
    fun saveVerified(
        backend: RemoteBackendKind,
        credentials: RemoteCredentials,
        daemonHostId: String?,
        snapshot: EnvironmentTransferSnapshot,
        capabilities: EnvironmentCapabilitySnapshot,
        ticket: Long,
        preferredLabel: String? = null
    ): Boolean = synchronized(RemoteCredentialLock.monitor) {
        if (!RemoteCredentialLock.current(ticket) ||
            !RememberedRemoteLogin(appContext).rememberingEnabled() ||
            backend == RemoteBackendKind.RTORRENT ||
            credentials.password.length !in 1..4096 ||
            (preferredLabel != null && !validLabel(preferredLabel.trim()))) return false
        val id = try {
            RemoteBackendIdentity.of(
                backend, credentials.endpoint, credentials.username, daemonHostId
            ).value
        } catch (_: IllegalArgumentException) { return false }
        val at = snapshot.observedAtEpochMillis ?: return false
        val now = System.currentTimeMillis()
        if (at <= 0 || at > now || now - at > FRESH_PROOF_MS ||
            snapshot.availability != EnvironmentAvailability.LIVE ||
            !snapshot.inventoryComplete || snapshot.environment.value != id ||
            capabilities.environment.value != id ||
            capabilities.observedAtEpochMillis != at ||
            capabilities.of(EnvironmentOperation.LIST) != CapabilityEvidence.UNKNOWN ||
            EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }
                .any { capabilities.decide(it, EnvironmentAvailability.LIVE).allowed }) return false

        val current = list().toMutableList()
        if (unreadableRoster) return false
        val existing = current.indexOfFirst { it.id == id }
        val name = preferredLabel?.trim() ?: current.getOrNull(existing)?.label ?:
            (backend.name.lowercase().replaceFirstChar { it.uppercase() } + " @ " +
                (runCatching { URI(credentials.endpoint).host }.getOrNull() ?: "remote client")).take(100)
        if (!validLabel(name)) return false
        if (existing >= 0) current.removeAt(existing)
        else if (current.size >= MAX_PROFILES) return false
        current.add(0, Profile(id, backend, name, credentials, daemonHostId, at))
        persist(current)
    }

    fun rename(id: String, newLabel: String): Boolean = synchronized(RemoteCredentialLock.monitor) {
        val label = newLabel.trim()
        if (!SHA_ID.matches(id) || !validLabel(label) ||
            !RememberedRemoteLogin(appContext).rememberingEnabled()) return false
        val current = list()
        if (unreadableRoster || current.none { it.id == id }) return false
        persist(current.map { if (it.id == id) it.copy(label = label) else it })
    }

    fun forget(id: String): Boolean = synchronized(RemoteCredentialLock.monitor) {
        if (!SHA_ID.matches(id)) return false
        val current = list()
        if (unreadableRoster || current.none { it.id == id }) return false
        val saved = persist(current.filterNot { it.id == id })
        if (saved) RemoteCredentialLock.invalidate()
        saved
    }

    /** Clear ciphertext before deleting its distinct device-bound key. */
    fun clearAll(): Boolean = synchronized(RemoteCredentialLock.monitor) {
        if (!prefs.edit().remove("roster").commit()) return false
        unreadableRoster = false
        RemoteCredentialLock.invalidate()
        runCatching { keys.erase() }
        true
    }

    private fun persist(profiles: List<Profile>): Boolean {
        if (profiles.isEmpty()) return clearAll()
        if (profiles.size > MAX_PROFILES || unreadableRoster) return false
        return try {
            val rows = JSONArray()
            profiles.forEach { item ->
                rows.put(JSONArray().put(item.id).put(item.backend.name).put(item.label)
                    .put(item.credentials.endpoint).put(item.credentials.username)
                    .put(item.credentials.password).put(item.delugeDaemonHostId ?: JSONObject.NULL)
                    .put(item.lastVerifiedAtMillis))
            }
            val raw = JSONArray().put(1).put(rows).toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= MAX_RAW)
            val packed = SealedBlob.seal(keys.encryptionKey(), raw)
            require(packed.size <= 131_072)
            val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
            require(encoded.length <= MAX_ENCODED)
            prefs.edit().putString("roster", encoded).commit()
        } catch (_: Exception) { false }
    }

    private fun strictString(row: JSONArray, index: Int): String =
        row.opt(index) as? String ?: throw IllegalArgumentException("Invalid saved client")
}
