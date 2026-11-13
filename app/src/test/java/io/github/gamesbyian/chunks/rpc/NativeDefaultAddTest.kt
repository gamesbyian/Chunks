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

    /** Data-integrity/behavioral: unresolved old roots stay readable without blocking another exact custom Add. */
    @Test fun anUnsupportedCwdOnAnotherJobDoesNotBlockAnExplicitAbsoluteDestination() = runBlocking {
        FakeRTorrent().use { fixture ->
            val other = "ac".repeat(20)
            fixture.seed(other, "Existing", ".")
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            assertEquals(".", remote.listTransfers().single().directory)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, "/chosen"),
                remote.loadStopped(StoppedRemoteAddPlan.fromMagnet(magnet, "/chosen")))
            assertEquals(1, fixture.loads().size)
            assertEquals(".", fixture.jobs.getValue(other).directory)
        }
    }
    @Test fun sessionOnlyRemoveCanMatchAnUnresolvedRawRootWithoutFileAuthority() = runBlocking {
        FakeRTorrent().use { fixture ->
            fixture.seed(hash, "Relative", ".")
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            assertEquals(SessionEraseOutcome.VerifiedRemoved(hash), remote.eraseFromSession(hash, "Relative", "."))
            assertFalse(fixture.jobs.containsKey(hash))
            assertEquals(1, fixture.methods.count { it == "d.erase" })
            assertEquals(SessionEraseRecheck.Absent, remote.recheckSessionErase(hash, "Relative", "."))
        }
    }

    /** Behavioral/latency: native-default accounts require one CWD read pair per inventory, not per row. */
    @Test fun anInventoryResolvesAllRelativeJobsWithOneWorkingDirectoryObservation() = runBlocking {
        for (cwd in listOf(null, "/server/work")) {
            FakeRTorrent(workingDirectory = cwd).use { fixture ->
                repeat(55) { fixture.seed("%040x".format(it), "Job$it", ".") }
                val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
                val rows = remote.listTransfers()
                assertEquals(55, rows.size)
                assertTrue(rows.all { it.directory == (cwd ?: ".") })
                assertEquals(if (cwd == null) 1 else 2, fixture.methods.count { it == "system.cwd" })
            }
        }
    }

    @Test fun modernMethodScopedUntrustedCwdDenialDoesNotHideTheInventory() = runBlocking {
        FakeRTorrent(untrustedWorkingDirectory = true).use { fixture ->
            fixture.seed(hash, "Existing", ".")
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            assertEquals(".", remote.listTransfers().single().directory)
            assertEquals(1, fixture.methods.count { it == "system.cwd" })
            assertEquals(SessionEraseOutcome.VerifiedRemoved(hash), remote.eraseFromSession(hash, "Existing", "."))
        }
    }

    @Test fun aJoinedDirectoryBeyondTheInventoryBoundRemainsExplicitlyUnresolved() = runBlocking {
        val relative = "r".repeat(200)
        FakeRTorrent(workingDirectory = "/" + "dir/".repeat(1000) + "end").use { fixture ->
            fixture.seed(hash, "Long root", relative)
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", ""))
            assertEquals(relative, remote.listTransfers().single().directory)
        }
    }

}
