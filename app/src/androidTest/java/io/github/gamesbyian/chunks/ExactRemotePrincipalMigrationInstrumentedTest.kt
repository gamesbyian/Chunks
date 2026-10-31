package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacySession
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.security.MessageDigest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Existing encrypted records and enabled privacy locks must survive exact-principal migration. */
@RunWith(AndroidJUnit4::class)
class ExactRemotePrincipalMigrationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val endpoint = "https://identity-fixture.invalid/RPC2"
    private val plain = RemoteCredentials(endpoint, "alice", "private-password-plain")
    private val padded = RemoteCredentials(endpoint, " alice ", "private-password-padded")

    @Test fun legacyEncryptedRosterMigratesWithoutOverwritingTheOtherPrincipal() {
        val login = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = login.rememberingEnabled()
        assertTrue(login.setRememberingEnabled(true))
        try {
            assertTrue(roster.clearAll())
            val oldId = RememberedRemoteProfileRoster.identityOf(endpoint, plain.username)
            val newId = RememberedRemoteProfileRoster.identityOf(endpoint, padded.username)
            assertNotEquals(oldId, newId)
            // Emulate an actual old-format encrypted row whose ID was computed
            // with username.trim(), but which retained exact HTTP credentials.
            val rows = JSONArray().put(JSONArray()
                .put(oldId).put("Padded account").put(padded.endpoint)
                .put(padded.username).put(padded.password))
            assertTrue(roster.writeRows(rows))
            val migrated = RememberedRemoteProfileRoster(context).list().single()
            assertEquals(newId, migrated.id)
            assertEquals(padded.username, migrated.credentials.username)
            assertEquals(padded.password, migrated.credentials.password)
            assertEquals(newId, RememberedRemoteProfileRoster(context).list().single().id)
            assertTrue(roster.saveVerified(plain))
            val both = RememberedRemoteProfileRoster(context).list()
            assertEquals(2, both.size)
            assertEquals(setOf(oldId, newId), both.map { it.id }.toSet())
            assertEquals(setOf(plain.username, padded.username),
                both.map { it.credentials.username }.toSet())
            assertTrue(roster.forget(newId))
            assertEquals(listOf(plain.username), roster.list().map { it.credentials.username })
        } finally {
            roster.clearAll()
            login.setRememberingEnabled(original)
        }
    }

    private fun prefKey(scope: String): String = "policy_" +
        MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun previouslyEnabledLockMigratesAndNeverSharesAnUnlockTicket() {
        val legacy = requireNotNull(RemoteTransferPrivacyScope.fromAccount(endpoint, plain.username))
        val exact = requireNotNull(RemoteTransferPrivacyScope.fromAccount(endpoint, padded.username))
        assertNotEquals(legacy, exact)
        val prefs = context.getSharedPreferences("chunks_transfer_privacy_policy_v1",
            Context.MODE_PRIVATE)
        // Isolate only these fixture keys. No real account settings are changed.
        assertTrue(prefs.edit().remove(prefKey(legacy)).remove(prefKey(exact)).commit())
        try {
            val store = TransferPrivacyPolicyStore(context)
            assertTrue(store.setMode(legacy, TransferLockMode.ONCE_PER_PROCESS, false))
            assertEquals(TransferLockMode.ONCE_PER_PROCESS,
                TransferPrivacyPolicyStore(context).mode(exact))
            // Different process-local tickets remain strictly isolated.
            val session = TransferPrivacySession { 10_000L }
            assertTrue(session.configure(legacy, TransferLockMode.ONCE_PER_PROCESS, false))
            assertTrue(session.configure(exact, TransferLockMode.ONCE_PER_PROCESS, false))
            assertTrue(session.finishUnlock(requireNotNull(session.beginUnlock(legacy)), true))
            assertTrue(session.mayView(legacy))
            assertFalse(session.mayView(exact))
            // A migrated enabled policy cannot be disabled without fresh auth.
            assertFalse(store.setMode(exact, TransferLockMode.NEVER, false))
            assertTrue(store.setMode(exact, TransferLockMode.NEVER, true))
            assertEquals(TransferLockMode.NEVER,
                TransferPrivacyPolicyStore(context).mode(exact))
            assertEquals(TransferLockMode.ONCE_PER_PROCESS,
                TransferPrivacyPolicyStore(context).mode(legacy))
            // Migration must not resurrect a user-authorized opt-out.
            assertEquals(TransferLockMode.NEVER, TransferPrivacyPolicyStore(context).mode(exact))
        } finally {
            assertTrue(prefs.edit().remove(prefKey(legacy)).remove(prefKey(exact)).commit())
        }
    }
}
