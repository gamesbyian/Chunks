package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class LocalInodeAccountingTest {
    @Test fun sameInodeCountsAllocatedBlocksOnlyOnceAcrossPaths() {
        val account = LocalInodeAccounting()
        assertTrue(account.add(device = 1, inode = 7, size = 4096, statBlocks = 8, linkCount = 3))
        assertTrue(account.add(device = 1, inode = 7, size = 4096, statBlocks = 8, linkCount = 3))
        assertEquals(4096L, account.logicalBytes)
        assertEquals(4096L, account.allocatedBytes)
        assertEquals(1, account.uniqueInodes)
        assertTrue(account.outsideHardlinksPossible)
        assertTrue(account.add(device = 1, inode = 7, size = 4096, statBlocks = 8, linkCount = 3))
        assertFalse(account.outsideHardlinksPossible)
    }

    @Test fun impossibleObservedHardlinksAreNotSilentlyCounted() {
        val account = LocalInodeAccounting()
        assertTrue(account.add(1, 10, 4096, 8, 2))
        assertTrue(account.add(1, 10, 4096, 8, 2))
        assertFalse("Three paths cannot refer to an inode with two links",
            account.add(1, 10, 4096, 8, 2))
        assertEquals(1, account.uniqueInodes)
        assertEquals(4096L, account.allocatedBytes)
        assertFalse(account.outsideHardlinksPossible)
    }

    @Test fun sameInodeNumberOnOtherDeviceIsIndependentStorage() {
        val account = LocalInodeAccounting()
        assertTrue(account.add(1, 7, 1_000, 8, 1))
        assertTrue(account.add(2, 7, 1_000, 8, 1))
        assertEquals(2, account.uniqueInodes)
        assertEquals(8192L, account.allocatedBytes)
        assertEquals(2_000L, account.logicalBytes)
    }

    @Test fun inconsistentHardlinkOrStatOverflowIsNeverAccepted() {
        val account = LocalInodeAccounting()
        assertTrue(account.add(1, 1, 10, 1, 2))
        assertFalse(account.add(1, 1, 11, 1, 2))
        assertFalse(account.add(1, 1, 10, 2, 2))
        assertFalse(account.add(1, 2, 1, Long.MAX_VALUE, 1))
        assertFalse(account.add(1, 3, -1, 0, 1))
        assertEquals(1, account.uniqueInodes)
        assertEquals(512L, account.allocatedBytes)
    }

    @Test fun aggregateOverflowReportsUnknownRatherThanWrappedNegativeBytes() {
        val account = LocalInodeAccounting()
        assertTrue(account.add(1, 1, Long.MAX_VALUE, 1, 1))
        assertFalse(account.add(1, 2, 1, 1, 1))
        assertNull(account.logicalBytes)
    }
}
