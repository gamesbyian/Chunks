package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import android.webkit.MimeTypeMap
import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import java.io.File
import java.io.FileDescriptor
import java.util.Locale
import java.util.UUID

/**
 * Process-memory-only, single-file read authority. Never starts a native
 * engine, makes a cache copy, exports a filesystem path or gives a recipient
 * arbitrary path authority. Initial scope deliberately excludes locked
 * profiles (even temporarily unlocked ones).
 */
internal object LocalPayloadAccess {
    const val MAX_BYTES = 64L * 1024 * 1024 * 1024
    const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_GRANTS = 8
    private const val MAX_READERS = 4

    internal class Prepared(
        val engine: LocalTorrentEngine,
        val review: LocalFileInspection.Review,
        val file: LocalTransferFile,
        val root: File,
        val source: File,
        val identity: LocalPinnedReadIdentity,
        val revision: Int,
        internal val revocationEpoch: Any
    ) {
        // Accessed only under LocalPayloadAccess's monitor. A preparation is
        // authority for one handoff attempt, not a reusable grant factory.
        internal var issued = false
        val name = file.path.substringAfterLast('/')
        val mime: String get() = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
    }

    internal class Grant(val source: Prepared, val expires: Long) {
        var readers = 0
    }

    private val grants = LinkedHashMap<String, Grant>()
    private var revocationEpoch = Any()

    @Synchronized
    private fun currentRevocationEpoch(): Any = revocationEpoch
    // Revoked/expired grants may still have open proxy descriptors. Count
    // them until onRelease; removing a token cannot free a reader slot.
    private var activeReaders = 0

    private fun privacyAllows(context: Context, revision: Int): Boolean = try {
        revision == TransferPrivacyPolicyStore.revision &&
            TransferPrivacyPolicyStore(context).mode(TransferCollectionStore.LOCAL_ENGINE_SCOPE) ==
                TransferLockMode.NEVER &&
            revision == TransferPrivacyPolicyStore.revision
    } catch (_: Exception) { false }

    fun available(context: Context) = privacyAllows(context, TransferPrivacyPolicyStore.revision)

    private fun reviewed(engine: LocalTorrentEngine, review: LocalFileInspection.Review,
                         file: LocalTransferFile): Boolean {
        val fresh = LocalFileInspection.review(engine, review.infoHash) ?: return false
        return fresh.nativeToken == review.nativeToken && fresh.savePath == review.savePath &&
            fresh.files.singleOrNull { it.index == file.index } == file
    }

    private fun onDisk(root: File, file: LocalTransferFile) =
        LocalFileDiskCheck.check(root, listOf(file)).results.singleOrNull()?.status ==
            LocalFileDiskCheck.Status.PRESENT

    /** Off main. No URI is issued by an abandoned/cancelled preparation. */
    fun prepare(context: Context, engine: LocalTorrentEngine,
                review: LocalFileInspection.Review, file: LocalTransferFile,
                cancelled: () -> Boolean): Prepared? {
        val epoch = currentRevocationEpoch()
        val revision = TransferPrivacyPolicyStore.revision
        fun authorized() = !cancelled() && epoch === currentRevocationEpoch() &&
            privacyAllows(context, revision) && reviewed(engine, review, file)
        if (file !in review.files || file.sizeBytes !in 0..MAX_BYTES ||
            !authorized()) return null
        return try {
            if (!engine.flushCompletedForLibraryRead(review.infoHash, 30_000L) || !authorized()) return null
            val selected = File(review.savePath)
            if (!onDisk(selected, file)) return null
            val root = selected.canonicalFile
            val source = File(root, file.path)
            val fd = LocalSingleLibraryMediaStoreCopy.openPinnedRegularSource(source)
            try {
                val stat = Os.fstat(fd)
                val identity = LocalPinnedReadIdentity.from(stat)
                if (stat.st_size != file.sizeBytes || !identity.matches(Os.lstat(source.absolutePath)) ||
                    !onDisk(selected, file) || selected.canonicalFile != root || !authorized()) null
                else Prepared(engine, review.copy(files = listOf(file)), file, root, source, identity, revision, epoch)
            } finally { Os.close(fd) }
        } catch (_: Exception) { null }
    }

    fun authority(context: Context) = context.packageName + ".local-payload"

    @Synchronized
    fun issue(context: Context, prepared: Prepared): Uri? {
        if (prepared.issued || prepared.revocationEpoch !== revocationEpoch) return null
        prepared.issued = true
        if (!privacyAllows(context, prepared.revision)) return null
        val now = SystemClock.elapsedRealtime()
        grants.entries.removeAll { now >= it.value.expires }
        if (grants.size >= MAX_GRANTS) return null
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(prepared, now + TTL_MS)
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).build()
    }

    private fun token(context: Context, uri: Uri): String? =
        uri.pathSegments.singleOrNull()?.takeIf {
            uri.scheme == "content" && uri.authority == authority(context) &&
                uri.query == null && uri.fragment == null &&
                it.matches(Regex("[0-9a-f-]{36}"))
        }

    @Synchronized
    fun lookup(context: Context, uri: Uri): Grant? {
        val id = token(context, uri) ?: return null
        val grant = grants[id] ?: return null
        if (SystemClock.elapsedRealtime() >= grant.expires ||
            !privacyAllows(context, grant.source.revision)) {
            grants.remove(id)
            return null
        }
        return grant
    }

    fun revoke(context: Context, uri: Uri) {
        val id = token(context, uri) ?: return
        synchronized(this) { grants.remove(id) }
        // Removing the process-memory token is the authoritative denial;
        // platform grant cleanup is best effort and cannot resurrect access.
        try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: RuntimeException) { }
    }

    fun revokeAll(context: Context) {
        val ids = synchronized(this) {
            // Atomically retire both issued tokens and preparations that
            // started before this revocation, including a pending cache flush.
            revocationEpoch = Any()
            grants.keys.toList().also { grants.clear() }
        }
        ids.forEach { id ->
            val uri = Uri.Builder().scheme("content").authority(authority(context)).appendPath(id).build()
            revoke(context, uri)
        }
    }

    @Synchronized
    fun acquire(context: Context, uri: Uri): Grant? {
        val grant = lookup(context, uri) ?: return null
        if (activeReaders >= MAX_READERS) return null
        activeReaders++
        grant.readers++
        return grant
    }

    @Synchronized
    fun release(grant: Grant) {
        if (grant.readers > 0) {
            grant.readers--
            activeReaders--
        }
    }

    /** Exact grant, native generation, current file row and pinned identity.
     * A failed observation permanently removes this grant; no implicit rebind.
     */
    fun current(context: Context, uri: Uri, grant: Grant, fd: FileDescriptor? = null): Boolean {
        val source = grant.source
        val valid = try {
            lookup(context, uri) === grant &&
                reviewed(source.engine, source.review, source.file) &&
                File(source.review.savePath).canonicalFile == source.root &&
                onDisk(File(source.review.savePath), source.file) &&
                source.identity.matches(Os.lstat(source.source.absolutePath)) &&
                (fd == null || source.identity.matches(Os.fstat(fd))) &&
                lookup(context, uri) === grant
        } catch (_: Exception) { false }
        if (!valid) revoke(context, uri)
        return valid
    }

    fun open(context: Context, uri: Uri, grant: Grant): FileDescriptor? {
        if (!current(context, uri, grant)) return null
        val fd = try { LocalSingleLibraryMediaStoreCopy.openPinnedRegularSource(grant.source.source) }
            catch (_: Exception) { revoke(context, uri); return null }
        if (current(context, uri, grant, fd)) return fd
        Os.close(fd)
        return null
    }

    /** Narrow temporary Android read grant, never a file:// URI or write grant. */
    fun chooser(uri: Uri, mime: String, share: Boolean): Intent {
        val target = if (share) Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        target.clipData = ClipData.newRawUri("Completed file", uri)
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(target, if (share) "Share file" else "Open with")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
