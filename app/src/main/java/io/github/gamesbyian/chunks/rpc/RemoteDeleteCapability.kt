package io.github.gamesbyian.chunks.rpc

/** Adapter evidence is separate from write authorization and from file deletion proof. */
enum class RemoteCapabilityState { SUPPORTED, UNSUPPORTED, RESTRICTED, UNKNOWN }
enum class RemoteDeleteAdapter { ERASEDATA_HTTP, REGISTERED_ERASE_HOOKS }

data class RemoteDeleteCapability(
    val state: RemoteCapabilityState,
    val adapter: RemoteDeleteAdapter?,
    val reason: String,
    val observedAtMillis: Long,
    val accountScope: String,
    val authorization: RemoteCapabilityState = RemoteCapabilityState.UNKNOWN
) {
    fun isFresh(scope: String, now: Long = System.currentTimeMillis()): Boolean =
        scope == accountScope && now - observedAtMillis in 0..30_000L
    val actionable: Boolean get() = state == RemoteCapabilityState.SUPPORTED && adapter != null
}
