package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class DeviceSpaceSnapshotTest {
    @Test fun lowSpaceUsesAndroidThresholdRatherThanInventingATorrentQuota() {
        val low = DeviceSpaceSnapshot(1000, 100, 100)
        assertEquals(DeviceSpaceStatus.AT_OR_BELOW_ANDROID_FLOOR, low.status)
        assertEquals(0.9f, low.occupiedFraction!!, 0.001f)
        assertEquals(DeviceSpaceStatus.ABOVE_ANDROID_FLOOR,
            DeviceSpaceSnapshot(1000, 101, 100).status)
    }

    @Test fun unknownThresholdOrMeasurementsStayUnknown() {
        assertEquals(DeviceSpaceStatus.UNKNOWN, DeviceSpaceSnapshot(null, null, null).status)
        assertEquals(DeviceSpaceStatus.UNKNOWN, DeviceSpaceSnapshot(100, 30, null).status)
        assertNull(DeviceSpaceSnapshot(null, 30, 10).occupiedFraction)
        assertEquals(0f, DeviceSpaceSnapshot(100, 200, 10).occupiedFraction!!, 0f)
    }

    @Test fun impossibleNegativeCapacityInputsAreRefused() {
        for (bad in listOf(
            { DeviceSpaceSnapshot(0, 0, 0) },
            { DeviceSpaceSnapshot(-1, 0, 0) },
            { DeviceSpaceSnapshot(100, -1, 0) },
            { DeviceSpaceSnapshot(100, 0, -1) }
        )) assertThrows(IllegalArgumentException::class.java, bad)
    }
}
