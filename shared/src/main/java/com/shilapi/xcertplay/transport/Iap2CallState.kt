package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Readable iAP2 CallStateUpdate (0x4155) for the session log. Only system calls (cellular, and
 * CallKit apps) send it; WeChat calls in mainland China do not, which tells the two apart.
 * The number is masked to its last four digits.
 */
internal object Iap2CallState {
    const val MESSAGE_ID = 0x4155

    private val STATUS = mapOf(
        0 to "disconnected", 1 to "sending", 2 to "ringing", 3 to "connecting",
        4 to "active", 5 to "held", 6 to "disconnecting",
    )
    private val DIRECTION = mapOf(0 to "unknown", 1 to "incoming", 2 to "outgoing")
    private val SERVICE = mapOf(0 to "unknown", 1 to "telephony", 2 to "FaceTime audio", 3 to "FaceTime video")

    fun describe(frame: Iap2Frame): String = runCatching {
        val body = Iap2Messages.reader(frame)
        val number = body.optionalString(0)
        val status = body.optionalU8(2)
        val direction = body.optionalU8(3)
        val service = body.optionalU8(7)
        val reason = body.optionalU8(10)
        buildString {
            append("Call state: ")
            append(status?.let { STATUS[it] ?: "status $it" } ?: "status ?")
            direction?.let { append(" ").append(DIRECTION[it] ?: "direction $it") }
            service?.let { append(" service=").append(SERVICE[it] ?: it.toString()) }
            append(" number=").append(mask(number))
            reason?.let { append(" disconnectReason=").append(it) }
        }
    }.getOrElse { "Call state: undecodable (${it.javaClass.simpleName})" }

    fun mask(number: String?): String = when {
        number.isNullOrBlank() -> "none"
        number.length <= 4 -> number
        else -> "*".repeat(number.length - 4) + number.takeLast(4)
    }
}
