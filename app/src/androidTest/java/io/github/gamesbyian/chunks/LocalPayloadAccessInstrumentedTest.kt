package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

@RunWith(AndroidJUnit4::class)
class LocalPayloadAccessInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeCompletedFileProviderSupportsSeekAndRefusesWritesSubstitutionAndRevocation() {
        val root = File(context.cacheDir, "payload-access-" + System.nanoTime()).apply { assertTrue(mkdir()) }
        val sourceFolder = File(root, "Source").apply { assertTrue(mkdir()) }
        val bytes = ByteArray(1_200_123).also { Random(781).nextBytes(it) }
        val file = File(sourceFolder, "payload.bin").apply { writeBytes(bytes) }
        val foreign = File(root, "foreign.bin").apply { writeText("foreign canary") }
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        val policy = TransferPrivacyPolicyStore(context)
        val scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
        val previous = policy.mode(scope)
        val resolver = context.contentResolver
        try {
            assertTrue(policy.setMode(scope, TransferLockMode.NEVER, true))
            engine.start()
            val torrent = TorrentBuilder().path(sourceFolder).pieceSize(16 * 1024).generate().entry().bencode()
            val hash = (engine.addStopped(torrent, root) as LocalAddResult.AddedStopped).status.infoHash
            engine.resume(hash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (engine.status(hash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() >= deadline) fail("Native payload fixture did not seed")
                Thread.sleep(100)
            }
            val review = requireNotNull(LocalFileInspection.review(engine, hash))
            val row = review.files.single()
            assertNull(LocalPayloadAccess.prepare(context, engine,
                review.copy(nativeToken = review.nativeToken + "-stale"), row) { false })
            assertNull(LocalPayloadAccess.prepare(context, engine, review,
                row.copy(index = row.index + 1)) { false })
            assertNull(LocalPayloadAccess.prepare(context, engine, review, row) { true })
            val prepared = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            val uri = requireNotNull(LocalPayloadAccess.issue(context, prepared))
            assertFalse(uri.toString().contains(row.path))
            assertFalse(uri.toString().contains(hash))
            assertNull("A prepared handoff is single-use", LocalPayloadAccess.issue(context, prepared))
            val unknown = Uri.Builder().scheme("content").authority(LocalPayloadAccess.authority(context))
                .appendPath("00000000-0000-0000-0000-000000000000").build()
            assertNotNull(runCatching { resolver.openFileDescriptor(unknown, "r")?.close() }.exceptionOrNull())
            assertNotNull(runCatching { resolver.openFileDescriptor(uri, "rw")?.close() }.exceptionOrNull())
            assertNotNull(runCatching { resolver.openFileDescriptor(uri, "w")?.close() }.exceptionOrNull())
            requireNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)).use {
                assertTrue(it.moveToFirst())
                assertEquals("payload.bin", it.getString(0))
                assertEquals(bytes.size.toLong(), it.getLong(1))
            }

            fun readAt(descriptor: ParcelFileDescriptor, offset: Int, count: Int): ByteArray {
                val actual = ByteArray(count)
                assertEquals(count, Os.pread(descriptor.fileDescriptor, actual, 0, count, offset.toLong()))
                return actual
            }
            // A separate APK UID must fail without a grant and read only
            // after the precise URI read permission is explicitly granted.
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            assertNotEquals(context.packageName, testPackage)
            assertEquals("refused", externalRead(uri, testPackage))
            context.grantUriPermission(testPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val expected = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes.copyOfRange(8193, 8225))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, externalRead(uri, testPackage))
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals("refused", externalRead(uri, testPackage))

            val seek = requireNotNull(resolver.openFileDescriptor(uri, "r"))
            try {
                val end = bytes.size - 19
                assertArrayEquals(bytes.copyOfRange(end, bytes.size), readAt(seek, end, 19))
                assertArrayEquals(bytes.copyOfRange(513, 528), readAt(seek, 513, 15))
                assertEquals(0, Os.pread(seek.fileDescriptor, ByteArray(1), 0, 1, bytes.size.toLong()))
                LocalPayloadAccess.revoke(context, uri)
                assertNull(LocalPayloadAccess.lookup(context, uri))
                // Read a region not previously requested. A raw FD or stale
                // proxy implementation would still disclose those bytes.
                val revoked = runCatching {
                    Os.pread(seek.fileDescriptor, ByteArray(16), 0, 16, 600_000L)
                }
                assertTrue("Revoked grant must not disclose another range",
                    revoked.isFailure || (revoked.getOrNull() ?: 0) <= 0)
            } finally { seek.close() }
            assertNotNull(runCatching { resolver.openFileDescriptor(uri, "r")?.close() }.exceptionOrNull())

            assertNull("Revoking a token cannot restore its preparation",
                LocalPayloadAccess.issue(context, prepared))

            val beforeRevoke = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            val activePreparation = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            val active = requireNotNull(LocalPayloadAccess.issue(context, activePreparation))
            requireNotNull(resolver.openFileDescriptor(active, "r")).use { reader ->
                assertArrayEquals(bytes.copyOfRange(200, 216), readAt(reader, 200, 16))
                LocalPayloadAccess.revokeAll(context)
                assertNull(LocalPayloadAccess.lookup(context, active))
                val afterRevoke = runCatching {
                    Os.pread(reader.fileDescriptor, ByteArray(16), 0, 16, 700_000L)
                }
                assertTrue("Revoke all denies subsequent uncached reads",
                    afterRevoke.isFailure || (afterRevoke.getOrNull() ?: 0) <= 0)
            }
            assertNull("Revoke all invalidates an unissued preparation",
                LocalPayloadAccess.issue(context, beforeRevoke))
            var revokedWhilePreparing = false
            assertNull("Revoke all fences an in-flight preparation", LocalPayloadAccess.prepare(
                context, engine, review, row) {
                if (!revokedWhilePreparing) {
                    revokedWhilePreparing = true
                    LocalPayloadAccess.revokeAll(context)
                }
                false
            })
            assertTrue(revokedWhilePreparing)

            val freshGrant = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            val protected = requireNotNull(LocalPayloadAccess.issue(context, freshGrant))
            val pendingPrivacy = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            requireNotNull(resolver.openFileDescriptor(protected, "r")).use {
                assertArrayEquals(bytes.copyOfRange(301, 316), readAt(it, 301, 15))
            }
            assertTrue(policy.setMode(scope, TransferLockMode.ONCE_PER_PROCESS, true))
            assertNull(LocalPayloadAccess.lookup(context, protected))
            assertNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            assertTrue(policy.setMode(scope, TransferLockMode.NEVER, true))
            assertNull(LocalPayloadAccess.lookup(context, protected))
            assertNull("Old preparation cannot inherit a new privacy epoch",
                LocalPayloadAccess.issue(context, pendingPrivacy))

            val fresh = requireNotNull(LocalPayloadAccess.prepare(context, engine, review, row) { false })
            val swapped = requireNotNull(LocalPayloadAccess.issue(context, fresh))
            val retired = File(sourceFolder, "retired.bin")
            val replacement = ByteArray(bytes.size).also { Random(782).nextBytes(it) }
            assertTrue(file.renameTo(retired))
            file.writeBytes(replacement)
            assertNotNull(runCatching { resolver.openFileDescriptor(swapped, "r")?.close() }.exceptionOrNull())
            assertArrayEquals(bytes, retired.readBytes())
            assertArrayEquals(replacement, file.readBytes())
            assertEquals("foreign canary", foreign.readText())
        } finally {
            LocalPayloadAccess.revokeAll(context)
            policy.setMode(scope, previous, true)
            engine.close()
            root.deleteRecursively()
        }
    }

    private fun externalRead(uri: Uri, testPackage: String): String {
        val action = context.packageName + ".payload_test." + java.util.UUID.randomUUID().toString().replace("-", "")
        val receipt = java.util.concurrent.atomic.AtomicReference<String>()
        val uid = java.util.concurrent.atomic.AtomicInteger(-1)
        val done = java.util.concurrent.CountDownLatch(1)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                receipt.set(intent.getStringExtra("result"))
                uid.set(intent.getIntExtra("uid", -1))
                done.countDown()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try {
            // Extras contain only a random opaque URI and a disposable result
            // action. No grant flag is supplied by the shell launch.
            val command = "am start -W -n " + testPackage +
                "/io.github.gamesbyian.chunks.external.ExternalPayloadReaderActivity" +
                " --es fixture_uri " + uri + " --es fixture_action " + action +
                " --es fixture_target " + context.packageName
            val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            val launch = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
            assertTrue("Standalone reader did not start", launch.contains("Status: ok"))
            assertTrue("No receipt from separate reader process", done.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(uid.get() >= 0)
            assertNotEquals(android.os.Process.myUid(), uid.get())
            return requireNotNull(receipt.get())
        } finally { context.unregisterReceiver(receiver) }
    }

    @Test fun chooserGrantsReadOnlyToExactOpaqueContentUri() {
        val uri = Uri.Builder().scheme("content").authority(LocalPayloadAccess.authority(context))
            .appendPath("00000000-0000-0000-0000-000000000001").build()
        for (share in listOf(false, true)) {
            val chooser = LocalPayloadAccess.chooser(uri, "application/octet-stream", share)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val target = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW, target.action)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                target.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            assertEquals(uri, target.clipData?.getItemAt(0)?.uri)
            @Suppress("DEPRECATION")
            val actual = if (share) target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else target.data
            assertEquals(uri, actual)
            assertEquals("application/octet-stream", target.type)
        }
    }
}
