package io.github.gamesbyian.chunks.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferPrivacyPolicyStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val local = "test-privacy-local-scope"
    private val remoteA = "https://host.invalid/RPC2\u0000alice-privacy"
    private val remoteB = "https://host.invalid/RPC2\u0000bob-privacy"

    private fun clear() {
        val store = TransferPrivacyPolicyStore(context)
        for (scope in listOf(local, remoteA, remoteB))
            store.setMode(scope, TransferLockMode.NEVER, true)
    }

    @Test fun policiesSurviveReopenWithNoPersistedUnlockAndDistinctAccountScopes() {
        clear()
        val store = TransferPrivacyPolicyStore(context)
        try {
            assertEquals(TransferLockMode.NEVER, store.mode(local))
            assertTrue(store.setMode(local, TransferLockMode.ONCE_PER_PROCESS, false))
            assertTrue(store.setMode(remoteA,
                TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND, false))
            assertEquals(TransferLockMode.NEVER,
                TransferPrivacyPolicyStore(context).mode(remoteB))
            val process = TransferPrivacySession { 12_345L }
            assertTrue(TransferPrivacyPolicyStore(context).loadInto(process, local))
            assertTrue(TransferPrivacyPolicyStore(context).loadInto(process, remoteA))
            assertTrue(TransferPrivacyPolicyStore(context).loadInto(process, remoteB))
            assertFalse(process.mayView(local))
            assertFalse(process.mayView(remoteA))
            assertTrue(process.mayView(remoteB))
            assertTrue(process.finishUnlock(requireNotNull(process.beginUnlock(local)), true))
            assertFalse(TransferPrivacySession { 12_346L }.let {
                TransferPrivacyPolicyStore(context).loadInto(it, local)
                it.mayView(local)
            })
        } finally { clear() }
    }

    @Test fun disablingOrChangingEnabledPolicyWithoutFreshAuthIsRefused() {
        clear()
        val store = TransferPrivacyPolicyStore(context)
        try {
            assertTrue(store.setMode(local, TransferLockMode.ONCE_PER_PROCESS, false))
            assertFalse(store.setMode(local, TransferLockMode.NEVER, false))
            assertFalse(store.setMode(local,
                TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND, false))
            assertEquals(TransferLockMode.ONCE_PER_PROCESS,
                TransferPrivacyPolicyStore(context).mode(local))
            assertTrue(store.setMode(local, TransferLockMode.NEVER, true))
            assertEquals(TransferLockMode.NEVER, store.mode(local))
        } finally { clear() }
    }

    @Test fun persistedKeyContainsNoRawEndpointAccountOrUnlockToken() {
        clear()
        val store = TransferPrivacyPolicyStore(context)
        try {
            assertTrue(store.setMode(remoteA, TransferLockMode.EVERY_ENTRY, false))
            val prefs = context.getSharedPreferences(
                "chunks_transfer_privacy_policy_v1", android.content.Context.MODE_PRIVATE)
            assertTrue(prefs.all.keys.none {
                it.contains("host.invalid") || it.contains("alice") || it.contains("RPC2")
            })
            assertTrue(prefs.all.values.none { value ->
                value.toString().contains("token", ignoreCase = true) ||
                    value.toString().contains("alice")
            })
        } finally { clear() }
    }
}
