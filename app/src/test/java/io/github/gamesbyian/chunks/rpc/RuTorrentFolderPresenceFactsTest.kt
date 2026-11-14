package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider disagreement classifications do not depend on tenant names/paths. */
class RuTorrentFolderPresenceFactsTest {
    @Test fun bothProvidersCorroborateDirectoryOrAbsence() {
        assertEquals(RemoteFolderPresence.BOTH_DIRECTORIES, folderPresence(true, true, false))
        assertEquals(RemoteFolderPresence.BOTH_ABSENT, folderPresence(false, false, false))
    }
    @Test fun inventoryComparisonIgnoresFilesSymlinksAndUnrelatedFolders() {
        assertEquals(
            RemoteFolderInventoryComparison(2, 2, 1),
            folderInventoryComparison(listOf("TV", "Movies"),
                setOf("TV/", "Another/", "Movies", "readme.txt"))
        )
        assertEquals(
            RemoteFolderInventoryComparison(1, 0, 0),
            folderInventoryComparison(listOf("Folder"), setOf("Folder"))
        )
        assertEquals(
            RemoteFolderInventoryComparison(0, 0, 0),
            folderInventoryComparison(emptyList(), emptySet())
        )
    }
    @Test fun disagreementKeepsDirectionAndRealDirectoryType() {
        assertEquals(RemoteFolderPresence.BROWSER_ONLY, folderPresence(true, false, false))
        assertEquals(RemoteFolderPresence.FILE_MANAGER_ONLY, folderPresence(false, true, false))
        assertEquals(RemoteFolderPresence.FILE_MANAGER_NON_DIRECTORY, folderPresence(true, false, true))
        assertEquals(RemoteFolderPresence.FILE_MANAGER_NON_DIRECTORY, folderPresence(false, false, true))
    }
}
