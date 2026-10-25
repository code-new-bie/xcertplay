package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCodecSupportTest {
    @Test
    fun inPlaceConversionHonorsLogicalLengthInsteadOfBucketCapacity() {
        val bytes = ByteArray(64)
        byteArrayOf(0, 0, 0, 3, 0x65, 1, 2).copyInto(bytes)

        assertTrue(MediaCodecSupport.toAnnexBInPlace(bytes, 7))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), bytes.copyOf(7))
    }

    @Test
    fun nalHeadersComeWithoutCopyingTheUnits() {
        val idr = byteArrayOf(0x26, 1, 9, 9, 9)
        val sei = byteArrayOf(0x4e, 1)
        val oneByte = byteArrayOf(0x02)
        val annexB = byteArrayOf(0, 0, 0, 1) + idr + byteArrayOf(0, 0, 1) + sei + byteArrayOf(0, 0, 0, 1) + oneByte
        val headers = MediaCodecSupport.annexBNalHeaders(annexB)
        assertEquals(listOf(listOf<Byte>(0x26, 1), listOf<Byte>(0x4e, 1), listOf<Byte>(0x02)), headers.map { it.toList() })
        assertEquals(MediaCodecSupport.annexBNalUnits(annexB).map { it.take(2) }, headers.map { it.toList() })
        // A pooled buffer: only the picture's own bytes count.
        val pooled = annexB.copyOf(annexB.size + 32)
        assertEquals(headers.map { it.toList() }, MediaCodecSupport.annexBNalHeaders(pooled, annexB.size).map { it.toList() })
    }

    @Test
    fun malformedAnnexBYieldsNothing() {
        val malformed = listOf(
            byteArrayOf(0x26, 1, 0, 0, 1, 0x02), // no leading start code
            byteArrayOf(0, 0, 1, 0, 0, 1, 0x26), // empty NAL unit
            byteArrayOf(0, 0, 0, 1), // start code only
        )
        for (bytes in malformed) {
            assertEquals(0, MediaCodecSupport.annexBNalHeaders(bytes).size)
            assertEquals(0, MediaCodecSupport.annexBNalUnits(bytes).size)
        }
    }

    @Test
    fun syncGateDecidesFromHeadersOnly() {
        val gate = VideoSyncGate(com.shilapi.xcertplay.airplay.VideoCodec.H265)
        val trail = byteArrayOf(0, 0, 0, 1, 0x02, 1, 7, 7)
        val idr = byteArrayOf(0, 0, 0, 1, 0x26, 1, 7, 7)
        assertEquals(false, gate.accept(trail))
        assertEquals(true, gate.accept(idr))
        assertEquals(true, gate.accept(trail))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    @Test
    fun malformedSecondNalRejectsTheWholeAccessUnit() {
        val payload = byteArrayOf(0, 0, 0, 2, 0x26, 1, 0, 0, 0, 8, 0x02, 1)
        assertEquals(0, MediaCodecSupport.toAnnexB(payload).size)
    }

    @Test
    fun trailingPartialLengthRejectsTheWholeAccessUnit() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x26, 1, 0)).size)
    }

    @Test
    fun incompleteHevcParameterSetsAreRejected() {
        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(hevcRecord(byteArrayOf(0x40, 1))).size)
    }

    @Test
    fun hevcCsdOrdersParameterSetsAndPreservesSei() {
        val vps = byteArrayOf(0x40, 1)
        val sps = byteArrayOf(0x42, 1, 2)
        val pps = byteArrayOf(0x44, 1)
        val sei = byteArrayOf(0x4e, 1, 2)
        val start = byteArrayOf(0, 0, 0, 1)
        assertArrayEquals(start + vps + start + sps + start + pps + start + sei,
            MediaCodecSupport.hevcCodecSpecificData(hevcRecord(pps, sei, sps, vps)))
    }

    @Test
    fun hugeNalLengthDoesNotOverflowBoundsCheck() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0x7f, -1, -1, -1, 0x26, 1)).size)
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = if (parameterSet.isEmpty()) (32 + index).toByte()
            else ((parameterSet[0].toInt() ushr 1) and 0x3f).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }
}
