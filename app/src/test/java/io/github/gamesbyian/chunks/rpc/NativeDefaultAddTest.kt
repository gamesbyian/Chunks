package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.FakeRTorrent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Behavioral/data-integrity: native unknown Place is verified after Add, never guessed or replayed. */
class NativeDefaultAddTest {
    private val hash = "b7".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash"

    @Test fun unreadableDefaultDoesNotPreventOneNativeLoadAndExactPlaceReadback() = runBlocking {
        FakeRTorrent(directoryDefault = null).use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet)
            assertEquals("Unknown is not a fabricated absolute directory", "", plan.destination)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, "/home/user"), remote.loadStopped(plan))
            assertEquals(1, fixture.loads().size)
            assertEquals(2, fixture.calls.single { it.first.startsWith("load.") }.second.size)
            assertFalse("Unknown preview needs no getter to authorize native load",
                fixture.methods.contains("directory.default"))
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, "/home/user"), remote.reconcileStoppedAdd(plan))
            assertEquals(1, fixture.loads().size)
        }
    }

    @Test fun anUnresolvableRelativeDirectoryCannotBecomeVerifiedOrTriggerAnotherAdd() = runBlocking {
        FakeRTorrent(directoryDefault = "", unsetDefaultLandsIn = ".").use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(magnet)
            val result = remote.loadStopped(plan)
            assertFalse(result is RemoteAddOutcome.VerifiedStopped)
            assertFalse(remote.reconcileStoppedAdd(plan) is RemoteAddOutcome.VerifiedStopped)
            assertEquals(1, fixture.loads().size)
            assertTrue(fixture.methods.contains("system.cwd"))
        }
    }
}
