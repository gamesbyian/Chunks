package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.Permission
import org.junit.Assert.*
import org.junit.Test

/** JDK17 permission hook interleaves with the unmodified pathname attribute lookup. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class CreateSeedCopyAcquisitionRaceTest {
    @Test fun replacedFileBeforeIdentityObservationSurvivesCancellation() = exercise(false)
    @Test fun replacedDirectoryBeforeIdentityObservationSurvivesCancellation() = exercise(true)

    private fun exercise(directory: Boolean) {
        DesktopLibtorrent.ensureLoaded()
        val root = Files.createTempDirectory("chunks-create-acquisition-").toFile()
        val name = if (directory) "New folder" else "one.bin"
        val bytes = ByteArray(32_768) { 7 }
        val request = TorrentMetainfoBuilder.Request(name, listOf(
            TorrentMetainfoBuilder.Entry(listOf("one.bin"), bytes.size.toLong()) {
                ByteArrayInputStream(bytes)
            }))
        val expected = TorrentMetainfoBuilder.build(request)
        val foreign = File(root, name)
        val unrelated = byteArrayOf(42, 17, 99, 13)
        val ownerThread = Thread.currentThread()
        var injected = false
        var checking = false
        val previous = System.getSecurityManager()
        val hook = object : SecurityManager() {
            override fun checkPermission(permission: Permission?) {}
            override fun checkRead(file: String) {
                if (Thread.currentThread() != ownerThread || checking || injected ||
                    file != foreign.absolutePath) return
                checking = true
                try {
                    if (Files.exists(Path.of(file), LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(foreign.toPath(), File(root, "original-held").toPath())
                        if (directory) check(foreign.mkdir()) else foreign.writeBytes(unrelated)
                        injected = true
                    }
                } finally { checking = false }
            }
        }
        try {
            val outcome = try {
                System.setSecurityManager(hook)
                CreateSeedCopy.copyVerifyAndSeed(request, expected, root, LocalTorrentEngine(),
                    isCancelled = { injected })
            } finally { System.setSecurityManager(previous) }
            assertTrue("Must replace the entry before its first identity observation", injected)
            assertTrue("Cancellation must refuse seeding: $outcome", outcome is CreateSeedCopy.Outcome.Refused)
            if (directory) assertTrue("The foreign directory must remain", foreign.isDirectory)
            else assertArrayEquals("The foreign bytes must remain", unrelated, foreign.readBytes())
            assertTrue((outcome as CreateSeedCopy.Outcome.Refused).reason.contains("manual review"))
        } finally {
            System.setSecurityManager(previous)
            root.deleteRecursively() // Entire root is this test's disposable fixture.
        }
    }
}
