package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure source-exact admission: zero HTTP, zero mutation, zero file IO. */
class CanonicalRemoteFilesEligibilityTest {
    private val remote = RTorrentRemote(RemoteCredentials(
        "https://box.example/rutorrent1/RPC2", "alice", "dummy"))
    private val wrongAccount = RTorrentRemote(RemoteCredentials(
        "https://box.example/rutorrent2/RPC2", "alice", "dummy"))
    private val hash = "f".repeat(40)
    private val transfer = RemoteTransfer(hash, "Season", 100, 30, false, "/shows", 0, 0)

    @Test fun exactMainAndPrincipalAllowOnlyReadOnlyFileInventoryOffer() {
        val id = remote.addAccountScope()
        assertTrue(canonicalRemoteFilesEligible(remote, transfer, id, false, true))
        assertFalse(canonicalRemoteFilesEligible(remote, transfer,
            wrongAccount.addAccountScope(), false, true))
        assertFalse(canonicalRemoteFilesEligible(remote, transfer, id, true, true))
        assertFalse(canonicalRemoteFilesEligible(remote, transfer, id, false, false))
        assertFalse(canonicalRemoteFilesEligible(remote,
            transfer.copy(hash = "invalid"), id, false, true))
    }
}
