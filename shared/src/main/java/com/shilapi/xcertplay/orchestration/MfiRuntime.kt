package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiDeviceScanner
import com.shilapi.xcertplay.transport.I2cTransport
import java.io.Closeable
import java.io.IOException

/** Signals the normal, retryable state where the MFi address probe found no chip. */
internal class MfiCoprocessorNotFoundException(
    message: String = "No MFi authentication coprocessor responded to the device probe",
) : IOException(message)

/** An opened MFi coprocessor client plus the handle that releases its backing transport. */
class MfiSession(
    client: MfiAuthenticator,
    private val closeable: Closeable?,
) : Closeable {
    val client: MfiAuthenticator = CertificateCachingMfiAuthenticator(client)

    override fun close() {
        closeable?.close()
    }
}

/**
 * Reads the accessory certificate once. A connection needs it for the Wi-Fi P2P name, the
 * Bluetooth and tunnelled iAP2 authentication and AirPlay's auth-setup, and over CH341 I2C each read
 * takes about 0.2 s; the chip's certificate does not change while it is open.
 */
internal class CertificateCachingMfiAuthenticator(
    private val delegate: MfiAuthenticator,
) : MfiAuthenticator by delegate {
    private val lock = Any()
    private var certificate: ByteArray? = null

    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        val cached = synchronized(lock) {
            certificate ?: delegate.readCertificate(maximumOutputLength).also { certificate = it }
        }
        // A smaller limit than the certificate fails as the coprocessor read would.
        if (cached.size > maximumOutputLength) return delegate.readCertificate(maximumOutputLength)
        return cached.copyOf()
    }
}

/** Runs the documented address probe and wraps the first responding MFi coprocessor. */
object MfiRuntime {
    fun scan(transport: I2cTransport): MfiAuthenticationClient {
        val discovery = MfiDeviceScanner(
            transport = transport,
            probeTimeoutMillis = MFI_STARTUP_PROBE_TIMEOUT_MILLIS,
        ).scan()
        val chip = discovery.chip
            ?: throw MfiCoprocessorNotFoundException(
                "No MFi authentication coprocessor responded: " +
                    discovery.failures.joinToString { "0x${it.address7Bit.toString(16)}=${it.error}" },
            )
        return MfiAuthenticationClient(transport, chip.address7Bit)
    }

    private const val MFI_STARTUP_PROBE_TIMEOUT_MILLIS = 15_000L
}
