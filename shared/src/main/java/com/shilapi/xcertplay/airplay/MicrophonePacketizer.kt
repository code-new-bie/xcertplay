package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Inet4Address

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
) {
    /**
     * Also the RTP timestamp step. For Opus it counts at [sampleRate], the rate of the selected
     * Opus bit, not at 48 kHz: the iPhone's own Opus streams do (16000 Hz measured in the car), and
     * upstream 42ad6da made the same change.
     */
    val samplesPerPacket: Int
        get() = maxOf(1, sampleRate * frameMillis / 1000)

    val frameBytes: Int
        get() = samplesPerPacket * channels * 2

    companion object {
        /**
         * Milliseconds of PCM per packet when the iPhone names no framesPerPacket: the longest of 20,
         * 10 and 5 ms whose sealed packet still fits one 1,500-byte link MTU with its IPv6 and UDP
         * headers. 20 ms of 48 kHz mono seals to 1,956 bytes and leaves as IP fragments; DiPlay
         * found wired 48 kHz recordings (Voice Memos, WeChat voice messages) silent that way, while
         * 24 kHz Siri packets (996 bytes) worked. 48 kHz mono therefore sends 10 ms, the 480 samples
         * a 24 kHz Siri packet already carries. After DiPlay 8d5fd4b2.
         */
        fun pcmFrameMillis(sampleRate: Int, channels: Int): Int = PCM_FRAME_MILLIS.firstOrNull { millis ->
            MicrophonePacketizer.SEAL_OVERHEAD + sampleRate * millis / 1000 * channels * 2 <= MAX_UDP_PAYLOAD
        } ?: PCM_FRAME_MILLIS.last()

        private val PCM_FRAME_MILLIS = listOf(20, 10, 5)

        /** A 1,500-byte link MTU less the 40-byte IPv6 and 8-byte UDP headers. */
        private const val MAX_UDP_PAYLOAD = 1500 - 40 - 8
    }
}

internal fun microphoneBindAddress(peer: InetAddress): InetAddress =
    InetAddress.getByName(if (peer is Inet4Address) "0.0.0.0" else "::")

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(
    var sequence: Int = 0,
    var timestamp: Int = 0,
    var nonce: Long = 0,
)

/**
 * Seals one microphone frame in the CarPlay RTP layout.
 *
 * The 12-byte RTP header is clear; its timestamp and SSRC are authenticated as AAD. The packet
 * tail repeats the eight-byte little-endian nonce counter after the 16-byte Poly1305 tag.
 */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8

    /** Bytes a sealed packet adds to its samples: the RTP header, the Poly1305 tag and the nonce. */
    const val SEAL_OVERHEAD = RTP_HEADER_LEN + TAG_LEN + NONCE_LEN

    fun sealPacket(
        key: ByteArray,
        payloadType: Int,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7f).toByte()
        putU16Be(header, 2, counters.sequence)
        putU32Be(header, 4, counters.timestamp)

        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counters.nonce)
        val sealed = AirPlayCrypto.chachaSeal(
            key = key,
            nonce = nonce,
            plaintext = body,
            aad = header.copyOfRange(4, RTP_HEADER_LEN),
        )

        val packet = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(packet, 0)
        sealed.copyInto(packet, RTP_HEADER_LEN)
        putU64Le(packet, RTP_HEADER_LEN + sealed.size, counters.nonce)

        counters.sequence = (counters.sequence + 1) and 0xffff
        counters.timestamp += samples
        counters.nonce++
        return packet
    }

    /** Captured Android PCM is little-endian; CarPlay's wired microphone payload is big-endian. */
    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
        val output = pcmLittleEndian.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }

    private fun putU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 0 until 8) {
            target[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}
