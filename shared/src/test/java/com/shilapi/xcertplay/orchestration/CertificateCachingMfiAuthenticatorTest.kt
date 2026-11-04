package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.mfi.MfiInvalidDataException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificateCachingMfiAuthenticatorTest {
    private class CountingAuthenticator : MfiAuthenticator {
        val certificate = ByteArray(908) { it.toByte() }
        var reads = 0
        var signs = 0
        override val certificateType = MfiCertificateType.BAA
        override fun protocolMajor() = 3
        override fun readCertificate(maximumOutputLength: Int): ByteArray {
            reads++
            if (certificate.size > maximumOutputLength) throw MfiInvalidDataException("too long")
            return certificate.copyOf()
        }
        override fun signChallenge(challenge: ByteArray): ByteArray {
            signs++
            return challenge.reversedArray()
        }
    }

    @Test
    fun readsTheCertificateOnceAndHandsOutCopies() {
        val chip = CountingAuthenticator()
        val session = MfiSession(chip, null)

        val first = session.client.readCertificate()
        first[0] = 99
        val second = session.client.readCertificate()

        assertEquals(1, chip.reads)
        assertArrayEquals(chip.certificate, second)
        assertNotSame(first, second)
    }

    @Test
    fun aSmallerLimitStillFailsAndSigningIsNotCached() {
        val chip = CountingAuthenticator()
        val client = MfiSession(chip, null).client
        client.readCertificate()

        assertThrows(MfiInvalidDataException::class.java) { client.readCertificate(100) }
        client.signChallenge(byteArrayOf(1, 2))
        client.signChallenge(byteArrayOf(1, 2))

        assertEquals(2, chip.reads)
        assertEquals(2, chip.signs)
        assertEquals(MfiCertificateType.BAA, client.certificateType)
        assertEquals(3, client.protocolMajor())
    }
}
