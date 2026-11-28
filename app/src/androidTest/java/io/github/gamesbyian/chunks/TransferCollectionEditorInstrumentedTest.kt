package io.github.gamesbyian.chunks

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferCollectionEditorInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun reusesAnExistingCollectionByExplicitOneTapWithoutNetworkSideEffects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val scope = "https://test.invalid/RPC2\u0000quick-collection-choice-test"
        val hash = "E".repeat(40)
        val knownHash = "F".repeat(40)
        val transfer = RemoteTransfer(hash, "Untouched.torrent.name", 100L, 70L, false, "/original/path")
        try {
            store.add(scope, knownHash, "Star Trek")
            store.remove(scope, hash, "Star Trek")
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(transfer, scope, store,
                        availableCollections = listOf("Star Trek", "STAR TREK")) {}
                }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithText("Choose existing collection", substring = true).performClick()
            rule.onNodeWithText("Add to Star Trek").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Star Trek"), TransferCollectionStore(context).forTransfer(scope, hash))
            assertEquals("Untouched.torrent.name", transfer.name)
            assertEquals("/original/path", transfer.directory)
            rule.onNodeWithText("Remove from Star Trek").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
        } finally {
            store.remove(scope, hash, "Star Trek")
            store.remove(scope, knownHash, "Star Trek")
        }
    }

    @Test fun localEngineUsesAnIndependentCollectionNamespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val infoHash = "D".repeat(40)
        val localScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remoteScope = "https://test.invalid/RPC2\u0000local-separation"
        try {
            store.remove(localScope, infoHash, "On the local device")
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(infoHash, "Local download", localScope, store) {}
                }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("On the local device")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("On the local device"), TransferCollectionStore(context).forTransfer(localScope, infoHash))
            assertEquals(emptyList<String>(), store.forTransfer(remoteScope, infoHash))
            rule.onNodeWithText("Remove from On the local device").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(localScope, infoHash))
        } finally {
            store.remove(localScope, infoHash, "On the local device")
        }
    }

    @Test fun nativeEditAndRemovalOnlyChangeLocalMembershipMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val hash = "C".repeat(40)
        val scope = "https://test.invalid/RPC2\u0000compose-collections-test"
        val transfer = RemoteTransfer(hash, "Original.Celebrity.Jeopardy.mkv",
            200L, 100L, false, "/unchanged/source")
        try {
            rule.setContent {
                MaterialTheme {
                    TransferCollectionEditor(transfer, scope, store) {}
                }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Celeb Jeopardy")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Celeb Jeopardy"), store.forTransfer(scope, hash))
            rule.onNodeWithText("Remove from Celeb Jeopardy").performClick()
            waitForText("No manual collections")
            assertEquals(emptyList<String>(), store.forTransfer(scope, hash))
            assertEquals("Original.Celebrity.Jeopardy.mkv", transfer.name)
            assertEquals("/unchanged/source", transfer.directory)
        } finally {
            store.remove(scope, hash, "Celeb Jeopardy")
        }
    }

    @Test fun switchingExactScopeWithSameHashDiscardsOldEditorState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val hash = "B".repeat(40)
        val local = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val remote = "https://test.invalid/RPC2\u0000collection-scope-switch"
        val selectedScope = mutableStateOf(remote)
        try {
            store.add(remote, hash, "Remote membership")
            store.remove(local, hash, "Local membership")
            rule.setContent {
                MaterialTheme { TransferCollectionEditor(hash, "Same hash", selectedScope.value, store) {} }
            }
            waitForText("Remote membership")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Unsaved remote draft")
            rule.runOnIdle { selectedScope.value = local }
            waitForText("No manual collections")
            rule.onNodeWithText("Remote membership").assertDoesNotExist()
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithText("Unsaved remote draft").assertDoesNotExist()
            rule.onNodeWithTag("collection-name-input").performTextInput("Local membership")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Local membership"), store.forTransfer(local, hash))
            assertEquals(listOf("Remote membership"), store.forTransfer(remote, hash))
        } finally {
            store.remove(remote, hash, "Remote membership")
            store.remove(local, hash, "Local membership")
        }
    }

    @Test fun malformedMembershipIsUnavailableAndCannotBeOverwrittenByAnyMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TransferCollectionStore(context)
        val prefs = context.getSharedPreferences("chunks_collection_memberships_v1", Context.MODE_PRIVATE)
        val scope = "https://test.invalid/RPC2\u0000collection-corruption"
        val hash = "1".repeat(40)
        val otherHash = "2".repeat(40)
        val key = "membership_" + MessageDigest.getInstance("SHA-256")
            .digest((scope + "\u0000" + hash).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        try {
            assertTrue(prefs.edit().putString(key, "malformed").commit())
            assertFalse(store.add(scope, hash, "Do not overwrite"))
            assertFalse(store.remove(scope, hash, "Do not overwrite"))
            assertFalse(store.addBatch(scope, listOf(otherHash, hash), "No partial batch"))
            assertEquals("malformed", prefs.getString(key, null))
            assertEquals(emptyList<String>(), store.readForEdit(scope, otherHash))
            rule.setContent {
                MaterialTheme { TransferCollectionEditor(hash, "Corrupt membership", scope, store) {} }
            }
            waitForText("Collection memberships unavailable")
            rule.onNodeWithText("No manual collections").assertDoesNotExist()
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Recovered")
            rule.onNodeWithText("Add to collection").assertIsNotEnabled()
            assertEquals("malformed", prefs.getString(key, null))
            // A correctly typed but malformed set must also be preserved.
            val invalidSet = setOf("Valid", "  Invalid  ")
            assertTrue(prefs.edit().putStringSet(key, invalidSet).commit())
            assertFalse(store.add(scope, hash, "Do not normalize"))
            assertFalse(store.remove(scope, hash, "Valid"))
            assertFalse(store.addBatch(scope, listOf(otherHash, hash), "No partial batch"))
            assertEquals(invalidSet, prefs.getStringSet(key, null))
            // Simulate an independent repair, then explicitly reload before allowing an edit.
            assertTrue(prefs.edit().putStringSet(key, setOf("Restored")).commit())
            rule.onNodeWithText("Reload memberships").performClick()
            waitForText("Restored")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Membership saved on this device. Sharing is unchanged.")
            assertEquals(listOf("Recovered", "Restored"), store.readForEdit(scope, hash))
        } finally {
            prefs.edit().remove(key).commit()
            store.remove(scope, otherHash, "No partial batch")
        }
    }

    @Test fun switchingScopeDuringCommitReloadsPersistedOldScopeWithoutLeakingResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realStore = TransferCollectionStore(context)
        val prefs = context.getSharedPreferences("chunks_collection_memberships_v1", Context.MODE_PRIVATE)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wrappedPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return Proxy.newProxyInstance(
                    javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
                ) { proxy, method, args ->
                    if (method.name == "commit") {
                        started.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Commit gate timed out" }
                        delegate.commit()
                    } else {
                        val result = method.invoke(delegate, *(args ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        val wrappedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "chunks_collection_memberships_v1") wrappedPrefs
                else super.getSharedPreferences(name, mode)
        }
        val store = TransferCollectionStore(wrappedContext)
        val hash = "3".repeat(40)
        val first = "https://test.invalid/RPC2\u0000collection-pending-first"
        val second = "https://test.invalid/RPC2\u0000collection-pending-second"
        val selected = mutableStateOf(first)
        try {
            realStore.remove(first, hash, "First account only")
            realStore.remove(second, hash, "First account only")
            rule.setContent {
                MaterialTheme { TransferCollectionEditor(hash, "Same hash", selected.value, store) {} }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("First account only")
            rule.onNodeWithText("Add to collection").performClick()
            assertTrue("Commit must be in flight", started.await(10, TimeUnit.SECONDS))
            rule.runOnIdle { selected.value = second }
            // The new scope composes before the old write is allowed to return.
            rule.onNodeWithText("Edit collections").assertExists()
            release.countDown()
            waitForText("No manual collections")
            rule.onNodeWithText("Membership saved on this device. Sharing is unchanged.").assertDoesNotExist()
            rule.onNodeWithText("First account only").assertDoesNotExist()
            assertEquals(emptyList<String>(), realStore.readForEdit(second, hash))
            rule.runOnIdle { selected.value = first }
            waitForText("First account only")
            assertEquals(listOf("First account only"), realStore.readForEdit(first, hash))
        } finally {
            release.countDown()
            realStore.remove(first, hash, "First account only")
            realStore.remove(second, hash, "First account only")
        }
    }

    @Test fun unverifiedCommitRequiresExplicitRecoveryEvenAfterScopeSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realStore = TransferCollectionStore(context)
        val prefs = context.getSharedPreferences("chunks_collection_memberships_v1", Context.MODE_PRIVATE)
        val reportFailure = AtomicBoolean(true)
        val commits = AtomicInteger()
        val wrappedPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return Proxy.newProxyInstance(
                    javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
                ) { proxy, method, args ->
                    if (method.name == "commit") {
                        commits.incrementAndGet()
                        check(delegate.commit()) { "Fixture commit failed" }
                        !reportFailure.get()
                    } else {
                        val result = method.invoke(delegate, *(args ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        val wrappedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "chunks_collection_memberships_v1") wrappedPrefs
                else super.getSharedPreferences(name, mode)
        }
        val store = TransferCollectionStore(wrappedContext)
        val hash = "4".repeat(40)
        val bystander = "5".repeat(40)
        val first = "https://test.invalid/RPC2\u0000collection-uncertain-first"
        val second = "https://test.invalid/RPC2\u0000collection-uncertain-second"
        val selected = mutableStateOf(first)
        try {
            realStore.remove(first, hash, "Pending collection")
            rule.setContent {
                MaterialTheme { TransferCollectionEditor(hash, "Same hash", selected.value, store) {} }
            }
            waitForText("No manual collections")
            rule.onNodeWithText("Edit collections").performClick()
            rule.onNodeWithTag("collection-name-input").performTextInput("Pending collection")
            rule.onNodeWithText("Add to collection").performClick()
            waitForText("Observed: Pending collection. Save not confirmed.")
            rule.onNodeWithText("Membership saved on this device. Sharing is unchanged.").assertDoesNotExist()
            assertEquals(TransferCollectionStore.Observation(listOf("Pending collection"), true),
                realStore.observeForEdit(first, hash))
            assertFalse(realStore.add(first, hash, "Pending collection"))
            assertFalse(realStore.remove(first, hash, "Pending collection"))
            assertFalse(realStore.addBatch(first, listOf(bystander, hash), "Pending collection"))
            assertEquals(emptyList<String>(), realStore.readForEdit(first, bystander))
            assertEquals(1, commits.get())
            rule.runOnIdle { selected.value = second }
            waitForText("No manual collections")
            rule.onNodeWithText("Confirm save observed memberships").assertDoesNotExist()
            rule.runOnIdle { selected.value = first }
            waitForText("Observed: Pending collection. Save not confirmed.")
            rule.onNodeWithText("Reload memberships").performClick()
            waitForText("Observed: Pending collection. Save not confirmed.")
            assertEquals(1, commits.get())
            reportFailure.set(false)
            rule.onNodeWithText("Confirm save observed memberships").performClick()
            waitForText("Collection memberships confirmed saved.")
            assertEquals(2, commits.get())
            assertEquals(listOf("Pending collection"), realStore.readForEdit(first, hash))
            // An uncertain deletion must not turn into a confirmed empty membership on reopening.
            reportFailure.set(true)
            assertFalse(store.remove(first, hash, "Pending collection"))
            assertEquals(TransferCollectionStore.Observation(emptyList(), true),
                realStore.observeForEdit(first, hash))
            assertFalse(store.addBatch(first, listOf(hash, bystander), "New collection"))
            reportFailure.set(false)
            assertTrue(store.confirmObserved(first, hash, emptyList()))
            assertEquals(4, commits.get())
            assertEquals(emptyList<String>(), realStore.readForEdit(first, hash))
        } finally {
            reportFailure.set(false)
            val current = realStore.observeForEdit(first, hash)
            if (current.writeUnverified) realStore.confirmObserved(first, hash, current.names)
            realStore.remove(first, hash, "Pending collection")
            realStore.remove(first, bystander, "Pending collection")
        }
    }
}
