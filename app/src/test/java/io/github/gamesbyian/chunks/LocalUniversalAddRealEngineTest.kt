package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Local Universal Add journey on REAL libtorrent (plain JVM): the shared
 * lifecycle runs ONE native Add, and the app-owned controller's read-only
 * LocalNativeAddObserver follow-up, not a screen, verifies exact hash,
 * reviewed Place and requested Start from the engine's own list. A lost
 * reply after the native write and a duplicate source are fenced, never resent.
 */
class LocalUniversalAddRealEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private var engine: LocalTorrentEngine? = null

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engine?.close() }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        .also { it.start(); engine = it }

    /** A two-file payload placed in [root] beforehand, so its check completes locally. */
    private fun placedFixture(root: File): TorrentMetainfoBuilder.Result {
        val random = SecureRandom()
        val name = "chunks-ua-" + java.lang.Long.toString(System.nanoTime() and Long.MAX_VALUE, 36)
        val files = linkedMapOf("a.bin" to ByteArray(48 * 1024).also(random::nextBytes),
            "b.bin" to ByteArray(20 * 1024 + 7).also(random::nextBytes))
        val result = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = emptyList(), pieceLength = 16 * 1024))
        val top = File(root, TorrentMetainfoReader.preview(result.torrent).name).apply { mkdirs() }
        files.forEach { (f, b) -> File(top, f).writeBytes(b) }
        return result
    }

    private fun admit(session: LocalAddAttemptSession, place: LocalDownloadPlace,
                      torrent: ByteArray, start: Boolean): UniversalAddCoordinator.Ticket {
        val reviewed = PendingLocalAdd(TorrentMetainfoReader.preview(torrent), torrent)
        val intent = LocalUniversalAddAdapter.torrentIntent(reviewed, place, "", start, allowArtwork = false)
        assertTrue(LocalUniversalAddAdapter.review(session.coordinator, intent, true, false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(System.currentTimeMillis()))
        session.bindPlace(ticket, place)
        return ticket
    }

    private suspend fun awaitPhase(session: LocalAddAttemptSession, wanted: UniversalAddCoordinator.Phase) {
        for (i in 0 until 400) {
            if (session.coordinator.phase == wanted) return
            delay(50)
        }
        assertEquals(wanted, session.coordinator.phase)
    }

    @Test fun theSharedFollowUpVerifiesStoppedStartedLostReplyAndDuplicateOnRealLibtorrent() = runBlocking {
        val native = engine()
        val root = temp.newFolder("place").canonicalFile
        val place = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE, root, "Local test", "Test-only scope")
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val writes = AtomicInteger()
        val fence = LocalScreenObservationRevision()
        fun observer(session: LocalAddAttemptSession) = LocalNativeAddObserver(session,
            read = { withContext(Dispatchers.IO) { native.list() } }, fence = fence)
        val fast = List(400) { 50L }

        suspend fun addOnce(session: LocalAddAttemptSession, ticket: UniversalAddCoordinator.Ticket,
                            bytes: ByteArray, start: Boolean, loseReply: Boolean = false) =
            controller.run(session.coordinator, ticket, LocalNativeAddExecutor(session, place, { it.added }) { boundary, returned ->
                withContext(Dispatchers.IO) {
                    boundary.enter()
                    writes.incrementAndGet()
                    val result = LocalAddExecution.torrent(native, bytes, root, skipsRequested = false,
                        reviewedSkips = { null }, startNow = start, onAdded = returned::set)
                    if (loseReply) error("reply lost after the native write")
                    result
                }
            }, followUp = observer(session), followUpSchedule = fast)

        // 1. A stopped Add: verified by the app-owned follow-up from the real native list.
        val stopped = placedFixture(root)
        val s1 = LocalAddAttemptSession()
        val t1 = admit(s1, place, stopped.torrent, start = false)
        val r1 = addOnce(s1, t1, stopped.torrent, start = false)
        assertTrue("$r1", r1 is UniversalAddRun.Completed && r1.writeMayHaveHappened)
        assertEquals("the native write alone never verifies", UniversalAddCoordinator.Phase.READBACK_REQUIRED, s1.coordinator.phase)
        assertTrue(s1.registrationFromThisSubmission)
        awaitPhase(s1, UniversalAddCoordinator.Phase.VERIFIED)
        println("CHUNKS_LOCAL_UA: stopped=verified_by_follow_up")

        // 2. A started Add: verified only once the engine itself reports payload Start.
        val started = placedFixture(root)
        val s2 = LocalAddAttemptSession()
        val t2 = admit(s2, place, started.torrent, start = true)
        addOnce(s2, t2, started.torrent, start = true)
        awaitPhase(s2, UniversalAddCoordinator.Phase.VERIFIED)
        assertTrue(native.status(started.infoHashV1)?.stopped == false)
        println("CHUNKS_LOCAL_UA: started=verified_by_follow_up")

        // 3. The reply is lost after the native write: the fence holds, nothing is
        // resent, and the follow-up still proves THIS Add from its own native token.
        val lost = placedFixture(root)
        val s3 = LocalAddAttemptSession()
        val t3 = admit(s3, place, lost.torrent, start = false)
        val r3 = addOnce(s3, t3, lost.torrent, start = false, loseReply = true)
        assertTrue("$r3", r3 is UniversalAddRun.Failed && r3.writeMayHaveHappened)
        awaitPhase(s3, UniversalAddCoordinator.Phase.VERIFIED)
        assertEquals(1, native.list().count { it.infoHash == lost.infoHashV1 })
        println("CHUNKS_LOCAL_UA: lost_reply=fenced_then_verified_without_resend")

        // 4. The same source again: the engine refuses after the boundary, so this is
        // at best an exact existing session, never a verified new Add or a second job.
        val s4 = LocalAddAttemptSession()
        val t4 = admit(s4, place, stopped.torrent, start = false)
        addOnce(s4, t4, stopped.torrent, start = false)
        assertTrue((s4.registrationFromThisSubmission).not())
        awaitPhase(s4, UniversalAddCoordinator.Phase.SESSION_CONFIRMED)
        assertEquals(1, native.list().count { it.infoHash == stopped.infoHashV1 })
        println("CHUNKS_LOCAL_UA: duplicate=exact_session_not_new_add")

        assertEquals("exactly one native Add per ticket", 4, writes.get())
        // A resolved phase ends the follow-up at its next tick, without another read.
        for (i in 0 until 100) { if (!controller.isFollowingUp(EnvironmentSnapshots.LOCAL)) break; delay(20) }
        assertTrue(!controller.isFollowingUp(EnvironmentSnapshots.LOCAL))
        scope.cancel()
    }
}
