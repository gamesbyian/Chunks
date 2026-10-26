package io.github.gamesbyian.chunks

/**
 * Pure arithmetic shared by the Android lstat survey and deterministic JVM
 * hardlink tests. Linux st_blocks uses 512-byte units. No delete authority.
 */
internal class LocalInodeAccounting {
    private data class Key(val device: Long, val inode: Long)
    private data class Entry(val size: Long, val blocks: Long, val links: Long, var paths: Int)
    private val seen = HashMap<Key, Entry>()

    var logicalBytes: Long? = 0L
        private set
    var allocatedBytes: Long? = 0L
        private set
    val uniqueInodes: Int get() = seen.size
    val outsideHardlinksPossible: Boolean get() = seen.values.any { it.links > it.paths }

    /**
     * Returns false for invalid, inconsistent or unrepresentable accounting.
     * Repeated paths for one inode count allocation only once.
     */
    fun add(device: Long, inode: Long, size: Long, statBlocks: Long, linkCount: Long): Boolean {
        if (size < 0L || statBlocks < 0L || linkCount < 1L) return false
        val blocks = try { Math.multiplyExact(statBlocks, 512L) }
            catch (_: ArithmeticException) { return false }
        val key = Key(device, inode)
        val old = seen[key]
        if (old != null) {
            if (old.size != size || old.blocks != blocks || old.links != linkCount) return false
            old.paths++
            return true
        }
        val nextLogical = try { Math.addExact(logicalBytes ?: return false, size) }
            catch (_: ArithmeticException) { logicalBytes = null; return false }
        val nextAllocated = try { Math.addExact(allocatedBytes ?: return false, blocks) }
            catch (_: ArithmeticException) { allocatedBytes = null; return false }
        seen[key] = Entry(size, blocks, linkCount, 1)
        logicalBytes = nextLogical
        allocatedBytes = nextAllocated
        return true
    }
}
