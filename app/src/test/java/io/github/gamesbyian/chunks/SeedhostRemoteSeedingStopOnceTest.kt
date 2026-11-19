package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.seeding.RTorrentSeedingPort
import io.github.gamesbyian.chunks.seeding.SeedingController
import io.github.gamesbyian.chunks.seeding.SeedingObservation
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Owner-authorized (2026-11-14) live acceptance of remote Smart Seeding on the
 * PRIMARY Seedhost rTorrent: the production [RTorrentSeedingPort] and shared
 * [SeedingController] stop ONE existing started, complete transfer whose real
 * server ratio already meets 1.0, exactly once, then the original Start is
 * restored with readback.
 *
 * Safety:
 * - only the exact primary endpoint; skipped unless the protected manual
 *   workflow supplies its credentials and an explicit apply flag;
 * - the controller sees exactly ONE chosen hash (a filtering port), so no
 *   other transfer is evaluated or touched;
 * - one Stop and one restoring Start, each with exact-hash readback; an
 *   uncertain outcome is reported, never retried;
 * - prints only redacted outcomes: no hash, name, path, counter or tracker.
 */
class SeedhostRemoteSeedingStopOnceTest {
    private val primary = "https://ianseeds.wood.seedhost.eu/rutorrent/"
    private fun report(text: String) = println("CHUNKS_SEEDHOST_SEEDING: $text")

    /** The real port, narrowed to one exact hash. */
    private class OneTransfer(private val real: RTorrentSeedingPort, private val hash: String) :
        SeedingController.EnginePort by real {
        override fun transfers() = real.transfers().filter { it.infoHash == hash }
        override fun observe(infoHash: String): SeedingObservation? {
            check(infoHash == hash) { "Only the chosen transfer may be observed" }
            return real.observe(infoHash)
        }
        override fun pause(infoHash: String): Boolean {
            check(infoHash == hash) { "Only the chosen transfer may be stopped" }
            return real.pause(infoHash)
        }
    }

    @Test fun oneExistingTransferMeetingItsRatioIsStoppedOnceAndRestored() = runBlocking {
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        assumeTrue("Protected manual Seedhost gate only",
            user.isNotBlank() && pass.isNotBlank() && System.getenv("CHUNKS_APPLY_SEEDING_STOP") == "1")
        // The protected workflow never sets the override: it is for a local loopback rehearsal only.
        val endpoint = System.getenv("CHUNKS_SEEDING_STOP_REHEARSAL_URL")?.takeIf { it.startsWith("http://127.0.0.1:") } ?: primary
        val remote = RTorrentRemote(RemoteCredentials(endpoint, user, pass))
        val environment = requireNotNull(io.github.gamesbyian.chunks.seeding.RemoteSeeding.environmentOf(remote))

        // Read-only selection: started, complete, real ratio >= 1.0 from the server's own counters.
        val started = remote.listTransfers().filter { it.started && it.sizeBytes > 0 && it.completedBytes == it.sizeBytes }
            .map { it.hash.lowercase() }.sorted()
        var chosen: String? = null
        for (hash in started) {
            val e = runCatching { remote.readSeedingEvidence(hash) }.getOrNull() ?: continue
            val up = e.uploadedBytes ?: continue
            val down = e.downloadedBytes ?: continue
            if (e.complete == true && e.active == true && down > 0 && up >= down) { chosen = hash; break }
        }
        report("started_complete=${started.size} eligible=${chosen != null}")
        val hash = requireNotNull(chosen) { "No started, complete transfer with a measurable ratio >= 1.0; nothing was changed" }

        val store = InteropAssignmentStore().apply { setEpoch(0L) }
        val port = RTorrentSeedingPort(remote, activationBaseline = false)
        var stopSent = false
        try {
            SeedingController(OneTransfer(port, hash), store)
                .tick(environment, emptyList(), SeedingPolicy(ratioGoal = BigDecimal.ONE), System.currentTimeMillis())
            stopSent = port.stopAttempted
            val assignment = requireNotNull(store.get(hash))
            assertTrue("one Stop was sent", stopSent)
            assertTrue("the Stop was verified by exact-hash readback", assignment.stoppedByRule != null)
            assertEquals(false, remote.listTransfers().single { it.hash.equals(hash, true) }.started)
            report("stop=verified reason_ratio=${assignment.stoppedByRule!!.startsWith("Ratio")}")
            // A second tick must not stop it again (journal).
            val again = RTorrentSeedingPort(remote, activationBaseline = false)
            SeedingController(OneTransfer(again, hash), store)
                .tick(environment, emptyList(), SeedingPolicy(ratioGoal = BigDecimal.ONE), System.currentTimeMillis())
            assertTrue("never a second Stop", !again.stopAttempted)
            report("second_tick=no_write")
        } finally {
            if (stopSent) {
                // Restore the transfer's original started state: one Start, exact readback, no retry.
                val restored = runCatching { remote.setStarted(hash, desiredStarted = true) }.getOrNull()
                report("restore=" + when (restored) {
                    LifecycleOutcome.Verified(true) -> "verified"
                    null -> "failed_check_server"
                    else -> "uncertain_check_server"
                })
                assertEquals("original Start restored", LifecycleOutcome.Verified(true), restored)
            }
        }
    }
}
