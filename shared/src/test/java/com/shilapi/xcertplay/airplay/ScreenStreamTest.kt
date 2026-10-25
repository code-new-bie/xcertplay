package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamTest {
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }

    /** Sealed frames come out as Annex B pictures in pooled buffers, one nonce per frame. */
    @Test
    fun sealedFramesArriveDecryptedAsAnnexB() {
        val pictures = LinkedBlockingQueue<ByteArray>()
        var failure: Throwable? = null
        val stream = ScreenStream(key)
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(accessUnit: VideoAccessUnit) {
                pictures.add(accessUnit.bytes.copyOf(accessUnit.size))
                accessUnit.close()
            }
            override fun onClosed(cause: Throwable?) {
                failure = cause
            }
        })
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val output = socket.getOutputStream()
                // An avcC box with four-byte NAL lengths.
                val avcC = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 0)
                output.write(message(OP_CONFIG, byteArrayOf(0, 0, 0, (8 + avcC.size).toByte()) + "avcC".toByteArray() + avcC))
                val small = byteArrayOf(0x65, 1, 2, 3)
                val large = ByteArray(40_000) { (it % 251).toByte() }.also { it[0] = 0x41 }
                for ((counter, nal) in listOf(small, large).withIndex()) {
                    val plain = byteArrayOf(0, 0, (nal.size ushr 8).toByte(), nal.size.toByte()) + nal
                    val header = header(OP_FRAME, plain.size + ScreenCodec.TAG_SIZE)
                    val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter.toLong()), plain, header)
                    output.write(header + sealed)
                }
                output.flush()
                assertArrayEquals(byteArrayOf(0, 0, 0, 1) + small, received(pictures.poll(2, TimeUnit.SECONDS)))
                assertArrayEquals(byteArrayOf(0, 0, 0, 1) + large, received(pictures.poll(2, TimeUnit.SECONDS)))
                assertEquals(0, pictures.size)
                assertEquals(null, failure)
            }
        } finally {
            stream.close()
        }
    }

    private fun received(picture: ByteArray?): ByteArray = checkNotNull(picture) { "No picture arrived" }

    private fun message(opcode: Int, body: ByteArray) = header(opcode, body.size) + body

    private fun header(opcode: Int, bodySize: Int) = ByteArray(128).also {
        it[0] = bodySize.toByte()
        it[1] = (bodySize ushr 8).toByte()
        it[2] = (bodySize ushr 16).toByte()
        it[3] = (bodySize ushr 24).toByte()
        it[4] = opcode.toByte()
        it[16] = 0x5a // Any header byte is authenticated data.
    }

    private companion object {
        const val OP_FRAME = 0
        const val OP_CONFIG = 1
    }
}
