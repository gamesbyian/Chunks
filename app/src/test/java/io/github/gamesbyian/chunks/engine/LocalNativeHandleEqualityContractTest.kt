package io.github.gamesbyian.chunks.engine

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code

/**
 * Native G1 dependency contract for save alerts, CACHE_FLUSHED and magnet
 * METADATA_RECEIVED dispatch. TorrentAlert.handle() creates a fresh Java
 * wrapper on every callback; LocalTorrentEngine deliberately resolves it by
 * libtorrent4j's native-backed TorrentHandle.equals, not Java object identity.
 *
 * Pinned libtorrent4j 2.1.0-39 implements equals via swig torrent_handle.eq.
 * This focused test must fail on a future wrapper upgrade that removes that
 * override, even when JNI and the rest of the app still compile.
 */
class LocalNativeHandleEqualityContractTest {
    @get:Rule val temp = TemporaryFolder()
    private var session: SessionManager? = null

    @Before fun native() = DesktopLibtorrent.ensureLoaded()

    @After fun closeNative() { session?.stop() }

    @Test fun separateJavaWrappersForTheSameNativeTorrentCompareEqual() {
        val root = File(temp.root, "handle-fixture").apply { check(mkdirs()) }
        File(root, "known-payload.bin").writeBytes(ByteArray(32_000) { (it % 251).toByte() })
        val torrentBytes = TorrentBuilder().path(root).pieceSize(16 * 1024)
            .generate().entry().bencode()
        val info = TorrentInfo.bdecode(torrentBytes)
        val engine = SessionManager()
        session = engine
        val settings = SettingsPack().listenInterfaces("127.0.0.1:0")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        engine.start(SessionParams(settings))
        val params = AddTorrentParams().apply {
            setTorrentInfo(info)
            setSavePath(root.parentFile.absolutePath)
        }
        val error = error_code()
        val raw = engine.swig().add_torrent(params.swig(), error)
        assertEquals("Synchronous native Add must succeed", 0, error.value())
        assertTrue(raw.is_valid)
        val stored = TorrentHandle(raw)
        val callbackStyle = requireNotNull(engine.find(info.infoHash()))
        assertNotSame("Each caller receives a separate Java wrapper", stored, callbackStyle)
        assertTrue("Callback matching needs native wrapper equality", stored == callbackStyle)
        assertEquals("Equal native handles must have equal hash codes",
            stored.hashCode(), callbackStyle.hashCode())
        assertTrue("Native equality must be symmetric", callbackStyle == stored)
        assertFalse("Fresh wrappers must not be identity-equal", stored === callbackStyle)
    }
}
