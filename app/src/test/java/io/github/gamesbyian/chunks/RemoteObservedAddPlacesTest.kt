package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteObservedAddPlacesTest {
    private fun transfer(name: String, directory: String) = RemoteTransfer(
        hash = "a".repeat(40), name = name,
        sizeBytes = 1, completedBytes = 0, started = false,
        directory = directory
    )

    @Test fun multipleMultifileDirectoriesOfferCommonParentInsteadOfTheirPayloadRoots() {
        val places = RemoteObservedAddPlaces.from(listOf(
            transfer("Episode 01", "/downloads/Episode 01"),
            transfer("Episode 02", "/downloads/Episode 02"),
            transfer("song.mp3", "/downloads"),
            transfer("Another", "/home/other/Another")
        ))
        assertEquals("/downloads", places.first())
        assertEquals(listOf("/downloads", "/home/other"), places)
        assertFalse(places.contains("/downloads/Episode 01"))
    }

    @Test fun ignoresInvalidRemotePathsAndNeverMakesUpDownloads() {
        assertEquals(emptyList<String>(), RemoteObservedAddPlaces.from(emptyList()))
        assertEquals(listOf("/valid"), RemoteObservedAddPlaces.from(listOf(
            transfer("x", "relative"),
            transfer("x", "/untrusted/../escape"),
            transfer("x", "/bad,command"),
            transfer("x", "/valid")
        )))
    }

    @Test fun limitsSuggestionsWithDeterministicFrequencyAndTieOrdering() {
        val transfers = (1..12).map { transfer("file$it", "/parent$it") } +
            listOf(transfer("extra", "/parent12"))
        val paths = RemoteObservedAddPlaces.from(transfers)
        assertEquals(8, paths.size)
        assertEquals("/parent12", paths.first())
        assertEquals("/parent1", paths[1])
    }
}
