package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteFolderReadback
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-facing reconciliation must never attribute an ambiguous request as a success. */
class RemoteFolderReadbackMessageTest {
    @Test fun uncertainFolderAtFilesystemRootCanStillBeRechecked() {
        org.junit.Assert.assertEquals("/", remoteFolderReadbackParent("/New"))
        org.junit.Assert.assertEquals("/srv/TV", remoteFolderReadbackParent("/srv/TV/New"))
    }

    @Test fun verifiedExistenceDoesNotClaimThePreviousWriteCreatedIt() {
        val text = remoteFolderReadbackMessage(RemoteFolderReadback.VERIFIED_DIRECTORY)
        assertTrue(text.contains("present and verified"))
        assertTrue(!text.contains("created"))
    }

    @Test fun absentInconsistentAndUnverifiableNeverInviteARepeatWrite() {
        listOf(
            RemoteFolderReadback.NOT_PRESENT,
            RemoteFolderReadback.INCONSISTENT,
            RemoteFolderReadback.CANNOT_VERIFY
        ).forEach { outcome ->
            val text = remoteFolderReadbackMessage(outcome)
            assertTrue(text, text.contains("not retry") || text.contains("No creation will be repeated"))
        }
    }
}
