package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun aFirstCheckIsDueAtOnce() {
        assertTrue(UpdateSchedule.isDue(now, lastSuccessMs = 0, lastAttemptMs = 0))
    }

    @Test
    fun aSuccessfulCheckWaitsADay() {
        assertFalse(UpdateSchedule.isDue(now, lastSuccessMs = now - 23 * hour, lastAttemptMs = now - 23 * hour))
        assertEquals(hour, UpdateSchedule.delayUntilDue(now, now - 23 * hour, now - 23 * hour))
        assertTrue(UpdateSchedule.isDue(now, lastSuccessMs = now - 24 * hour, lastAttemptMs = now - 24 * hour))
    }

    @Test
    fun aFailedCheckIsRetriedAfterAnHour() {
        // Never succeeded, failed ten minutes ago.
        assertEquals(50 * 60 * 1000L, UpdateSchedule.delayUntilDue(now, 0, now - 10 * 60 * 1000L))
        assertTrue(UpdateSchedule.isDue(now, 0, now - hour))
        // An old success does not shorten the wait after a fresh failure.
        assertEquals(50 * 60 * 1000L, UpdateSchedule.delayUntilDue(now, now - 30 * hour, now - 10 * 60 * 1000L))
    }

    @Test
    fun aRecentSuccessStillHoldsAfterAFailure() {
        assertEquals(23 * hour, UpdateSchedule.delayUntilDue(now, now - hour, now - 10 * 60 * 1000L))
    }

    @Test
    fun aClockThatWentBackwardsMakesACheckDue() {
        assertTrue(UpdateSchedule.isDue(now, lastSuccessMs = now + 5 * hour, lastAttemptMs = now + 5 * hour))
    }
}
