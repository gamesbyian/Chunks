package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RemoteDirectoryLookup
import io.github.gamesbyian.chunks.rpc.RemoteDirectoryPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRemotePlaceProbeTest {
    private fun checked(
        chosen: String,
        rootPath: String = "/tenant/data",
        observedPath: String = chosen,
        rootSupported: Boolean = true,
        targetSupported: Boolean = true
    ): Pair<Boolean, List<String>> = runBlocking {
        val reads = mutableListOf<String>()
        val valid = SnapshotRemotePlaceProbe.confirm(chosen) { requested ->
            reads += requested
            when {
                requested.isEmpty() && rootSupported ->
                    RemoteDirectoryLookup.Ready(RemoteDirectoryPage(rootPath, listOf("stopped")))
                requested == chosen && targetSupported ->
                    RemoteDirectoryLookup.Ready(RemoteDirectoryPage(observedPath, emptyList()))
                else -> RemoteDirectoryLookup.Unavailable(RemoteDirectoryLookup.Reason.UNSUPPORTED)
            }
        }
        valid to reads
    }

    @Test fun only_exact_existing_folder_under_current_tenant_root_authorizesReview() {
        val (verified, reads) = checked("/tenant/data/stopped")
        assertTrue(verified)
        assertEquals(listOf("", "/tenant/data/stopped"), reads)
        assertFalse(checked("/different-tenant/stopped").first)
        assertFalse(checked("/tenant/data-other/stopped").first)
        assertFalse(checked("/tenant/data/stopped", observedPath = "/tenant/data").first)
        assertFalse(checked("/tenant/data/stopped", targetSupported = false).first)
        assertFalse(checked("/tenant/data/stopped", rootSupported = false).first)
    }

    @Test fun unsafeAndUnreviewableInputNeverReachesRemoteRequest() {
        for (raw in listOf("/", "relative", "/tenant//stopped", "/tenant/../stopped",
            "/tenant/./stopped", "/tenant/%2Fstopped", "/tenant/\\stopped",
            "/tenant/data/" + "x".repeat(1100))) {
            val (valid, reads) = checked(raw)
            assertFalse(raw, valid)
            assertTrue(raw, reads.isEmpty())
        }
    }
}
