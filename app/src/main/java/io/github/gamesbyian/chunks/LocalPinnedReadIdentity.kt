package io.github.gamesbyian.chunks

import android.system.OsConstants
import android.system.StructStat

/** Point-in-time physical identity shared by bounded Local byte readers.
 * Android's exposed stat times have second granularity: this is not a
 * cryptographic byte identity or protection from every concurrent writer.
 */
internal data class LocalPinnedReadIdentity(
    val dev: Long, val inode: Long, val size: Long,
    val modified: Long, val changed: Long
) {
    fun matches(stat: StructStat): Boolean =
        OsConstants.S_ISREG(stat.st_mode) && from(stat) == this

    companion object {
        fun from(stat: StructStat) = LocalPinnedReadIdentity(
            stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_ctime)
    }
}
