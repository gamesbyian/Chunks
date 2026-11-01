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
