package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant control `remote-add-enabled-without-fresh-list`: the remote Add control is
 * unavailable, with a visible reason, until a fresh confirmed server transfer list exists.
 * No server access (nothing is submitted).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteAddSubmitEnablementTest {
    @get:Rule val rule = createComposeRule()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val magnet = "magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=Example"

    private fun show(mutationReady: Boolean) {
        rule.setContent { ChunksTheme { Surface { Column {
            VerifiedAddScreen(remote, magnet, "/srv", onMagnet = {}, onDestination = {}, onBack = {},
                onComplete = {}, onVerifiedAdd = {}, onMutationStarted = { true }, onMutationSettled = {},
                mutationReady = mutationReady)
        } } } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(ChunksSemantics.ADD_SUBMIT))
    }

    @Test fun addIsUnavailableWithAReasonUntilAFreshServerListIsConfirmed() {
        show(mutationReady = false)
        rule.onNode(hasTestTag(ChunksSemantics.ADD_SUBMIT)).assertIsNotEnabled().assert(
            SemanticsMatcher("states why") { node ->
                (if (SemanticsProperties.StateDescription in node.config) node.config[SemanticsProperties.StateDescription] else "")
                    .contains("Waiting for a fresh confirmed remote transfer list")
            })
    }

    @Test fun addIsAvailableOnceTheListIsFresh() {
        show(mutationReady = true)
        rule.onNode(hasTestTag(ChunksSemantics.ADD_SUBMIT)).assertIsEnabled()
    }
}
