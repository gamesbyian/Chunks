package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class DeviceSpaceSnapshotTest {
    @Test fun androidAlertIsSeparateFromInventedTorrentQuota() {
        val low = DeviceSpaceSnapshot(1000, 100, true)
        assertEquals(DeviceSpaceStatus.LOW_WARNING_ACTIVE, low.status)
        assertEquals(0.9f, low.occupiedFraction!!, 0.001f)
        assertEquals(DeviceSpaceStatus.NO_WARNING_DETECTED,
            DeviceSpaceSnapshot(1000, 101, false).status)
    }

    @Test fun unknownAlertOrMeasurementsRemainHonest() {
        assertEquals(DeviceSpaceStatus.UNKNOWN, DeviceSpaceSnapshot(null, null, null).status)
        assertEquals(DeviceSpaceStatus.UNKNOWN, DeviceSpaceSnapshot(100, 30, null).status)
        assertNull(DeviceSpaceSnapshot(null, 30, true).occupiedFraction)
        assertEquals(0f, DeviceSpaceSnapshot(100, 200, false).occupiedFraction!!, 0f)
    }

    @Test fun impossibleCapacityInputsAreRefused() {
        for (bad in listOf(
            { DeviceSpaceSnapshot(0, 0, null) },
            { DeviceSpaceSnapshot(-1, 0, null) },
            { DeviceSpaceSnapshot(100, -1, null) }
        )) assertThrows(IllegalArgumentException::class.java) { bad() }
    }
}
