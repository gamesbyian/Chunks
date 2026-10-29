package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in network loss and return with real peers (#569), run only by
 * tools/android_public_swarm_netflap.sh. A download runs under the
 * foreground service; once verified bytes flow, the script turns Wi-Fi and
 * mobile data off, signals the test, waits, turns them back on and signals
 * again. The test requires the job to stay started (never silently stopped
 * or removed) and verified growth to resume after the network returns,
 * without restarting the engine; it records what happened during the outage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPublicSwarmNetworkFlapInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val offFlag get() = File(context.filesDir, "net-off")
    private val onFlag get() = File(context.filesDir, "net-on")
    private val target get() = File(context.filesDir, "swarm-netflap")

    private fun log(text: String) = Log.i("ChunksSwarm", "CHUNKS_SWARM_NETFLAP: $text")
    private fun ongoing() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }

    private fun waitForFile(file: File, seconds: Long) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!file.exists()) {
            if (System.nanoTime() > deadline) fail("Signal ${file.name} never arrived")
            Thread.sleep(500)
        }
    }

    @Test fun downloadSurvivesNetworkLossAndResumes() {
        assumeTrue("Only in the manual network workflow", args.getString("chunksNetFlap") == "1")
        val name = args.getString("chunksSwarmTorrent").orEmpty()
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(name))
        val seconds = args.getString("chunksSwarmSeconds")?.toLongOrNull() ?: 600L
        require(seconds in 30L..1800L)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        offFlag.delete(); onFlag.delete()
        target.deleteRecursively(); target.mkdirs()
        val engine = LocalEngineHolder.get(context)
        val hash = (engine.addStopped(File(context.cacheDir, name).readBytes(), target)
            as LocalAddResult.AddedStopped).status.infoHash
        try {
            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(hash).outcome)
            LocalEngineService.start(context)
            val warm = 4L shl 20
            val started = System.nanoTime()
            var status = requireNotNull(engine.status(hash))
            while (status.doneBytes < warm && System.nanoTime() - started < seconds * 1_000_000_000L) {
                Thread.sleep(2_000)
                status = requireNotNull(engine.status(hash))
            }
            if (status.doneBytes < warm) {
                log("INCONCLUSIVE no flow before outage verified=${status.doneBytes}")
                fail("INCONCLUSIVE peer availability before the outage")
            }
            log("ready verified=${status.doneBytes}")
            waitForFile(offFlag, 180)
            val atLoss = requireNotNull(engine.status(hash)).doneBytes
            waitForFile(onFlag, 180)
            val offline = requireNotNull(engine.status(hash))
            log("outage verified_at_loss=$atLoss verified_at_return=${offline.doneBytes} " +
                "stopped=${offline.stopped} error=${offline.error != null}")
            assertFalse("the job must not be silently stopped by a network outage", offline.stopped)
            // Requirement: verified growth resumes after the network returns, same engine.
            val resumeGoal = offline.doneBytes + (2L shl 20)
            val returned = System.nanoTime()
            status = offline
            while (status.doneBytes < resumeGoal && System.nanoTime() - returned < seconds * 1_000_000_000L) {
                Thread.sleep(2_000)
                status = requireNotNull(engine.status(hash))
            }
            val recoverySeconds = (System.nanoTime() - returned) / 1_000_000_000L
            if (status.doneBytes < resumeGoal) {
                log("INCONCLUSIVE no recovery verified=${status.doneBytes} goal=$resumeGoal peers=${status.peers}")
                fail("INCONCLUSIVE: no verified growth after the network returned")
            }
            assertTrue("foreground service must survive the outage", ongoing())
            log("VERIFIED resumed_after_s=$recoverySeconds verified=${status.doneBytes} peers=${status.peers}")
        } finally {
            runCatching { engine.pause(hash) }
            runCatching { engine.removeKeepingData(hash) }
            context.stopService(Intent(context, LocalEngineService::class.java))
            offFlag.delete(); onFlag.delete()
            target.deleteRecursively()
        }
    }
}
