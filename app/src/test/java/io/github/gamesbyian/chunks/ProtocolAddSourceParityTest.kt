package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Protocol clients (Transmission/qBittorrent/Deluge) reach the same
 * source-first intake as Local and rTorrent: the shared source section, an
 * HTTPS .torrent link fetched and validated on the device, canonical magnet
 * checking, and the global original-.torrent Keep/Archive/Delete policy
 * applied only after a verified NEW registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolAddSourceParityTest {
    @get:Rule val rule = createComposeRule()

    private val torrentBytes: ByteArray = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request("parity",
        listOf(TorrentMetainfoBuilder.Entry(listOf("a.bin"), 20_000L) { ByteArray(20_000).inputStream() }),
        trackerTiers = emptyList(), pieceLength = 16 * 1024)).torrent
    private val torrentHash = TorrentMetainfoReader.preview(torrentBytes).infoHashV1

    private class Fake : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        var added: ClientAddRequest? = null
        var writes = 0
        var loseReply = false
        override suspend fun session() = ClientSessionInfo(kind, "4.0", "/srv/dl",
            ClientCapability.entries.toSet(), ClientFilePriority.entries.toSet())
        override suspend fun torrents() = listOfNotNull(added?.let { torrent(it.source.infoHash) })
        override suspend fun torrent(hash: String): ClientTorrent? = added?.takeIf { it.source.infoHash == hash }?.let {
            if (loseReply) null else ClientTorrent(hash, "x", ClientRunState.STOPPED, null, null, null, null,
                it.directory ?: "/srv/dl")
        }
        override suspend fun files(hash: String) = emptyList<io.github.gamesbyian.chunks.rpc.ClientFile>()
        override suspend fun add(request: ClientAddRequest) {
            writes++; added = request
            if (loseReply) throw io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException("lost")
        }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    private fun render(fake: Fake, attempt: ProtocolAddAttempt, draft: androidx.compose.runtime.MutableState<String>,
                       torrent: androidx.compose.runtime.MutableState<PendingLocalAdd?>,
                       fetched: MutableList<String> = mutableListOf(),
                       alias: androidx.compose.runtime.MutableState<String>? = null) {
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "tr", clientLabel = "Box",
                    createOperations = { ClientOperations(fake, 3, 1) },
                    destinationPicker = {},
                    sourceDraft = draft, reviewedTorrent = torrent,
                    startState = mutableStateOf(false), attempt = attempt,
                    attemptScope = androidx.compose.runtime.rememberCoroutineScope(),
                    onViewTransfer = {},
                    aliasState = alias,
                    fetchTorrentLink = { link ->
                        fetched += link.uri.toString()
                        TorrentLinkResult.Fetched(torrentBytes, TorrentMetainfoReader.preview(torrentBytes), "example.org")
                    }
                )
            }
        }
        rule.waitForIdle()
    }

    private fun archiveDirectory() = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
        SourceTorrentArchive.DIRECTORY)

    @Test fun anHttpsLinkIsFetchedOnlyOnTapReviewedAndAddedOnceAsATorrentFile() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val draft = mutableStateOf("")
        val torrent = mutableStateOf<PendingLocalAdd?>(null)
        val fetched = mutableListOf<String>()
        render(fake, attempt, draft, torrent, fetched)
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextInput("https://example.org/parity.torrent")
        rule.waitForIdle()
        assertTrue("typing a link fetches nothing", fetched.isEmpty())
        rule.onNodeWithText("Get torrent from link").performScrollTo().performClick()
        rule.waitUntil(5_000) { torrent.value != null }
        assertEquals(listOf("https://example.org/parity.torrent"), fetched)
        assertNull("a fetched link has no deletable original", torrent.value?.originalDocument)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        assertTrue(attempt.outcome is ClientOperationOutcome.Verified)
        assertEquals(1, fake.writes)
        assertEquals(torrentHash, (fake.added!!.source as ClientAddSource.TorrentFile).infoHash)
    }

    @Test fun aBase32MagnetIsNormalizedToItsHexIdentityLikeEveryClient() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        // 32 base32 characters: the same canonical check as Local and rTorrent.
        val draft = mutableStateOf("magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U&dn=b")
        render(fake, attempt, draft, mutableStateOf(null))
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        val sent = fake.added!!.source as ClientAddSource.Magnet
        assertTrue(sent.infoHash.matches(Regex("[0-9a-f]{40}")))
        assertEquals(1, fake.writes)
    }

    @Test fun aVerifiedNewAddArchivesThePersonsOriginalTorrentByPolicy() {
        archiveDirectory().deleteRecursively()
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val document = Uri.parse("content://chunks.test/document/parity.torrent")
        val torrent = mutableStateOf<PendingLocalAdd?>(
            PendingLocalAdd(TorrentMetainfoReader.preview(torrentBytes), torrentBytes, document))
        render(fake, attempt, mutableStateOf(""), torrent)
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_ORIGINAL_HANDLING_SUMMARY))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.originalNote != null }
        assertTrue(attempt.outcome is ClientOperationOutcome.Verified)
        assertTrue(attempt.originalNote!!.contains("archived"))
        assertEquals(1, archiveDirectory().listFiles().orEmpty().count { it.name.startsWith(torrentHash) })
        assertNull("this provider cannot delete, so no removal is requested", attempt.originalDeletion)
    }

    @Test fun anUncertainAddKeepsTheOriginalAndArchivesNothing() {
        archiveDirectory().deleteRecursively()
        val fake = Fake().apply { loseReply = true }
        val attempt = ProtocolAddAttempt()
        val document = Uri.parse("content://chunks.test/document/parity.torrent")
        render(fake, attempt, mutableStateOf(""), mutableStateOf(
            PendingLocalAdd(TorrentMetainfoReader.preview(torrentBytes), torrentBytes, document)))
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChunksSemantics.ADD_ORIGINAL_HANDLING_SUMMARY))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.originalNote != null }
        assertTrue(attempt.originalNote!!.contains("kept"))
        assertTrue(archiveDirectory().listFiles().orEmpty().none { it.name.startsWith(torrentHash) })
        assertNull(attempt.originalDeletion)
        assertEquals(1, fake.writes)
    }

    private fun aliases() = TransferAliasStore(ApplicationProvider.getApplicationContext())

    @Test fun aVerifiedNewProtocolAddSavesItsDisplayNameUnderTheExactProfileScope() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val alias = mutableStateOf("My Parity Show")
        render(fake, attempt, mutableStateOf(""), mutableStateOf(
            PendingLocalAdd(TorrentMetainfoReader.preview(torrentBytes), torrentBytes)), alias = alias)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null && alias.value.isEmpty() }
        assertTrue(attempt.outcome is ClientOperationOutcome.Verified)
        assertEquals("My Parity Show", aliases().get(ProtocolAliasScope.of("tr"), torrentHash))
        assertNull("another exact profile never sees it", aliases().get(ProtocolAliasScope.of("other"), torrentHash))
    }

    @Test fun anUncertainProtocolAddSavesNoDisplayName() {
        val fake = Fake().apply { loseReply = true }
        val attempt = ProtocolAddAttempt()
        val alias = mutableStateOf("Never Saved")
        render(fake, attempt, mutableStateOf(""), mutableStateOf(
            PendingLocalAdd(TorrentMetainfoReader.preview(torrentBytes), torrentBytes)), alias = alias)
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        assertNull(aliases().get(ProtocolAliasScope.of("tr"), torrentHash))
        assertEquals("the draft is kept for the person", "Never Saved", alias.value)
    }
}
