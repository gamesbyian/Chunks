package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Remote Universal Add: the person's own .torrent document is archived or
 * offered for deletion only after the engine verified a NEW exact job of the
 * reviewed infohash on this exact account. Every other outcome keeps it.
 * Uses the real app-private archive; no provider document is ever mutated
 * here (deletion stays a separate, confirmed request).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteOriginalTorrentHandlingTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val account = "http://127.0.0.1:9/RPC2\u0000"
    private val document = Uri.parse("content://io.github.gamesbyian.chunks.test/document/original")
    // Minimal valid single-file v1 metainfo (bencoded, sorted keys).
    private val bytes = ("d4:infod6:lengthi16384e4:name9:fixture.b12:piece lengthi16384e" +
        "6:pieces20:" + "a".repeat(20) + "ee").toByteArray(Charsets.ISO_8859_1)
    private val hash = requireNotNull(TorrentMetainfoReader.preview(bytes).infoHashV1)
    private val archive get() = File(context.filesDir, SourceTorrentArchive.DIRECTORY)

    @Before fun clean() { archive.deleteRecursively() }

    private fun submitted(handling: OriginalTorrentHandling, deletable: Boolean = false): Pair<RemoteAddSession,
        io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Ticket> {
        val session = RemoteAddSession(account, "/srv")
        session.bindOriginalDocument(document, bytes)
        val target = UniversalAddTarget.AuthenticatedServer(account)
        val intent = UniversalAddIntent(UniversalAddSource.metainfo(bytes, hash), target,
            UniversalAddPlace.ServerCustom("/srv"), startWhenAdded = false, allowArtwork = true)
        assertTrue(session.coordinator.review(intent, EnvironmentCapabilities.unprobed(target.environment),
            EnvironmentAvailability.LIVE).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(1_000L))
        session.frozenOriginal = RemoteAddSession.FrozenOriginalHandling(
            requireNotNull(session.originalDocumentFor(bytes)), handling, deletable, bytes.copyOf())
        return session to ticket
    }

    private fun archivedCopies() = archive.listFiles().orEmpty().filter { it.name.startsWith("$hash-") }

    @Test fun documentAuthorityIsBoundToTheExactReviewedBytes() {
        val session = RemoteAddSession(account, "/srv")
        session.bindOriginalDocument(document, bytes)
        assertEquals(document, session.originalDocumentFor(bytes.copyOf()))
        val changed = bytes.copyOf().also { it[it.size - 3] = 'b'.code.toByte() }
        assertNull("Different bytes never inherit the document", session.originalDocumentFor(changed))
        assertNull(session.originalDocumentFor(null))
        session.bindOriginalDocument(null, null)
        assertNull("A fetched link or magnet has no original", session.originalDocumentFor(bytes))
    }

    @Test fun onlyAVerifiedNewJobOfTheReviewedHashQualifies() {
        assertEquals(hash, remoteVerifiedNewSourceHash(RemoteAddAndStart.Started(hash.uppercase()), hash))
        assertEquals(hash, remoteVerifiedNewSourceHash(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, "/srv")), hash))
        for (unverified in listOf(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)),
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.PendingVerification(hash)),
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
            RemoteAddAndStart.StartNotVerified(hash, null)))
            assertNull(unverified.toString(), remoteVerifiedNewSourceHash(unverified, hash))
        assertNull("A different hash never qualifies",
            remoteVerifiedNewSourceHash(RemoteAddAndStart.Started("f".repeat(40)), hash))
    }

    @Test fun archiveAfterVerifiedNewRemoteAddKeepsAPrivateCopyAndTheOriginal() = runBlocking {
        val (session, ticket) = submitted(OriginalTorrentHandling.ARCHIVE)
        val note = applyRemoteOriginalHandling(context, session, ticket,
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.VerifiedStopped(hash, "/srv")))
        assertEquals(archiveOutcomeMessage(SourceArchiveOutcome.ArchivedCopy("", false)), note)
        assertArrayEquals(bytes, archivedCopies().single().readBytes())
        assertNull("Not deletable: no removal question", session.originalDeletion)
        assertNull("Frozen authority is consumed exactly once", session.frozenOriginal)
    }

    @Test fun archiveOfADeletableOriginalAsksSeparatelyBeforeRemovingIt() = runBlocking {
        val (session, ticket) = submitted(OriginalTorrentHandling.ARCHIVE, deletable = true)
        applyRemoteOriginalHandling(context, session, ticket, RemoteAddAndStart.Started(hash))
        assertEquals(1, archivedCopies().size)
        val request = requireNotNull(session.originalDeletion)
        assertTrue(request.archived)
        assertEquals(document, request.document)
        assertArrayEquals(OriginalTorrentDocuments.sha256(bytes), request.reviewedSha256)
    }

    @Test fun deleteOnlyBecomesASeparateConfirmationRequest() = runBlocking {
        val (session, ticket) = submitted(OriginalTorrentHandling.DELETE, deletable = true)
        val note = applyRemoteOriginalHandling(context, session, ticket, RemoteAddAndStart.Started(hash))
        assertNull(note)
        val request = requireNotNull(session.originalDeletion)
        assertFalse(request.archived)
        assertTrue("Delete never archives", archivedCopies().isEmpty())
    }

    @Test fun alreadyPresentOrUncertainAddsKeepTheOriginalWithoutArchiving() = runBlocking {
        for (result in listOf(
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)),
            RemoteAddAndStart.AddedStopped(RemoteAddOutcome.Uncertain(hash)),
            RemoteAddAndStart.StartNotVerified(hash, null))) {
            for (handling in listOf(OriginalTorrentHandling.ARCHIVE, OriginalTorrentHandling.DELETE)) {
                val (session, ticket) = submitted(handling, deletable = true)
                val note = applyRemoteOriginalHandling(context, session, ticket, result)
                assertEquals("$result $handling",
                    "The original .torrent file was kept because this Add did not verify a new transfer.", note)
                assertNull(session.originalDeletion)
                assertTrue(archivedCopies().isEmpty())
            }
        }
    }

    @Test fun keepDoesNothing() = runBlocking {
        val (session, ticket) = submitted(OriginalTorrentHandling.KEEP, deletable = true)
        assertNull(applyRemoteOriginalHandling(context, session, ticket, RemoteAddAndStart.Started(hash)))
        assertNull(session.originalDeletion)
        assertTrue(archivedCopies().isEmpty())
    }
}
