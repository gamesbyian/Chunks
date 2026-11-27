package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.NewRemoteReadOnlyCapabilities
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual ChunksApp -> UniversalAddRoute -> exact typed-client gate; no OS-authentication claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ProtocolAddPrivacyRouteTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factories = AtomicInteger()
    private val writes = AtomicInteger()
    private val fake = object : TorrentClient {
        override val kind = TorrentClientKind.DELUGE
        override suspend fun session() = ClientSessionInfo(kind, "2.1", "/srv/dl", ClientCapability.entries.toSet())
        override suspend fun torrents() = emptyList<ClientTorrent>()
        override suspend fun torrent(hash: String): ClientTorrent? = null
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) { writes.incrementAndGet() }
        override suspend fun setStarted(hash: String, started: Boolean) { writes.incrementAndGet() }
        override suspend fun remove(hash: String, deleteData: Boolean) { writes.incrementAndGet() }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) { writes.incrementAndGet() }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) { writes.incrementAndGet() }
        override suspend fun recheck(hash: String) { writes.incrementAndGet() }
    }
    private val profile = CombinedReadOnlyProtocolProfile("ab".repeat(32), RemoteBackendKind.DELUGE, "Privacy fixture", 1L,
        operations = { factories.incrementAndGet(); ClientOperations(fake) },
        inspect = {
            val now = System.currentTimeMillis()
            val id = EnvironmentId("ab".repeat(32))
            CombinedReadOnlyProtocolReceipt(RemoteBackendKind.DELUGE,
                EnvironmentTransferSnapshot(id, EnvironmentAvailability.LIVE, true, now, emptyList()),
                NewRemoteReadOnlyCapabilities.forBackend(RemoteBackendKind.DELUGE, id, now))
        })
    private val privacyScope = requireNotNull(CombinedReadOnlyProtocolAdmission.privacyScope(profile))

    private fun policy(mode: TransferLockMode) {
        assertTrue(TransferPrivacyPolicyStore(context).setMode(privacyScope, mode, true))
        assertTrue(TransferPrivacyRuntime.session.configure(privacyScope, mode, true))
    }

    @After fun cleanup() = policy(TransferLockMode.NEVER)

    private fun openActualAdd(mode: TransferLockMode) {
        policy(mode)
        val owner = LocalAddAttemptOwner().apply {
            addDestination.value = UniversalAddRouteTarget.Protocol(profile.id)
        }
        val remote = RemoteSessionOwner().apply {
            transientProtocolProfile.value = profile
            verifiedProtocolProfileId.value = profile.id
        }
        rule.setContent { ChunksTheme { ChunksApp(owner, remote) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.waitForIdle()
    }

    @Test fun lockedExactClientNeverConstructsItsAddOperations() {
        openActualAdd(TransferLockMode.ONCE_PER_PROCESS)
        rule.onNodeWithText("Deluge client locked").assertExists()
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertDoesNotExist()
        assertEquals("locked route did not construct the typed Add operations", 0, factories.get())
        assertEquals("navigation never writes", 0, writes.get())
    }

    @Test fun unprotectedExactClientActuallyReachesItsAddForm() {
        openActualAdd(TransferLockMode.NEVER)
        rule.onNodeWithText("Deluge client locked").assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).assertExists()
        rule.waitUntil(5_000) { factories.get() > 0 }
        assertEquals("navigation never writes", 0, writes.get())
    }
}
