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

    @Test fun optOutBlocksConcurrentSaveAndReenableCannotResurrectOldRoster() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        try {
            assertTrue(roster.clearAll())
            val entered = java.util.concurrent.CountDownLatch(1)
            // The worker signals just before attempting Save. The shared
            // credential monitor is held until OFF commits, so the worker
            // cannot authenticate a stale enabled state or race a later wipe.
            val worker = synchronized(RemoteCredentialLock.monitor) {
                Thread {
                    try {
                        entered.countDown()
                        assertFalse(roster.saveVerified(padded))
                    } catch (t: Throwable) { failures.add(t) }
                }.also { thread ->
                    thread.start()
                    assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue(vault.setRememberingEnabled(false))
                }
            }
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(failures.joinToString(), failures.isEmpty())
            assertFalse(vault.rememberingEnabled())
            assertTrue(roster.list().isEmpty())
            // Fault injection for a legacy interrupted opt-out: a roster
            // ciphertext exists while the remembering policy is OFF.
            val row = JSONArray().put(JSONArray()
                .put(RememberedRemoteProfileRoster.identityOf(endpoint, padded.username))
                .put("Legacy").put(padded.endpoint).put(padded.username).put(padded.password))
            assertTrue(roster.writeRows(row))
            assertTrue(roster.list().isEmpty())
            // Enabling must clear that ciphertext before the policy says ON.
            assertTrue(vault.setRememberingEnabled(true))
            assertTrue(roster.list().isEmpty())
            assertTrue(roster.saveVerified(plain))
            assertEquals(listOf(plain.username), roster.list().map { it.credentials.username })
            assertTrue(vault.setRememberingEnabled(false))
            assertTrue(roster.list().isEmpty())
        } finally {
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun staleConnectCannotRestoreRememberedCredentialsAfterForgetOrPolicyToggle() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        try {
            assertTrue(roster.clearAll())
            assertTrue(vault.clear())
            val pendingConnect = RemoteCredentialLock.capture()
            assertTrue(vault.setRememberingEnabled(false))
            assertTrue(vault.setRememberingEnabled(true))
            assertFalse(vault.saveIfCurrent(padded, pendingConnect))
            assertFalse(roster.saveVerifiedIfCurrent(padded, pendingConnect))
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())

            val accepted = RemoteCredentialLock.capture()
            assertTrue(vault.saveIfCurrent(plain, accepted))
            assertTrue(roster.saveVerifiedIfCurrent(plain, accepted))
            assertEquals(plain, vault.load())
            assertEquals(listOf(plain.username), roster.list().map { it.credentials.username })
            val beforeForget = RemoteCredentialLock.capture()
            assertTrue(roster.forget(RememberedRemoteProfileRoster.identityOf(
                endpoint, plain.username)))
            assertFalse(roster.saveVerifiedIfCurrent(plain, beforeForget))
            assertTrue(roster.list().isEmpty())
        } finally {
            roster.clearAll()
            vault.clear()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun interruptedOldSingleProfileCiphertextCannotReturnAfterOptBackIn() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        try {
            assertTrue(vault.clear())
            assertTrue(roster.clearAll())
            assertTrue(vault.save(padded))
            val preferences = context.getSharedPreferences("remembered-remote-login-v1",
                Context.MODE_PRIVATE)
            val oldCiphertext = requireNotNull(preferences.getString("ciphertext", null))
            assertTrue(vault.setRememberingEnabled(false))
            // Simulate a corrupted/interrupted legacy OFF transaction where
            // ciphertext outlived the policy, without involving any real host.
            assertTrue(preferences.edit().putString("ciphertext", oldCiphertext).commit())
            assertNull(vault.load())
            assertTrue(vault.setRememberingEnabled(true))
            assertFalse(preferences.contains("ciphertext"))
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())
        } finally {
            vault.clear()
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun unreadableRosterCannotBeSilentlyDroppedOrOverwritten() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val prefs = context.getSharedPreferences("chunks_remote_profiles_encrypted_v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(roster.clearAll())
            val corrupt = "corrupted-encrypted-roster-not-a-valid-base64-record"
            assertTrue(prefs.edit().putString("roster", corrupt).commit())
            assertTrue(roster.list().isEmpty())
            assertFalse(roster.saveVerified(padded))
            assertFalse(roster.forget(RememberedRemoteProfileRoster.identityOf(
                padded.endpoint, padded.username)))
            assertEquals(corrupt, prefs.getString("roster", null))
            // An explicit clear is authoritative and restores normal behavior.
            assertTrue(roster.clearAll())
            assertTrue(roster.saveVerified(plain))
            assertEquals(listOf(plain.username), roster.list().map { it.credentials.username })
        } finally {
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun unreadableLegacyLoginCannotBeSilentlyDroppedOrOverwritten() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val prefs = context.getSharedPreferences("remembered-remote-login-v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(vault.clear())
            val corrupt = "corrupted-login-ciphertext"
            assertTrue(prefs.edit().putString("ciphertext", corrupt).commit())
            assertNull(vault.load())
            assertFalse(vault.save(plain))
            assertEquals(corrupt, prefs.getString("ciphertext", null))
            assertTrue(vault.clear())
            assertTrue(vault.save(plain))
            assertEquals(plain, vault.load())
        } finally {
            vault.clear()
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun failedLegacyIdMigrationPreservesEncryptedRowsAndRetriesOnlyWhenCommitWorks() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val prefs = context.getSharedPreferences("chunks_remote_profiles_encrypted_v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(roster.clearAll())
            val oldId = RememberedRemoteProfileRoster.identityOf(endpoint, plain.username)
            val exactId = RememberedRemoteProfileRoster.identityOf(endpoint, padded.username)
            assertNotEquals(oldId, exactId)
            assertTrue(roster.writeRows(JSONArray().put(JSONArray()
                .put(oldId).put("Old principal").put(endpoint)
                .put(padded.username).put(padded.password))))
            val originalBytes = requireNotNull(prefs.getString("roster", null))
            roster.rejectPersistForTest = true
            assertTrue(roster.list().isEmpty())
            assertFalse(roster.saveVerified(plain))
            assertFalse(roster.forget(exactId))
            assertEquals(originalBytes, prefs.getString("roster", null))
            // Simulate the same failed disk commit after recreating a store.
            val reopened = RememberedRemoteProfileRoster(context)
            reopened.rejectPersistForTest = true
            assertTrue(reopened.list().isEmpty())
            assertFalse(reopened.saveVerified(plain))
            assertEquals(originalBytes, prefs.getString("roster", null))
            reopened.rejectPersistForTest = false
            val recovered = reopened.list().single()
            assertEquals(exactId, recovered.id)
            assertEquals(padded, recovered.credentials)
            assertTrue(reopened.saveVerified(plain))
            assertEquals(setOf(plain.username, padded.username),
                reopened.list().map { it.credentials.username }.toSet())
        } finally {
            roster.rejectPersistForTest = false
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun validCiphertextWithConflictingLegacyIdentityIsPreservedNotDeleted() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val prefs = context.getSharedPreferences("chunks_remote_profiles_encrypted_v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(roster.clearAll())
            // A structurally valid encrypted row with a valid-shaped but
            // mismatched ID must not be rewritten or silently erased.
            val wrongId = "a".repeat(64)
            assertTrue(roster.writeRows(JSONArray().put(JSONArray()
                .put(wrongId).put("Mismatched identity").put(endpoint)
                .put(padded.username).put(padded.password))))
            val originalBytes = requireNotNull(prefs.getString("roster", null))
            assertTrue(roster.list().isEmpty())
            assertFalse(roster.saveVerified(plain))
            assertFalse(roster.forget(wrongId))
            assertEquals(originalBytes, prefs.getString("roster", null))
            val reopened = RememberedRemoteProfileRoster(context)
            assertTrue(reopened.list().isEmpty())
            assertFalse(reopened.saveVerified(plain))
            assertEquals(originalBytes, prefs.getString("roster", null))
            assertTrue(reopened.clearAll())
            assertTrue(reopened.saveVerified(plain))
            assertEquals(plain, reopened.list().single().credentials)
        } finally {
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun overlappingVerifiedConnectsCannotCommitOlderPrincipal() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        try {
            assertTrue(vault.clear())
            assertTrue(roster.clearAll())
            val firstConnection = RemoteCredentialLock.beginConnection()
            val secondConnection = RemoteCredentialLock.beginConnection()
            assertNotEquals(firstConnection, secondConnection)
            // Out-of-order completion: only the second authenticated attempt
            // may become the remembered principal, in EITHER encrypted store.
            assertTrue(vault.saveIfCurrent(plain, secondConnection))
            assertTrue(roster.saveVerifiedIfCurrent(plain, secondConnection))
            assertFalse(vault.saveIfCurrent(padded, firstConnection))
            assertFalse(roster.saveVerifiedIfCurrent(padded, firstConnection))
            assertEquals(plain, vault.load())
            assertEquals(listOf(plain.username), roster.list().map { it.credentials.username })
            // Disconnection must revoke the current attempt's write ticket.
            RemoteCredentialLock.invalidate()
            assertFalse(roster.saveVerifiedIfCurrent(padded, secondConnection))
            assertFalse(vault.saveIfCurrent(padded, secondConnection))
        } finally {
            vault.clear()
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun temporaryAndroidKeystoreOutagePreservesBothEncryptedVaultsAcrossRetries() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val original = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val loginPrefs = context.getSharedPreferences("remembered-remote-login-v1",
            Context.MODE_PRIVATE)
        val rosterPrefs = context.getSharedPreferences("chunks_remote_profiles_encrypted_v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(vault.clear())
            assertTrue(roster.clearAll())
            assertTrue(vault.save(plain))
            assertTrue(roster.saveVerified(plain))
            val legacyBytes = requireNotNull(loginPrefs.getString("ciphertext", null))
            val rosterBytes = requireNotNull(rosterPrefs.getString("roster", null))
            vault.rejectDecryptForTest = true
            roster.rejectDecryptForTest = true
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())
            assertFalse(vault.save(padded))
            assertFalse(roster.saveVerified(padded))
            assertFalse(roster.forget(RememberedRemoteProfileRoster.identityOf(
                endpoint, plain.username)))
            assertEquals(legacyBytes, loginPrefs.getString("ciphertext", null))
            assertEquals(rosterBytes, rosterPrefs.getString("roster", null))
            // Fail again in new instances: a process/store recreation cannot
            // translate inaccessible ciphertext into a writable empty vault.
            val reopenedLogin = RememberedRemoteLogin(context)
            val reopenedRoster = RememberedRemoteProfileRoster(context)
            reopenedLogin.rejectDecryptForTest = true
            reopenedRoster.rejectDecryptForTest = true
            assertNull(reopenedLogin.load())
            assertTrue(reopenedRoster.list().isEmpty())
            assertFalse(reopenedLogin.save(padded))
            assertFalse(reopenedRoster.saveVerified(padded))
            assertEquals(legacyBytes, loginPrefs.getString("ciphertext", null))
            assertEquals(rosterBytes, rosterPrefs.getString("roster", null))
            reopenedLogin.rejectDecryptForTest = false
            reopenedRoster.rejectDecryptForTest = false
            assertEquals(plain, reopenedLogin.load())
            assertEquals(listOf(plain), reopenedRoster.list().map { it.credentials })
            assertEquals(legacyBytes, loginPrefs.getString("ciphertext", null))
            assertEquals(rosterBytes, rosterPrefs.getString("roster", null))
        } finally {
            vault.rejectDecryptForTest = false
            roster.rejectDecryptForTest = false
            vault.clear()
            roster.clearAll()
            vault.setRememberingEnabled(original)
        }
    }

    @Test fun malformedRememberingPolicyFailsClosedWithoutDroppingEncryptedLogin() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val prior = vault.rememberingEnabled()
        val prefs = context.getSharedPreferences("remembered-remote-login-v1",
            Context.MODE_PRIVATE)
        assertTrue(vault.setRememberingEnabled(true))
        try {
            assertTrue(roster.clearAll())
            assertTrue(vault.clear())
            assertTrue(vault.save(plain))
            val ciphertext = requireNotNull(prefs.getString("ciphertext", null))
            assertTrue(prefs.edit().putString("remember_enabled", "invalid-boolean").commit())
            assertFalse(vault.rememberingEnabled())
            assertNull(vault.load())
            assertFalse(vault.save(padded))
            assertEquals(ciphertext, prefs.getString("ciphertext", null))
            // Explicit opt-out and subsequent opt-in repair the bad policy,
            // but neither may resurrect the old saved login.
            assertTrue(vault.setRememberingEnabled(false))
            assertTrue(vault.setRememberingEnabled(true))
            assertNull(vault.load())
            assertFalse(prefs.contains("ciphertext"))
        } finally {
            vault.clear()
            roster.clearAll()
            vault.setRememberingEnabled(prior)
        }
    }

    @Test fun pairSaveRefusesStaleConnectAndDoesNotChangeLegacyWhenRosterCannotSave() {
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val originalPolicy = vault.rememberingEnabled()
        assertTrue(vault.setRememberingEnabled(true))
        val prefs = context.getSharedPreferences("chunks_remote_profiles_encrypted_v1",
            Context.MODE_PRIVATE)
        try {
            assertTrue(roster.clearAll())
            assertTrue(vault.clear())
            val first = RemoteCredentialLock.beginConnection()
            assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, plain, first))
            assertEquals(plain, vault.load())
            assertEquals(listOf(plain), roster.list().map { it.credentials })
            val second = RemoteCredentialLock.beginConnection()
            assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, padded, first))
            assertEquals(plain, vault.load())
            assertTrue(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, padded, second))
            assertEquals(padded, vault.load())
            assertEquals(setOf(plain.username, padded.username),
                roster.list().map { it.credentials.username }.toSet())

            // A malformed but still occupied roster is read-only. The paired
            // operation MUST refuse before changing the legacy login.
            val inaccessible = "encrypted-roster-undecryptable"
            assertTrue(prefs.edit().putString("roster", inaccessible).commit())
            val third = RemoteCredentialLock.beginConnection()
            assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, plain, third))
            assertEquals(padded, vault.load())
            assertEquals(inaccessible, prefs.getString("roster", null))

            // An explicit policy opt-out must revoke even the most recent
            // ticket and clear both credential stores.
            assertTrue(vault.setRememberingEnabled(false))
            assertFalse(RemoteCredentialLock.saveVerifiedPairIfCurrent(vault, roster, plain, third))
            assertNull(vault.load())
            assertTrue(roster.list().isEmpty())
        } finally {
            roster.clearAll()
            vault.clear()
            vault.setRememberingEnabled(originalPolicy)
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
