package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A2611-02 step 2: the server-job handoff through the real composition and
 * through Activity recreation. Loopback-free; the owner is the only authority.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class AddHandoffRecreationTest {
    @get:Rule val rule = createComposeRule()
    private val magnet = "magnet:?xt=urn:btih:" + "c".repeat(40) + "&dn=handoff"
    private val other = "magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=typed"

    private fun show(restoration: StateRestorationTester, owner: LocalAddAttemptOwner) {
        restoration.setContent { ChunksTheme { ChunksApp(owner, RemoteSessionOwner()) } }
    }

    @Test fun anEmptyDraftReceivesTheHandoffOnceAndRecreationNeverReplaysIt() {
        val owner = LocalAddAttemptOwner()
        val restoration = StateRestorationTester(rule)
        owner.handoff.stage(magnet)
        show(restoration, owner)
        rule.waitUntil(15_000) { owner.sourceDraft.value == magnet }
        rule.waitUntil(15_000) { owner.handoff.staged == null }

        // The person clears the source; a recreation must not bring the old handoff back.
        rule.runOnIdle { owner.sourceDraft.value = "" }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("a consumed handoff is never replayed", "", owner.sourceDraft.value)
        assertNull(owner.handoff.staged)
    }

    @Test fun anOccupiedDraftIsNeverReplacedAndTheHandoffIsRefusedNotKept() {
        val owner = LocalAddAttemptOwner()
        val restoration = StateRestorationTester(rule)
        owner.sourceDraft.value = other
        owner.handoff.stage(magnet)
        show(restoration, owner)
        rule.waitUntil(15_000) { owner.handoff.staged == null }
        assertEquals("the typed source is untouched", other, owner.sourceDraft.value)

        // It does not wait unseen to overwrite the draft after the attempt later clears.
        rule.runOnIdle { owner.sourceDraft.value = "" }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("", owner.sourceDraft.value)
        assertNull(owner.handoff.staged)
    }

    @Test fun aHandoffStagedForAnotherSessionIsDroppedWhenNoSessionIsLive() {
        val owner = LocalAddAttemptOwner()
        val restoration = StateRestorationTester(rule)
        owner.handoff.stage(magnet, RemoteSessionStamp(Any(), "https://x.example/RPC2\u0000u"))
        show(restoration, owner)
        rule.waitUntil(15_000) { owner.handoff.staged == null }
        assertEquals("a server job's link does not outlive its session", "", owner.sourceDraft.value)
    }
}
