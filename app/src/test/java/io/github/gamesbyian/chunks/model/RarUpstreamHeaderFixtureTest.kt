package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Header-only projections from independently generated libarchive v3.8.1
 * multivolume regression fixtures (libarchive/test/). Original signature,
 * header bytes, offsets, file length and zero padding are retained; compressed
 * payload bytes are replaced by zeros. These prove structural interoperability,
 * not that the modified fixtures are valid decompressible archives.
 *
 * Source and copyright: see rar-header-fixtures-LICENSE.txt.
 */
class RarUpstreamHeaderFixtureTest {
    private fun check(size: Int, format: RarVolumeHeaderReader.Format,
                      ordinal: Int, next: Boolean, vararg parts: Pair<Int, String>) {
        val projected = ByteArray(size)
        for ((offset, hex) in parts) {
            require(hex.length % 2 == 0)
            val bytes = ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
            bytes.copyInto(projected, offset)
        }
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED, format, true, ordinal, next),
            RarVolumeHeaderReader.inspect(ByteArrayMediaByteSource(projected)))
    }

    @Test fun genuineFirstContinuationAndFinalVolumeHeaderProjections() {
        // test_rar_multivolume_single_file.part1.rar.uu; upstream .uu blob b2afdf152a484e6214dd6d818272d9859ddefc5f
        check(7000, RarVolumeHeaderReader.Format.RAR4, 1, true,
            0 to "526172211a0700",
            7 to "5a6e7311010d00000000000000",
            20 to "88ae7422904300ed1a00008f4e00000390cabc05f363214014301900a48100004c696261726368697665416464696e67546573742e68746d6cc0ccf3632140f4632140",
            6980 to "5e967b0f401400bcad2fce000000000000000000")
        // test_rar_multivolume_single_file.part2.rar.uu; upstream .uu blob 615698bdefb317abfd6ba4c7d0d1bb28de9388dc
        check(7000, RarVolumeHeaderReader.Format.RAR4, 2, true,
            0 to "526172211a0700",
            7 to "197a7311000d00000000000000",
            20 to "d7837423904300ed1a00008f4e00000338944e15f363214014301900a48100004c696261726368697665416464696e67546573742e68746d6cc0ccf3632140f4632140",
            6980 to "770f7b0f4014004892aca6010000000000000000")
        // test_rar_multivolume_single_file.part3.rar.uu; upstream .uu blob 63c984782e1de7826f308b94a773d03583d5e54c
        check(6432, RarVolumeHeaderReader.Format.RAR4, 3, false,
            0 to "526172211a0700",
            7 to "197a7311000d00000000000000",
            20 to "1f6d7421904300b51800008f4e00000363a6055ef363214014301900a48100004c696261726368697665416464696e67546573742e68746d6cc0ccf3632140f4632140",
            6412 to "a7957b0e401400a7120e12020000000000000000")
        // test_read_format_rar5_multiarchive.part01.rar.uu; upstream .uu blob 9006181efaeefcfb9146bf513fa5836e5a42a2b5
        check(15360, RarVolumeHeaderReader.Format.RAR5, 1, true,
            0 to "526172211a070100",
            8 to "550041cf0c01050801070101eff6808000",
            25 to "e7bbe52a5702130b82f60004e0e908ed83026a49059580150137686f6d652f616e74656b2f74656d702f6275696c642f756e726172352f6c6962617263686976652f62696e2f6273646361745f746573740a0313a804ab5b0fdf3a00",
            15223 to "0fc6f8a60e0306e50000e5000080000102514f",
            15343 to "8b47512603050401",
            15351 to "000000000000000000")
        // test_read_format_rar5_multiarchive.part02.rar.uu; upstream .uu blob d766ccee9d87d274bd7fe451275f4684dff9f2f4
        check(15360, RarVolumeHeaderReader.Format.RAR5, 2, true,
            0 to "526172211a070100",
            8 to "baa22ad10d0105080301070101eff6808000",
            26 to "601b2d7a57021b0b81f60004e0e908ed83025d37c13280150137686f6d652f616e74656b2f74656d702f6275696c642f756e726172352f6c6962617263686976652f62696e2f6273646361745f746573740a0313a804ab5b0fdf3a00",
            15223 to "0fc6f8a60e0306e50000e5000080000102514f",
            15343 to "8b47512603050401",
            15351 to "000000000000000000")
        // test_read_format_rar5_multiarchive.part08.rar.uu; upstream .uu blob 459beb48d24112894e053aa287ddd012abe933fa
        check(9208, RarVolumeHeaderReader.Format.RAR5, 8, false,
            0 to "526172211a070100",
            8 to "c223abb00d0105080307070101f0c6808000",
            26 to "cfc4306b57020b0b82c60004e8a816ed8302f86596e580150137686f6d652f616e74656b2f74656d702f6275696c642f756e726172352f6c6962617263686976652f62696e2f6273647461725f746573740a0313aa04ab5bd1750237",
            9080 to "0fc6f8a60e0306e50000e5000080000102514f",
            9200 to "1d77565103050400")
    }
}
