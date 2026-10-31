package io.github.gamesbyian.chunks.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndependentReadbackWitnessTest {
    @Test fun exactFreshByteStreamHashesIndependently() {
        val result = IndependentReadbackWitness.read(3, { ByteArrayInputStream("abc".toByteArray()) })
        assertEquals(3L, result?.size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result?.sha256)
    }

    @Test fun absenceShortReadGrowthAndCancellationCannotWitness() {
        assertNull(IndependentReadbackWitness.read(1, { null }))
        assertNull(IndependentReadbackWitness.read(4, { ByteArrayInputStream("abc".toByteArray()) }))
        assertNull(IndependentReadbackWitness.read(2, { ByteArrayInputStream("abc".toByteArray()) }))
        assertNull(IndependentReadbackWitness.read(3, { ByteArrayInputStream("abc".toByteArray()) }, { true }))
    }

    @Test fun stalledStreamNeverBecomesVerified() {
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertNull(IndependentReadbackWitness.read(1, { stalled }))
    }
}
