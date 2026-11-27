package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RarVolumeStructureTest {
    private val first = RarVolumeHeaderReader.Result(
        status = RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
        format = RarVolumeHeaderReader.Format.RAR5,
        multiVolume = true,
        ordinal = 1,
        nextVolume = true,
        splitAfter = true
    )
    private val last = first.copy(ordinal = 2, nextVolume = false, splitBefore = true, splitAfter = false)

    @Test fun terminalAndNumberingAreSeparateObservations() {
        assertEquals(RarVolumeStructure.TERMINAL_CHAIN, RarVolumeStructure.inspect(listOf(first, last)))
        assertEquals(RarVolumeStructure.MORE_VOLUMES_REQUIRED, RarVolumeStructure.inspect(listOf(first)))
        assertEquals(RarVolumeStructure.NUMBERS_UNCONFIRMED,
            RarVolumeStructure.inspect(listOf(first, last.copy(ordinal = null))))
    }

    @Test fun wrongOrderSplitFlagsFormatAndEarlyTerminalAreRefused() {
        for (volumes in listOf(
            listOf(last, first),
            listOf(first, last.copy(ordinal = 3)),
            listOf(first, last.copy(splitBefore = false)),
            listOf(first, last.copy(format = RarVolumeHeaderReader.Format.RAR4)),
            listOf(first.copy(nextVolume = false), last)
        )) {
            assertEquals(RarVolumeStructure.INCONSISTENT, RarVolumeStructure.inspect(volumes))
        }
    }
}
