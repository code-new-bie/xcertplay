package com.shilapi.xcertplay.hud

/** The calls this feature makes on BYD's public BYDAutoAcDevice API (and one setting read for logs). */
internal interface AcFan {
    fun powerState(): Int
    fun controlMode(): Int
    fun windLevel(): Int
    /** The car's own "lower fan during Bluetooth calls" switch; logged only. Null when unreadable. */
    fun stockCallWind(): Int?
    fun setWindLevel(level: Int): Int
    fun setAutoMode(): Int
}

/**
 * Lowers the AC fan to [target] for one call and puts it back afterwards, through the public AC API.
 *
 * Only lowers: an AC that is off, or a fan already at or below [target], is left alone. Release
 * restores automatic mode or the old level, but only while the fan still shows [target] and the
 * AC is on, so a change the driver made during the call is kept.
 */
internal class AcWindReduction(
    private val fan: AcFan,
    private val target: Int,
    private val otherCall: () -> String?,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val emit: (String) -> Unit,
) : CallWindBridge {
    private data class Original(val mode: Int, val level: Int)

    private var planned: Original? = null
    private var changed: Original? = null

    init {
        require(target in 1..MAX_LEVEL) { "target fan level must be in 1..$MAX_LEVEL" }
    }

    override fun unavailableReason(): String? {
        planned = null
        otherCall()?.let { return it }
        val power = fan.powerState()
        val mode = fan.controlMode()
        val level = fan.windLevel()
        emit("ac power=$power mode=${modeName(mode)} level=$level target=$target " +
            "stockCallWind=${fan.stockCallWind() ?: "unknown"}")
        if (power != AC_POWER_ON) return "AC is off"
        if (level !in 1..MAX_LEVEL) return "AC fan level unknown ($level)"
        if (level <= target) return "AC fan already at level $level"
        planned = Original(mode, level)
        return null
    }

    override fun request() {
        val original = checkNotNull(planned) { "AC state was not read before the request" }
        otherCall()?.let { error("native call became active before entry: $it") }
        // A timed-out command may still reach the vehicle; release decides from the fan level.
        changed = original
        val result = fan.setWindLevel(target)
        emit("ac fan set level=$target result=$result success=${result == AC_COMMAND_SUCCESS}")
        check(result == AC_COMMAND_SUCCESS) { "AC fan set failed result=$result" }
        emit("ac fan readback level=${awaitLevel(target, REQUEST_READBACK_MS)} expected=$target")
    }

    override fun release() {
        val original = changed ?: return
        changed = null
        val power = fan.powerState()
        val current = fan.windLevel()
        if (power != AC_POWER_ON || current != target) {
            emit("ac restore skipped: power=$power level=$current; keeping the driver's or vehicle's change")
            return
        }
        val auto = original.mode == AC_CTRLMODE_AUTO
        val result = if (auto) fan.setAutoMode() else fan.setWindLevel(original.level)
        emit("ac restore ${if (auto) "mode=auto" else "level=${original.level}"} result=$result " +
            "success=${result == AC_COMMAND_SUCCESS}")
        check(result == AC_COMMAND_SUCCESS) { "AC fan restore failed result=$result" }
        if (auto) {
            emit("ac restore readback mode=${modeName(awaitMode(AC_CTRLMODE_AUTO))} level=${fan.windLevel()}")
        } else {
            emit("ac restore readback level=${awaitLevel(original.level, RESTORE_READBACK_MS)} " +
                "expected=${original.level}")
        }
    }

    /** The vehicle reports the new state asynchronously; poll briefly so the log shows the outcome. */
    private fun awaitLevel(expected: Int, timeoutMs: Long): Int {
        val deadline = now() + timeoutMs
        var level = fan.windLevel()
        while (level != expected && now() < deadline) {
            sleep(READBACK_POLL_MS)
            level = fan.windLevel()
        }
        return level
    }

    private fun awaitMode(expected: Int): Int {
        val deadline = now() + RESTORE_READBACK_MS
        var mode = fan.controlMode()
        while (mode != expected && now() < deadline) {
            sleep(READBACK_POLL_MS)
            mode = fan.controlMode()
        }
        return mode
    }

    private fun modeName(mode: Int): String = when (mode) {
        AC_CTRLMODE_AUTO -> "auto"
        AC_CTRLMODE_MANUAL -> "manual"
        else -> mode.toString()
    }

    companion object {
        // Values from BYD's public BYDAutoAcDevice API, unchanged in this firmware's framework.
        const val AC_COMMAND_SUCCESS = 0
        const val AC_POWER_ON = 1
        const val AC_CTRLMODE_AUTO = 0
        const val AC_CTRLMODE_MANUAL = 1
        const val AC_CTRL_SOURCE_UI_KEY = 0
        const val MAX_LEVEL = 7
        const val REQUEST_READBACK_MS = 1_500L
        const val RESTORE_READBACK_MS = 500L
        const val READBACK_POLL_MS = 100L
    }
}
