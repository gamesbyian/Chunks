package io.github.gamesbyian.chunks

/**
 * One logical on-device Forget transaction across the encrypted saved-profile
 * roster and the legacy single-login vault.
 *
 * Both stores use the same process-wide monitor. The requested roster row
 * must be durably removed before its matching legacy login may be cleared.
 * No new verified Connect can save between those operations, and forgetting
 * one exact principal cannot erase another principal's login.
 */
internal object RemoteProfileForget {
    fun forget(
        roster: RememberedRemoteProfileRoster,
        vault: RememberedRemoteLogin,
        profile: RememberedRemoteProfileRoster.Profile
    ): Boolean = synchronized(RemoteCredentialLock.monitor) {
        val legacy = vault.load()
        if (!roster.forget(profile.id)) return@synchronized false
        if (RemoteVerifiedUiIdentity.samePrincipal(legacy, profile.credentials)) vault.clear()
        else true
    }
}
