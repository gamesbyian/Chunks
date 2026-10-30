package io.github.gamesbyian.chunks

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in interrupted real-swarm download (#568/#5), run in two processes by
 * tools/android_public_swarm_interrupt.sh. Phase A downloads part of a public
 * torrent from ordinary peers with the app's own engine and state directory,
 * then SIGKILLs itself mid-download: no Stop, no explicit resume save.
 * Phase B, in a fresh process, requires the job restored STOPPED with no
 * restore problems and continued hash-verified growth past the pre-kill
 * amount; it records whether the recheck recovered pre-kill pieces from disk
 * or had to fetch them again. Emulator behind the runner's NAT only.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPublicSwarmInterruptInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val marker get() = File(context.filesDir, "swarm-interrupt.marker")
    private val target get() = File(context.filesDir, "swarm-interrupt")

    private fun log(text: String) = Log.i("ChunksSwarm", "CHUNKS_SWARM_INTERRUPT: $text")

    @Test fun phaseA_downloadPartThenDieMidTransfer() {
        assumeTrue("Only in the manual interrupt workflow", args.getString("chunksInterruptPhase") == "a")
        val name = args.getString("chunksSwarmTorrent").orEmpty()
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(name))
        val budget = args.getString("chunksSwarmBudgetBytes")?.toLongOrNull() ?: (8L shl 20)
        val seconds = args.getString("chunksSwarmSeconds")?.toLongOrNull() ?: 600L
        require(budget in 1L..(256L shl 20) && seconds in 30L..1800L)
        marker.delete()
        target.deleteRecursively(); target.mkdirs()
        val engine = LocalEngineHolder.get(context)
        val added = (engine.addStopped(File(context.cacheDir, name).readBytes(), target)
            as LocalAddResult.AddedStopped).status
        // A completed (or undersized) fixture cannot prove mid-download death
        // or meaningful continuation. Refuse before arming the kill.
        if (added.wantedBytes <= budget) {
            log("INCONCLUSIVE fixture_too_small wanted=${added.wantedBytes} budget=$budget")
            fail("INCONCLUSIVE: fixture has no data beyond interruption budget")
        }
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
        val started = System.nanoTime()
        var latest = requireNotNull(engine.status(added.infoHash))
        while (latest.doneBytes < budget && System.nanoTime() - started < seconds * 1_000_000_000L) {
            Thread.sleep(2_000)
            latest = requireNotNull(engine.status(added.infoHash))
        }
        if (latest.doneBytes < budget) {
            log("INCONCLUSIVE phase A verified=${latest.doneBytes} target=$budget peers=${latest.peers}")
            fail("INCONCLUSIVE peer availability before the kill")
        }
        if (InterruptedSwarmEvidence.continuationGoal(latest.doneBytes, added.wantedBytes) == null) {
            log("INCONCLUSIVE not_mid_download verified=${latest.doneBytes} wanted=${added.wantedBytes}")
            fail("INCONCLUSIVE: no genuinely incomplete transfer to interrupt")
        }
        marker.writeText(added.infoHash + "\n" + latest.doneBytes + "\n" + added.wantedBytes)
        log("armed hash=${added.infoHash} verified_before_kill=${latest.doneBytes} wanted=${added.wantedBytes}")
        // Mid-download sudden death while peers are still sending.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    @Test fun phaseB_restoredStoppedRechecksAndContinues() {
        assumeTrue("Only in the manual interrupt workflow", args.getString("chunksInterruptPhase") == "b")
        assertTrue("phase A did not arm", marker.isFile)
        val (hash, beforeText, wantedText) = marker.readLines()
        val before = beforeText.toLong()
        val wanted = wantedText.toLong()
        val seconds = args.getString("chunksSwarmSeconds")?.toLongOrNull() ?: 600L
        val engine = LocalEngineHolder.restoreIfSaved(context)
        try {
            assertNotNull("saved transfer was not restored", engine)
            assertTrue("restore problems: ${engine!!.restoreProblems}", engine.restoreProblems.isEmpty())
            val restored = requireNotNull(engine.status(hash)) { "transfer missing after restart" }
            assertTrue("restored transfer must not auto-start", restored.stopped)
            assertTrue("metadata must survive", restored.metadataKnown)
            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(hash).outcome)
            // First non-checking observation: what the recheck recovered from disk.
            val checkDeadline = System.nanoTime() + 180_000_000_000L
            var status = requireNotNull(engine.status(hash))
            Thread.sleep(2_000)
            status = requireNotNull(engine.status(hash))
            while (status.state == LocalTransferState.CHECKING && System.nanoTime() < checkDeadline) {
                Thread.sleep(500)
                status = requireNotNull(engine.status(hash))
            }
            if (status.state == LocalTransferState.CHECKING) {
                log("INCONCLUSIVE recheck_did_not_settle verified=${status.doneBytes}")
                fail("INCONCLUSIVE: disk recheck never settled after restart")
            }
            val recovered = status.doneBytes
            // Observation, not a pass condition: whether pieces written before the kill
            // were recognised on disk (RECOVERED) or must be fetched again (REDOWNLOAD).
            log((if (recovered > 0) "RECOVERED" else "REDOWNLOAD") +
                " recheck_recovered=$recovered verified_before_kill=$before state=${status.state}")
            // Rechecking existing disk pieces is not new peer transfer.
            // Require additional verified bytes beyond BOTH the pre-kill count
            // and the settled recovery count; otherwise fail inconclusive.
            val goal = requireNotNull(InterruptedSwarmEvidence.continuationGoal(maxOf(before, recovered), wanted)) {
                "INCONCLUSIVE: interruption marker did not record a partial download"
            }
            val started = System.nanoTime()
            while (status.doneBytes < goal && System.nanoTime() - started < seconds * 1_000_000_000L) {
                Thread.sleep(2_000)
                status = requireNotNull(engine.status(hash))
            }
            if (status.doneBytes < goal) {
                log("INCONCLUSIVE continuation verified=${status.doneBytes} goal=$goal peers=${status.peers}")
                fail("INCONCLUSIVE peer availability after restart")
            }
            assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(hash).outcome)
            log("VERIFIED restored_stopped=true recovered=$recovered before=$before continued_to=${status.doneBytes}")
            engine.removeKeepingData(hash)
        } finally {
            marker.delete()
            target.deleteRecursively()
        }
    }
}
