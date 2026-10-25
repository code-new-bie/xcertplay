package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBufferPoolTest {
    @Test
    fun releasedBufferIsReusedWithoutExposingBucketPadding() {
        val pool = VideoBufferPool(maxPerBucket = 1)
        val first = pool.acquire(20_001)
        val storage = first.bytes
        assertTrue(storage.size >= 20_001)
        first.close()

        val second = pool.acquire(19_000)
        assertSame(storage, second.bytes)
        assertEquals(19_000, second.length)
        second.close()
    }

    @Test
    fun decryptIntoUsesCallerOwnedStorage() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = AirPlayCrypto.nonce64(7)
        val aad = ByteArray(128) { (it * 3).toByte() }
        val plaintext = ByteArray(24_013) { (it * 7).toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val output = ByteArray(32 * 1024)

        val length = AirPlayCrypto.ChaChaOpener(key).open(7, sealed, sealed.size, aad, output)

        assertEquals(plaintext.size, length)
        assertTrue(plaintext.contentEquals(output.copyOf(length)))
    }
}
