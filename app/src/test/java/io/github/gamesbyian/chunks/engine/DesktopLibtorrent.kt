package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * JVM unit tests run on the build host, not Android. Load the desktop Linux
 * libtorrent4j binary from the test classpath. The Android x86_64 jar ships a
 * bionic library at the same resource path, so try each candidate in turn.
 */
object DesktopLibtorrent {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val failures = ArrayList<String>()
        val candidates = javaClass.classLoader.getResources("lib/x86_64/libtorrent4j.so").toList()
        for ((index, resource) in candidates.withIndex()) {
            val file = File.createTempFile("libtorrent4j-$index-", ".so").apply { deleteOnExit() }
            resource.openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            try {
                System.load(file.absolutePath)
                System.setProperty("libtorrent4j.jni.path", file.absolutePath)
                loaded = true
                return
            } catch (failure: UnsatisfiedLinkError) {
                failures += failure.message.orEmpty()
            }
        }
        throw IllegalStateException("No loadable desktop libtorrent4j binary: $failures")
    }
}
