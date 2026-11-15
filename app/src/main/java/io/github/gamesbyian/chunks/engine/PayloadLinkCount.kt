package io.github.gamesbyian.chunks.engine

import android.os.Build
import android.system.Os
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Hard-link count of a path without following a final symbolic link, or null
 * when it cannot be observed. Shipping Android (minSdk 29) uses the kernel's
 * `lstat`; JVM libtorrent tests run with Android stubs (SDK_INT == 0) and use
 * the JDK "unix" attribute view of the same syscall.
 */
internal object PayloadLinkCount {
    fun of(path: Path): Int? {
        if (Build.VERSION.SDK_INT != 0) try {
            return Os.lstat(path.toString()).st_nlink.toInt()
        } catch (_: Throwable) { /* fall through to the JDK view of lstat */ }
        return try {
            (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        } catch (_: Throwable) { null }
    }
}
