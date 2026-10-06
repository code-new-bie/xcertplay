package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class AcWindReductionTest {
    private class Fan(var power: Int = 1, var mode: Int = MANUAL, var level: Int = 5) : AcFan {
        val writes = mutableListOf<String>()
        var setResult = 0
        var restoreResult = 0
        var applies = true
        override fun powerState() = power
        override fun controlMode() = mode
        override fun windLevel() = level
        override fun stockCallWind(): Int? = 1
        override fun setWindLevel(level: Int): Int {
            writes += "level=$level"
            val result = if (writes.size == 1) setResult else restoreResult
            if (applies) {
                this.level = level
                mode = MANUAL
            }
            return result
        }
        override fun setAutoMode(): Int {
            writes += "auto"
            mode = AUTO
            level = 4
            return restoreResult
        }
    }

    private fun reduction(fan: Fan, target: Int = 2, otherCall: () -> String? = { null },
        output: MutableList<String> = mutableListOf()): AcWindReduction {
        var time = 0L
        return AcWindReduction(fan, target, otherCall, { time }, { time += it }, output::add)
    }

    private fun AcWindReduction.enter() {
        assertNull(unavailableReason())
        request()
    }

    @Test fun lowersAManualFanAndRestoresItsLevel() {
        val fan = Fan(level = 5)
        val output = mutableListOf<String>()
        val bridge = reduction(fan, output = output)
        bridge.enter()
        assertEquals(2, fan.level)
        bridge.release()
        assertEquals(listOf("level=2", "level=5"), fan.writes)
        assertEquals(5, fan.level)
        assertTrue(output.contains("ac fan readback level=2 expected=2"))
        assertTrue(output.contains("ac restore readback level=5 expected=5"))
    }

    @Test fun automaticModeIsRestoredInsteadOfTheOldLevel() {
        val fan = Fan(mode = AUTO, level = 6)
        val bridge = reduction(fan)
        bridge.enter()
        bridge.release()
        assertEquals(listOf("level=2", "auto"), fan.writes)
        assertEquals(AUTO, fan.mode)
    }

    @Test fun acThatIsOffIsNeverTouched() {
        val fan = Fan(power = 0)
        assertEquals("AC is off", reduction(fan).unavailableReason())
        assertTrue(fan.writes.isEmpty())
    }

    @Test fun fanAtOrBelowTheTargetIsNeverRaised() {
        assertEquals("AC fan already at level 2", reduction(Fan(level = 2)).unavailableReason())
        assertEquals("AC fan already at level 1", reduction(Fan(level = 1)).unavailableReason())
    }

    @Test fun unknownLevelWithoutVehicleDataIsLeftAlone() {
        val fan = Fan(level = 65535)
        assertEquals("AC fan level unknown (65535)", reduction(fan).unavailableReason())
        assertTrue(fan.writes.isEmpty())
    }

    @Test fun stockCallBlocksBeforeReadingOrWritingTheAc() {
        val fan = Fan()
        val output = mutableListOf<String>()
        assertEquals("Bluetooth call is active",
            reduction(fan, otherCall = { "Bluetooth call is active" }, output = output).unavailableReason())
        assertTrue(fan.writes.isEmpty())
        assertTrue(output.isEmpty())
    }

    @Test fun driverChangeDuringTheCallIsKept() {
        val fan = Fan(level = 6)
        val bridge = reduction(fan)
        bridge.enter()
        fan.level = 4
        bridge.release()
        assertEquals(listOf("level=2"), fan.writes)
        assertEquals(4, fan.level)
    }

    @Test fun acTurnedOffDuringTheCallIsNotTurnedBackOn() {
        val fan = Fan(level = 6)
        val bridge = reduction(fan)
        bridge.enter()
        fan.power = 0
        bridge.release()
        assertEquals(listOf("level=2"), fan.writes)
    }

    @Test fun rejectedSetThatNeverAppliedIsNotRestored() {
        val fan = Fan(level = 5).apply { setResult = -2147482646; applies = false }
        val bridge = reduction(fan)
        assertNull(bridge.unavailableReason())
        assertThrows(IllegalStateException::class.java) { bridge.request() }
        bridge.release()
        assertEquals(listOf("level=2"), fan.writes)
        assertEquals(5, fan.level)
    }

    @Test fun timedOutSetThatStillAppliedIsRestored() {
        val fan = Fan(level = 5).apply { setResult = -2147482646 }
        val bridge = reduction(fan)
        assertNull(bridge.unavailableReason())
        assertThrows(IllegalStateException::class.java) { bridge.request() }
        bridge.release()
        assertEquals(listOf("level=2", "level=5"), fan.writes)
    }

    @Test fun failedRestoreIsReportedAsFailure() {
        val fan = Fan(level = 5).apply { restoreResult = -2147482648 }
        val bridge = reduction(fan)
        bridge.enter()
        assertThrows(IllegalStateException::class.java) { bridge.release() }
    }

    @Test fun releaseWithoutAChangeDoesNothing() {
        val fan = Fan()
        reduction(fan).release()
        assertTrue(fan.writes.isEmpty())
    }

    @Test fun probeRestoresTheFanAfterTheTenSecondTest() {
        val fan = Fan(level = 5)
        var time = 0L
        val output = mutableListOf<String>()
        val bridge = AcWindReduction(fan, 2, { null }, { time }, { time += it }, output::add)
        val probe = CallWindProbe(bridge, { true }, { time }, { time += it }, output::add)
        assertEquals("completed", probe.run())
        assertEquals(listOf("level=2", "level=5"), fan.writes)
        assertTrue(output.contains("released"))
    }

    private companion object {
        const val AUTO = AcWindReduction.AC_CTRLMODE_AUTO
        const val MANUAL = AcWindReduction.AC_CTRLMODE_MANUAL
    }
}
