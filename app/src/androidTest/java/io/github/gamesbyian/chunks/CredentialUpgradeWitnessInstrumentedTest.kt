package io.github.gamesbyian.chunks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.MediaSettingsStore
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Credential upgrade witness (A2611-05, A2611-09). `credential-upgrade-witness.yml`
 * installs a PREVIOUS app APK, runs [CredentialUpgradeSeedInstrumentedTest]
 * against it, upgrades in place to the candidate APK (same signing key, higher
 * versionCode, data and Keystore entries retained) and then runs
 * [CredentialUpgradeVerifyInstrumentedTest]. Both skip everywhere else.
 *
 * The two phases live in separate classes so the runtime never links the
 * candidate's constructors against the previous APK's classes.
 */
internal object CredentialUpgradeWitness {
    const val ARGUMENT = "chunksUpgradeWitness"
    val login = RemoteCredentials(
        "https://upgrade-witness.example/hosted%20account/RPC2", "witness", "saved ☃ before upgrade"
    )
    val rosterEntry = RemoteCredentials(
        "https://upgrade-witness.example/rutorrent2/RPC2", "witness", "roster secret"
    )
    const val ROSTER_LABEL = "Upgrade witness"
    const val ROSTER_VERIFIED_AT = 1_700_000_000_000L
    const val MEDIA_CREDENTIAL = "upgrade-witness-tmdb-credential"

    fun phase(): String? = InstrumentationRegistry.getArguments().getString(ARGUMENT)
    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Construct a store in WHICHEVER app APK is installed, with all optional
     * arguments at their defaults: either a plain `(Context)` constructor or
     * Kotlin's synthetic default-argument constructor
     * `(Context, ..., int mask, DefaultConstructorMarker)`.
     */
    fun newStore(className: String, context: Context): Any {
        val type = Class.forName(className)
        type.declaredConstructors.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }?.let { it.isAccessible = true; return it.newInstance(context) }
        val synthetic = type.declaredConstructors.single {
            val p = it.parameterTypes
            p.size >= 3 && p[0] == Context::class.java && p[p.size - 2] == Int::class.javaPrimitiveType &&
                p.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        synthetic.isAccessible = true
        val types = synthetic.parameterTypes
        val optional = 1 until types.size - 2
        val mask = optional.fold(0) { bits, index -> bits or (1 shl index) }
        val args = arrayOfNulls<Any>(types.size)
        args[0] = context
        for (index in optional) args[index] = zeroFor(types[index])
        args[types.size - 2] = mask
        return synthetic.newInstance(*args)
    }

    private fun zeroFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}

/** Runs against the PREVIOUS app APK. Uses only signatures stable across releases. */
@RunWith(AndroidJUnit4::class)
class CredentialUpgradeSeedInstrumentedTest {
    @Test fun seedRecordsWithPreviousRelease() {
        assumeTrue("Only in credential-upgrade-witness.yml", CredentialUpgradeWitness.phase() == "seed")
        val context = CredentialUpgradeWitness.context()
        // Resolved reflectively: the candidate's compiled constructor signature
        // may not exist in the base APK (Kotlin default parameters).
        val login = CredentialUpgradeWitness.newStore("io.github.gamesbyian.chunks.RememberedRemoteLogin", context)
        val loginClass = login.javaClass
        assertEquals(true, loginClass.getMethod("setRememberingEnabled", Boolean::class.javaPrimitiveType)
            .invoke(login, true))
        assertEquals(true, loginClass.getMethod("clear").invoke(login))
        assertEquals(true, loginClass.getMethod("save", RemoteCredentials::class.java)
            .invoke(login, CredentialUpgradeWitness.login))

        val roster = CredentialUpgradeWitness.newStore("io.github.gamesbyian.chunks.RememberedRemoteProfileRoster", context)
        val rosterClass = roster.javaClass
        assertEquals(true, rosterClass.getMethod("clearAll").invoke(roster))
        assertEquals(true, rosterClass.getMethod("saveVerified", RemoteCredentials::class.java,
            String::class.java, Long::class.javaPrimitiveType)
            .invoke(roster, CredentialUpgradeWitness.rosterEntry, CredentialUpgradeWitness.ROSTER_LABEL,
                CredentialUpgradeWitness.ROSTER_VERIFIED_AT))

        val media = MediaSettingsStore::class.java.getConstructor(Context::class.java).newInstance(context)
        assertTrue("media credential must be Keystore-sealed, not memory-only",
            media.setTmdbCredential(CredentialUpgradeWitness.MEDIA_CREDENTIAL))
        // The store persists with apply(); a commit on the same file writes the
        // whole in-memory map synchronously before this process is replaced.
        assertTrue(context.getSharedPreferences("chunks_media_settings", Context.MODE_PRIVATE).edit().commit())
    }
}

/** Runs against the CANDIDATE app APK after an in-place upgrade; then cleans up. */
@RunWith(AndroidJUnit4::class)
class CredentialUpgradeVerifyInstrumentedTest {
    @Test fun recordsSealedByPreviousReleaseOpenAfterUpgrade() {
        assumeTrue("Only in credential-upgrade-witness.yml", CredentialUpgradeWitness.phase() == "verify")
        val context = CredentialUpgradeWitness.context()
        val login = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val media = MediaSettingsStore(context)
        try {
            assertEquals(CredentialUpgradeWitness.login, login.load())
            val profiles = roster.list()
            assertEquals(1, profiles.size)
            assertEquals(CredentialUpgradeWitness.rosterEntry, profiles.single().credentials)
            assertEquals(CredentialUpgradeWitness.ROSTER_LABEL, profiles.single().label)
            assertEquals(CredentialUpgradeWitness.ROSTER_VERIFIED_AT, profiles.single().lastVerifiedAt)
            assertEquals(CredentialUpgradeWitness.MEDIA_CREDENTIAL, media.tmdbCredential())
            // The candidate can also rewrite each record under the retained key.
            assertTrue(login.save(CredentialUpgradeWitness.login))
            assertEquals(CredentialUpgradeWitness.login, RememberedRemoteLogin(context).load())
        } finally {
            assertTrue(login.clear())
            assertTrue(roster.clearAll())
            media.setTmdbCredential(null)
        }
    }
}
