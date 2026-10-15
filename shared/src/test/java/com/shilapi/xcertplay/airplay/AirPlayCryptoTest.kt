package com.shilapi.xcertplay.airplay

import java.security.Provider
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class AirPlayCryptoTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val nonce = AirPlayCrypto.nonce64(42)
    private val aad = byteArrayOf(0x80.toByte(), 0x60, 0x12, 0x34, 0, 0, 0, 1)
    private val plain = ByteArray(1500) { it.toByte() }

    @Test
    fun openReturnsWhatSealProtected() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, AirPlayCrypto.chachaSeal(key, nonce, plain)))
    }

    @Test
    fun aTamperedPacketIsRejected() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        sealed[10] = (sealed[10].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { AirPlayCrypto.chachaOpen(key, nonce, sealed, aad) }
        val goodPacket = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        assertThrows(Exception::class.java) { AirPlayCrypto.chachaOpen(key, nonce, goodPacket, byteArrayOf(1)) }
    }

    @Test
    fun androidsPlatformCipherOpensWhatBouncyCastleSeals() {
        // Android's provider is Conscrypt; the same library runs here on the JVM when it is on the
        // test classpath (through Robolectric).
        val conscrypt = runCatching {
            Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null) as Provider
        }.getOrNull()
        assumeTrue("Conscrypt is not on the test classpath", conscrypt != null)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding", conscrypt)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        assertArrayEquals(plain, cipher.doFinal(sealed))
        // The same instance opens the next packet once set up again, as each thread's cipher is.
        val next = AirPlayCrypto.nonce64(43)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(next))
        assertArrayEquals(plain, cipher.doFinal(AirPlayCrypto.chachaSeal(key, next, plain)))
    }
}
