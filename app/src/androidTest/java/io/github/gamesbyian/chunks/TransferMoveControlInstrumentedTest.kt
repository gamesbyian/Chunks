package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Move Transfer UI with fake ports: job-only, separately confirmed, never auto-retried. */
@RunWith(AndroidJUnit4::class)
class TransferMoveControlInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "b63eaa9720ee797667ef58ea35ef7d81ab5d1c16"
    private val scope = "https://seed.example/RPC2\u0000user"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        context.getSharedPreferences("chunks_transfer_moves_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("chunks_display_aliases_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun show(
        target: () -> Observation, source: () -> Observation,
        removals: AtomicInteger, starts: AtomicInteger, removeFails: Boolean = false,
        phoneSourceToken: String? = "native-session:17"
    ) = rule.setContent {
        MaterialTheme {
            Column {
                TransferMoveControl(
                    direction = TransferMove.Direction.PHONE_TO_SERVER,
                    infoHash = hash, name = "Big Buck Bunny", serverAliasScope = scope,
                    sourceReviewToken = phoneSourceToken, busy = false,
                    ports = TransferMovePorts(
                        observeTarget = { target() }, observeSource = { source() },
                        removeSource = { removals.incrementAndGet(); if (removeFails) error("lost reply") },
                        targetUnavailableReason = "Connect"),
                    onStartMove = { starts.incrementAndGet() })
            }
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun moveVerifiesTargetThenRemovesOnlyTheSourceJobAfterSecondConfirmation() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        var targetSeen: Observation = Observation.Absent
        var sourceSeen: Observation = Observation.Present
        show({ targetSeen }, { sourceSeen }, removals, starts)
        rule.onNodeWithText("Move Transfer to server…").performClick()
        rule.onNodeWithText("Moves the torrent job, not its files", substring = true).assertExists()
        rule.onNodeWithText("Review server Add").performClick()
        assertEquals(1, starts.get())
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Not on the server yet")
        assertEquals(0, removals.get())
        targetSeen = Observation.Present
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Remove original job from the phone…")
        rule.onNodeWithText("Remove original job from the phone…").performClick()
        rule.onNodeWithText("Downloaded files stay on the phone", substring = true).assertExists()
        assertEquals(0, removals.get())
        sourceSeen = Observation.Absent
        rule.onNodeWithText("Remove original job").performClick()
        waitFor("Moved: the job now lives on the server")
        assertEquals(1, removals.get())
        assertNull(TransferMoveStore(context).get(TransferMove.Direction.PHONE_TO_SERVER, hash))
    }

    @Test fun verifiedPhoneToServerMoveCarriesOnlyChunksAliasWithoutRemovingSource() {
        val removals = AtomicInteger()
        val starts = AtomicInteger()
        val aliases = TransferAliasStore(context)
        val phone = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        assertEquals(true, aliases.save(phone, hash, "Personal favorite"))
        show({ Observation.Present }, { Observation.Present }, removals, starts)
        rule.onNodeWithText("Move Transfer to server…").performClick()
        rule.onNodeWithText("Review server Add").performClick()
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Remove original job from the phone…")
        assertEquals("Personal favorite", aliases.get(scope, hash))
        assertEquals("Personal favorite", aliases.get(phone, hash))
        assertEquals(0, removals.get())
    }

    @Test fun firstMoveVerificationPreservesPreviouslyCustomizedDestinationAlias() {
        val removals = AtomicInteger()
        val starts = AtomicInteger()
        val aliases = TransferAliasStore(context)
        val phone = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        assertEquals(true, aliases.save(phone, hash, "Source title"))
        assertEquals(true, aliases.save(scope, hash, "Target-specific title"))
        show({ Observation.Present }, { Observation.Present }, removals, starts)
        rule.onNodeWithText("Move Transfer to server…").performClick()
        rule.onNodeWithText("Review server Add").performClick()
        rule.onNodeWithText("Check the server").performClick()
        waitFor("Remove original job from the phone…")
        assertEquals("Target-specific title", aliases.get(scope, hash))
        assertEquals("Source title", aliases.get(phone, hash))
        assertEquals(0, removals.get())
    }

    @Test fun verifiedServerToPhoneMoveCarriesExactAccountAlias() {
        val removals = AtomicInteger()
        val starts = AtomicInteger()
        val aliases = TransferAliasStore(context)
        val phone = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        assertEquals(true, aliases.save(scope, hash, "Server original"))
        rule.setContent {
            MaterialTheme {
                Column {
                    TransferMoveControl(
                        direction = TransferMove.Direction.SERVER_TO_PHONE,
                        infoHash = hash, name = "Big Buck Bunny", serverAliasScope = scope,
                        sourceDirectory = "/seed/Big Buck Bunny", busy = false,
                        ports = TransferMovePorts(
                            observeTarget = { Observation.Present },
                            observeSource = { Observation.Present },
                            removeSource = { removals.incrementAndGet() },
                            targetUnavailableReason = "Connect"),
                        onStartMove = { starts.incrementAndGet() })
                }
            }
        }
        rule.onNodeWithText("Move Transfer to phone…").performClick()
        rule.onNodeWithText("Review phone Add").performClick()
        rule.onNodeWithText("Check the phone").performClick()
        waitFor("Remove original job from the server…")
        assertEquals("Server original", aliases.get(phone, hash))
        assertEquals("Server original", aliases.get(scope, hash))
        assertEquals(0, removals.get())
    }

    @Test fun replacementPhoneSourceCannotInheritPersistedMoveRemovalConsent() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        val original = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER, hash,
            "Big Buck Bunny", TransferMoveStore.scopeDigest(scope), "seed.example",
            sourceReviewToken = "native-session:old")
            .copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        TransferMoveStore(context).save(original)
        show({ Observation.Present }, { Observation.Present }, removals, starts,
            phoneSourceToken = "native-session:new")
        rule.onNodeWithText("The original transfer has changed", substring = true).assertExists()
        rule.onNodeWithText("Remove original job from the phone…").assertIsNotEnabled()
        assertEquals(0, removals.get())
        rule.onNodeWithText("Cancel Move (keep both jobs)").performClick()
        assertNull(TransferMoveStore(context).get(TransferMove.Direction.PHONE_TO_SERVER, hash))
    }

    @Test fun oldV1PhoneMoveNeedsFreshReviewBeforeAnySourceRemove() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        val legacy = TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER, hash,
            "Big Buck Bunny", TransferMoveStore.scopeDigest(scope), "seed.example")
            .copy(stage = TransferMove.Stage.TARGET_VERIFIED)
        TransferMoveStore(context).save(legacy)
        show({ Observation.Present }, { Observation.Present }, removals, starts)
        rule.onNodeWithText("cannot identify its original source", substring = true).assertExists()
        rule.onNodeWithText("Remove original job from the phone…").assertIsNotEnabled()
        assertEquals(0, removals.get())
    }

    @Test fun lostRemovalReplyBecomesUncertainAndIsNeverRepeatedAutomatically() {
        val removals = AtomicInteger(); val starts = AtomicInteger()
        var sourceSeen: Observation = Observation.Unknown
        val store = TransferMoveStore(context)
        store.save(TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER, hash, "Big Buck Bunny",
            TransferMoveStore.scopeDigest(scope), "seed.example",
            sourceReviewToken = "native-session:17")
            .copy(stage = TransferMove.Stage.TARGET_VERIFIED))
        show({ Observation.Present }, { sourceSeen }, removals, starts, removeFails = true)
        rule.onNodeWithText("Remove original job from the phone…").performClick()
        rule.onNodeWithText("Remove original job").performClick()
        waitFor("could not confirm whether the original job was removed")
        assertEquals(1, removals.get())
        rule.onNodeWithText("Check the original job").performClick()
        waitFor("Still unable to check")
        sourceSeen = Observation.Absent
        rule.onNodeWithText("Check the original job").performClick()
        waitFor("Moved:")
        assertEquals("observation, not a second request, resolved it", 1, removals.get())
    }
}
