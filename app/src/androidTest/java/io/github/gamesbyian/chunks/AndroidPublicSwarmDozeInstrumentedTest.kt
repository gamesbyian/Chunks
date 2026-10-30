package io.github.gamesbyian.chunks

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in Doze observation with real peers (#569), run only by
 * tools/android_public_swarm_doze.sh. A download runs under the foreground
 * service; once verified bytes are flowing the script forces deep Doze
 * (dumpsys deviceidle force-idle) and drops a flag file. The test then
 * records whether hash-verified bytes keep arriving while idle. Passing
 * requires periodic evidence that Android stayed in idle throughout the
 * observation window, with the service and engine still answering. Growth
 * versus stall is a separate recorded observation. Emulator only: OEM power
 * managers and real idle entry on a handset can differ.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPublicSwarmDozeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val flag get() = File(context.filesDir, "doze-on")
    private val target get() = File(context.filesDir, "swarm-doze")

    private fun log(text: String) = Log.i("ChunksSwarm", "CHUNKS_SWARM_DOZE: $text")
    private fun ongoing() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID }

    @Test fun foregroundDownloadUnderForcedDeepDoze() {
        assumeTrue("Only in the manual Doze workflow", args.getString("chunksDoze") == "1")
        val name = args.getString("chunksSwarmTorrent").orEmpty()
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(name))
        val seconds = args.getString("chunksSwarmSeconds")?.toLongOrNull() ?: 600L
        require(seconds in 30L..1800L)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        flag.delete()
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
                log("INCONCLUSIVE no flow before Doze verified=${status.doneBytes} peers=${status.peers}")
                fail("INCONCLUSIVE peer availability before Doze")
            }
            assertTrue("foreground service must be running before Doze", ongoing())
            log("ready verified=${status.doneBytes}")
            // The script forces deep idle, confirms it, then creates the flag.
            val flagDeadline = System.nanoTime() + 180_000_000_000L
            while (!flag.exists()) {
                if (System.nanoTime() > flagDeadline) fail("Doze flag never arrived")
                Thread.sleep(500)
            }
            val power = context.getSystemService(PowerManager::class.java)
            val witness = DeepIdleWindowWitness()
            witness.record(power.isDeviceIdleMode)
            val before = requireNotNull(engine.status(hash)).doneBytes
            // Sample 19 times over 90 seconds (initial + every five seconds).
            // A `force-idle` ACK before the window is not proof of sustained idle.
            val window = 90_000L
            repeat(18) {
                Thread.sleep(5_000)
                witness.record(power.isDeviceIdleMode)
            }
            val after = requireNotNull(engine.status(hash)) { "engine stopped answering in Doze" }
            val grew = after.doneBytes - before
            if (!witness.sustained(expectedSamples = 19)) {
                log("INCONCLUSIVE idle_not_sustained samples=${witness.samples} " +
                    "non_idle_samples=${witness.nonIdleSamples} window_ms=$window")
                fail("INCONCLUSIVE: Android did not remain in deep idle throughout the sampling window")
            }
            log((if (grew > 0) "GROWTH" else "STALLED") + " idle_mode=true " +
                "idle_samples=${witness.samples} window_ms=$window " +
                "verified_before=$before verified_after=${after.doneBytes} grew=$grew peers=${after.peers}")
            assertTrue("foreground service must survive Doze", ongoing())
            assertNotNull(after)
        } finally {
            runCatching { engine.pause(hash) }
            runCatching { engine.removeKeepingData(hash) }
            context.stopService(Intent(context, LocalEngineService::class.java))
            flag.delete()
            target.deleteRecursively()
        }
    }
}
