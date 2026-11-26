package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAddAvailabilityTest {
    private fun can(
        session: Boolean = true, source: Boolean = true, supported: Boolean = true, folder: Boolean = true,
        busy: Boolean = false, mine: Boolean = true, alias: Boolean = true, unresolved: Boolean = false,
        external: Boolean = false, readingTorrent: Boolean = false, link: Boolean = false,
        deletion: Boolean = false, noFile: Boolean = false
    ) = ProtocolAddAvailability.canSubmit(session, source, supported, folder, busy, mine, alias, unresolved,
        external, readingTorrent, link, deletion, noFile)

    @Test fun submitOnlyWhenEveryConditionHolds() {
        assertTrue(can())
        assertFalse("no exact client session", can(session = false))
        assertFalse("no reviewed source", can(source = false))
        assertFalse("capability unsupported", can(supported = false))
        assertFalse("invalid reviewed folder", can(folder = false))
        assertFalse("an Add is in flight (no double submit)", can(busy = true))
        assertFalse("another profile's attempt", can(mine = false))
        assertFalse("invalid display name", can(alias = false))
        assertFalse("an earlier attempt is unresolved", can(unresolved = true))
        assertFalse("an external document is being read", can(external = true))
        assertFalse("a .torrent is being read", can(readingTorrent = true))
        assertFalse("a link is being fetched", can(link = true))
        assertFalse("original document deletion pending", can(deletion = true))
        assertFalse("every file skipped", can(noFile = true))
    }
}
