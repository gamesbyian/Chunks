package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalInstalledAddProcessDeathTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val journey get() = InstalledUniversalAddJourney(rule)
    // No peers, trackers or metadata are supplied to this unique fixture.
    private val hash = "6c6f63616c2d6164642d70726f636573732d6c6f"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Chunks%20unresolved%20Add%20fixture"

    private fun requireDisposableRunner() {
        assumeTrue("Opt-in installed process-death workflow only",
            InstrumentationRegistry.getArguments().getString("chunks_local_add_death") == "authorized")
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun killAfterRealLocalRegistrationWhileStartIsUnverified() {
        requireDisposableRunner()
        val appPlace = LocalDownloadPlaces.choices(context).single { it.place.kind == LocalPlaceKind.APP_STORAGE }
        assertTrue(appPlace.verified)
        assertTrue(LocalDownloadPlaces.remember(context, appPlace))
        ActivityScenario.launch(MainActivity::class.java).use {
            journey.activate(ChunksSemantics.FOOTER_ADD)
            LazyComposeActions.replaceText(rule, hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD), magnet)
            journey.chooseStartPreference(ToggleableState.On)
            journey.submit()
            journey.activate(ChunksSemantics.FOOTER_TRANSFERS)
            journey.awaitTransferReceipt("Start unverified")
            val job = requireNotNull(LocalEngineHolder.current()).list().single { it.infoHash.equals(hash, true) }
            assertFalse(job.metadataKnown)
            assertEquals(false, LocalUniversalAddAdapter.observedPayloadStarted(job))
            Log.i("ChunksLocalAddDeath", "exact-job-registered-start-unverified")
            Process.killProcess(Process.myPid())
        }
    }

    @Test fun freshProcessCanReviewOriginalMagnetWithoutReplayingIt() {
        requireDisposableRunner()
        val incoming = Intent(Intent.ACTION_VIEW, Uri.parse(magnet), context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(incoming).use {
            val field = hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD)
            rule.waitUntil(30_000) { rule.onAllNodes(field).fetchSemanticsNodes().size == 1 }
            val submit = hasTestTag(ChunksSemantics.ADD_SUBMIT)
            rule.waitUntil(30_000) {
                runCatching {
                    rule.onNode(submit).performScrollTo().assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
            // A fresh review is available, with no previous ticket or result.
            assertFalse(journey.hasReceiptState("Start unverified"))
            assertFalse(journey.hasReceiptState("Verified"))
            // Starting the real engine for an independent inventory read
            // restores durable jobs, but it must not replay the unresolved source.
            val restored = LocalEngineHolder.get(context)
            assertEquals(0, restored.list().count { it.infoHash.equals(hash, true) })
            journey.activate(ChunksSemantics.FOOTER_TRANSFERS)
            rule.onNode(hasTestTag(ChunksSemantics.TRANSFERS_ADD_RECEIPT)).assertDoesNotExist()
            assertEquals(0, restored.list().count { it.infoHash.equals(hash, true) })
            Log.i("ChunksLocalAddDeath", "fresh-review-without-replayed-add")
        }
    }
}
