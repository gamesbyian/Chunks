package io.github.gamesbyian.chunks.rpc

import java.io.IOException

/**
 * A public XML-RPC version/main view cannot verify a supplied username.
 *
 * This is intentionally the same bounded HTTP challenge evidence used by
 * the protected #891 read-only acceptance gate, now applied before the
 * Android connection is labelled authenticated or its login is remembered.
 * It is not proof of human ownership or permission to mutate. A deliberately
 * anonymous connection remains possible without supplying credentials.
 */
internal class RemoteIdentityUnverified : IOException(
    "The RPC gateway returned public reads without verifying the supplied authentication identity"
)

internal fun requireRemoteConnectIdentityEvidence(
    credentials: RemoteCredentials,
    remote: RTorrentRemote
) {
    if ((credentials.username.isNotEmpty() || credentials.password.isNotEmpty()) &&
        remote.readAuthenticationEvidence() != RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED) {
        throw RemoteIdentityUnverified()
    }
}


/**
 * A saved account is not automatically signed in again after process death,
 * logout, password rotation or proxy reconfiguration. Reauthenticate its
 * exact endpoint on a fresh transport before showing the inventory under the
 * stored account identity in the combined transfer view.
 *
 * The returned list is one complete read-only main view; failure leaves the
 * combined UI's previous observations stale. Never run mutations here.
 */
internal suspend fun verifiedSavedProfileMainInventory(
    credentials: RemoteCredentials
): List<RemoteTransfer> {
    val connection = RTorrentRemote(credentials)
    val version = connection.version()
    check(version.isNotBlank() && version.length <= 120) {
        "Invalid rTorrent version response"
    }
    val transfers = connection.listTransfers("main")
    requireRemoteConnectIdentityEvidence(credentials, connection)
    return transfers
}
