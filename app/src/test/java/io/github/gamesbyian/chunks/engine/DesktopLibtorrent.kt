package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * JVM unit tests run on the build host, not Android. Load the desktop Linux
 * libtorrent4j binary from the test classpath. The Android x86_64 jar ships a
 * bionic library at the same resource path, and loading that on glibc crashes
 * the JVM (SIGSEGV) rather than throwing, so select the Linux jar explicitly.
 */
object DesktopLibtorrent {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val loader = requireNotNull(DesktopLibtorrent::class.java.classLoader)
        val candidates = loader.getResources("lib/x86_64/libtorrent4j.so").toList()
        val desktop = candidates.singleOrNull { it.toString().contains("libtorrent4j-linux") }
            ?: throw IllegalStateException("Desktop libtorrent4j-linux binary is not on the test classpath: $candidates")
        val file = File.createTempFile("libtorrent4j-linux-", ".so").apply { deleteOnExit() }
        desktop.openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        System.load(file.absolutePath)
        System.setProperty("libtorrent4j.jni.path", file.absolutePath)
        loaded = true
    }
}
