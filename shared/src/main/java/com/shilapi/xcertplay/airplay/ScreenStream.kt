package com.shilapi.xcertplay.airplay

import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        /** One Annex B picture in a pooled buffer; the listener must close it once done with it. */
        fun onFrame(accessUnit: VideoAccessUnit) { accessUnit.close() }
        fun onClosed(cause: Throwable?) {}
    }

    private var nalLengthSize = 4
    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    // Pictures go through recycled buffers: a new array per frame, at up to 60 frames a second,
    // kept the garbage collector busy (upstream 0d6c78c).
    private val buffers = VideoBufferPool()
    private val decryptor = AirPlayCrypto.ChaChaOpener(key)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        // Receiving and decrypting feed the decoder directly; a late frame is a visible stutter.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        var failure: Throwable? = null
        try {
            val input = sock.getInputStream()
            val header = ByteArray(HEADER_LEN)
            while (!closed.get()) {
                if (!readFully(input, header, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                val body = buffers.acquire(bodySize)
                try {
                    if (!readFully(input, body.bytes, bodySize)) break
                    onMessage(header, body)
                } finally {
                    body.close()
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: VideoBufferPool.Lease) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val plain = buffers.acquire(body.length)
                val accessUnit = try {
                    val length = if (body.length >= ScreenCodec.TAG_SIZE) {
                        decryptor.open(frameCounter.get(), body.bytes, body.length, header, plain.bytes)
                            .also { frameCounter.incrementAndGet() }
                    } else {
                        body.bytes.copyInto(plain.bytes, endIndex = body.length)
                        body.length
                    }
                    if (firstFrameLogged.compareAndSet(false, true)) {
                        Log.i(
                            TAG,
                            "video first decrypted frame sealed=${body.length} plain=$length " +
                            "head=${plain.bytes.hexPrefix(minOf(16, length))}",
                        )
                    }
                    ScreenCodec.lengthPrefixedToAnnexB(plain, length, nalLengthSize, buffers)
                } catch (error: Throwable) {
                    plain.close()
                    throw error
                }
                listener.onFrame(accessUnit)
            }
            OP_VIDEO_CONFIG -> {
                val config = body.bytes.copyOf(body.length)
                val (codec, codecData) = ScreenCodec.detectConfig(config)
                Log.i(TAG, "video codec config codec=$codec body=${body.length} data=${codecData.size}")
                val lengthOffset = if (codec == VideoCodec.H265) 21 else 4
                require(codecData.size > lengthOffset) { "Truncated video configuration" }
                nalLengthSize = (codecData[lengthOffset].toInt() and 3) + 1
                require(nalLengthSize != 3) { "Reserved NAL length size" }
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, output: ByteArray, length: Int): Boolean {
        if (length < 0 || length > output.size) return false
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /** The wire format is length-prefixed, even when its first length happens to be one. */
    fun lengthPrefixedToAnnexB(payload: ByteArray, lengthSize: Int = 4): ByteArray {
        if (lengthSize == 4) return if (MediaCodecSupport.toAnnexBInPlace(payload)) payload else ByteArray(0)
        val converted = MediaCodecSupport.toAnnexB(payload, lengthSize, allowAnnexB = false)
        if (converted.size == payload.size) {
            converted.copyInto(payload)
            return payload
        }
        return converted
    }

    /**
     * The same for a pooled [payload] of [length] bytes: four-byte lengths are rewritten in place;
     * other sizes are converted into a new pooled buffer. [payload] passes to the result, which is
     * empty for a damaged picture.
     */
    internal fun lengthPrefixedToAnnexB(
        payload: VideoBufferPool.Lease,
        length: Int,
        lengthSize: Int,
        pool: VideoBufferPool,
    ): VideoAccessUnit {
        if (lengthSize == 4) {
            return VideoAccessUnit(payload, if (MediaCodecSupport.toAnnexBInPlace(payload.bytes, length)) length else 0)
        }
        val converted = MediaCodecSupport.toAnnexB(payload.bytes.copyOf(length), lengthSize, allowAnnexB = false)
        payload.close()
        val output = pool.acquire(converted.size)
        converted.copyInto(output.bytes)
        return VideoAccessUnit(output, converted.size)
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
