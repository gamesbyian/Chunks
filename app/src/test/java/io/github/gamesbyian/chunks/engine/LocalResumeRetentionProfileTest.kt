package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/** Production JNI lifecycle/growth receipt, with synthetic bytes and loopback peers only. */
class LocalResumeRetentionProfileTest {
    @get:Rule val temp = TemporaryFolder()

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition()) {
            assertTrue("Timed out: $description", System.nanoTime() < deadline)
            Thread.sleep(25)
        }
    }

    private fun added(result: LocalAddResult): String {
        assertTrue(result.toString(), result is LocalAddResult.AddedStopped)
        return (result as LocalAddResult.AddedStopped).status.infoHash
    }

    @Test fun repeatedNativeSaveRestoreRemoveAndReAddReportsActualHistoryGrowth() {
        DesktopLibtorrent.ensureLoaded()
        val source = temp.newFolder("source")
        val payload = ByteArray(4 * 1024 * 1024).also { Random(865).nextBytes(it) }
        File(source, "fixture.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(File(source, "fixture.bin"))
            .pieceSize(16 * 1024).generate().entry().bencode()
        val destination = temp.newFolder("destination")
        val state = temp.newFolder("state")
        fun engine(saved: File? = null) = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true,
            stateDirectory = saved
        )).also { it.start() }
        val cycles = System.getenv("CHUNKS_RESUME_PROFILE_CYCLES")?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..100 })
        } ?: 3
        val saves = System.getenv("CHUNKS_RESUME_PROFILE_SAVES")?.let {
            requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 1..1000 })
        } ?: 8
        engine().use { seed ->
            val hash = added(seed.addStopped(torrent, source))
            assertEquals(LocalCommandOutcome.VERIFIED, seed.resume(hash).outcome)
            await("source seeding") { seed.status(hash)?.state == LocalTransferState.SEEDING }
            repeat(cycles) { cycle ->
                engine(state).use { active ->
                    assertTrue("Committed Remove must not resurrect", active.list().isEmpty())
                    assertTrue(active.restoreProblems.toString(), active.restoreProblems.isEmpty())
                    assertEquals(hash, added(active.addStopped(torrent, destination,
                        listOf(InetSocketAddress("127.0.0.1", seed.listenPort())))))
                    assertEquals(LocalCommandOutcome.VERIFIED, active.resume(hash).outcome)
                    await("download/recheck reaches seeding") {
                        active.status(hash)?.state == LocalTransferState.SEEDING
                    }
                    assertTrue(active.flushCompletedForLibraryRead(hash, 30_000))
                    assertArrayEquals(payload, File(destination, "fixture.bin").readBytes())
                    repeat(saves) { save ->
                        // A real per-transfer preference change makes each native
                        // snapshot distinct without fabricating bencoded resume data.
                        val limit = (16_384 + cycle * saves + save).toLong()
                        await("upload limit readback") { active.setUploadLimit(hash, limit) }
                        assertEquals(1, active.saveResumeData())
                    }
                    assertEquals(LocalCommandOutcome.VERIFIED, active.pause(hash).outcome)
                    assertEquals(1, active.saveResumeData())
                }
                val started = System.nanoTime()
                val restored = engine(state)
                val restoreMs = (System.nanoTime() - started) / 1_000_000L
                restored.use {
                    assertTrue(it.restoreProblems.toString(), it.restoreProblems.isEmpty())
                    assertTrue(requireNotNull(it.status(hash)).stopped)
                    assertEquals(LocalCommandOutcome.VERIFIED, it.resume(hash).outcome)
                    await("restored transfer seeds") { it.status(hash)?.state == LocalTransferState.SEEDING }
                    assertArrayEquals(payload, File(destination, "fixture.bin").readBytes())
                    assertTrue(it.removeKeepingData(hash))
                }
                val inventory = LocalResumeDirectoryInventory.prepare(state)
                val reopened = LocalResumeEventStore.open(state, inventory)
                assertTrue(reopened.restorable().isEmpty())
                assertTrue(reopened.canRegister(hash))
                assertTrue(reopened.problems().isEmpty())
                val events = inventory.filter { it.name.contains(".resume-event.") }
                println("CHUNKS_RESUME_RETENTION cycle=${cycle + 1} saves=$saves " +
                    "events=${events.size} bytes=${events.sumOf { it.length() }} " +
                    "restore_ms=$restoreMs payload_bytes=${payload.size}")
            }
        }
    }
}
