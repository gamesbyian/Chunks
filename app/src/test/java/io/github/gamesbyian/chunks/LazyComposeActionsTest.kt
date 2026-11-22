package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared lazy-container actions, on the JVM. The installed Connect journey
 * failed at once on its first field (#1895 run 44475146428, earlier 44459147277).
 * These pin the real Compose failure shapes the helper may re-locate, and prove
 * that a one-shot action is still dispatched at most once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LazyComposeActionsTest {
    @get:Rule val rule = createComposeRule()

    /**
     * [text], plus an outgoing copy (test tag "outgoing") for the first lookups only: the match set
     * changes between fetches exactly as when an outgoing and an incoming screen briefly coexist.
     */
    private fun transient(text: String, ambiguousLookups: Int = 6): SemanticsMatcher {
        var lookups = 0
        val outgoing = hasTestTag("outgoing")
        return SemanticsMatcher("transient '$text'") { node ->
            hasText(text).matches(node) && (!outgoing.matches(node) || lookups++ < ambiguousLookups)
        }
    }

    private fun undispatchedMessage(action: () -> Unit): String {
        try { action(); fail("expected a re-fetch failure") } catch (e: AssertionError) { return e.message.orEmpty() }
        error("unreachable")
    }

    @Test fun composeReportsAMissingOrAmbiguousNodeBeforeActingWithTheMatchedPrefix() {
        var clicks = 0
        rule.setContent {
            Column {
                Button(onClick = { clicks++ }) { Text("Twice") }
                Button(onClick = { clicks++ }) { Text("Twice") }
            }
        }
        // The exact prefix LazyComposeActions treats as "not acted on yet".
        val missing = undispatchedMessage { rule.onNodeWithText("Absent field").performTextReplacement("x") }
        assertTrue(missing, missing.contains("Expected exactly '1' node but"))
        val ambiguous = undispatchedMessage { rule.onNodeWithText("Twice").performClick() }
        assertTrue(ambiguous, ambiguous.contains("Expected exactly '1' node but"))
        assertEquals("a failed re-fetch never dispatches", 0, clicks)
    }

    @Test fun aTransientSecondFieldIsWaitedOutAndTheSurvivorReceivesTheText() {
        var entered by mutableStateOf("")
        var outgoing by mutableStateOf("")
        rule.setContent {
            Column {
                OutlinedTextField(entered, { entered = it }, label = { Text("Server address") })
                OutlinedTextField(outgoing, { outgoing = it }, label = { Text("Server address") },
                    modifier = Modifier.testTag("outgoing"))
            }
        }
        rule.waitForIdle()
        // The outgoing screen's copy stops matching after a few lookups, as when a transition ends.
        LazyComposeActions.replaceText(rule, transient("Server address"), "seedbox.invalid", timeoutMillis = 10_000)
        rule.waitForIdle()
        assertEquals("seedbox.invalid", entered)
        assertEquals("the transient copy is never written", "", outgoing)
    }

    @Test fun draftReplacementDoesNotFocusAFieldThatReflowsOnFocus() {
        var entered by mutableStateOf("")
        var fieldFocused by mutableStateOf(false)
        val target = hasTestTag("password-draft")
        rule.setContent {
            if (!fieldFocused) {
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it },
                    label = { Text("Password") },
                    modifier = Modifier.testTag("password-draft").onFocusChanged {
                        if (it.isFocused) fieldFocused = true
                    }
                )
            }
        }
        LazyComposeActions.replaceText(rule, target, "unsent-fixture")
        rule.onNode(target).assertExists()
        assertEquals("unsent-fixture", entered)
        assertEquals(false, fieldFocused)
    }

    @Test fun aTransientSecondButtonIsNeverClickedAndTheSurvivorIsClickedOnce() {
        var real by mutableIntStateOf(0)
        var stale by mutableIntStateOf(0)
        rule.setContent {
            Column {
                Button(onClick = { real++ }) { Text("Connect") }
                Button(onClick = { stale++ }, modifier = Modifier.testTag("outgoing")) { Text("Connect") }
            }
        }
        rule.waitForIdle()
        LazyComposeActions.activateOnce(rule, transient("Connect"), timeoutMillis = 10_000)
        rule.waitForIdle()
        assertEquals(1, real)
        assertEquals(0, stale)
    }

    @Test fun aPersistentAmbiguityStillFailsLoudlyWithoutActing() {
        var clicks = 0
        rule.setContent {
            Column {
                Button(onClick = { clicks++ }) { Text("Remove") }
                Button(onClick = { clicks++ }) { Text("Remove") }
            }
        }
        val failure = runCatching {
            LazyComposeActions.activateOnce(rule, hasText("Remove"), timeoutMillis = 500)
        }.exceptionOrNull()
        assertTrue("$failure", failure is AssertionError && failure.message!!.startsWith("Ambiguous click target: 2"))
        assertEquals(0, clicks)
    }

    @Test fun aDetachedNodeDuringReadOnlyScrollMayBeRefoundButActionFailuresMayNot() {
        val observed = AssertionError(
            "Action performScrollTo() failed.\n" +
                "The node is no longer in the tree, last known semantics:\n" +
                "Node #10516, Tag: 'chunks.add.submit'")
        assertTrue("Observed CI scroll-only detachment is safe to re-find",
            LazyComposeActions.retryableScrollFailure(observed))
        val lazy = AssertionError(
            "Action performScrollToNode() failed.\nThe node is no longer in the tree")
        assertTrue(LazyComposeActions.retryableScrollFailure(lazy))
        assertTrue("Existing no-parent scroll behavior stays retryable",
            LazyComposeActions.retryableScrollFailure(
                AssertionError("no parent layout with a Scroll")))
        assertTrue("An action handler failure must never be classified as an undispatched scroll",
            !LazyComposeActions.retryableScrollFailure(
                AssertionError("Click handler failed after Add transfer")))
        assertTrue("An unrelated scroll assertion remains visible",
            !LazyComposeActions.retryableScrollFailure(
                AssertionError("Action performScrollTo() failed. Unexpected semantics assertion")))
    }

    @Test fun aFailureAfterTheActionIsNeverRetried() {
        var clicks = 0
        rule.setContent {
            Button(onClick = { clicks++; throw IllegalStateException("handler failed") }) { Text("Add transfer") }
        }
        val failure = runCatching {
            LazyComposeActions.activateOnce(rule, hasText("Add transfer"), timeoutMillis = 2_000)
        }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException)
        assertEquals("a dispatched one-shot action is never repeated", 1, clicks)
    }
}
