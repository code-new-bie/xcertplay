package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaSentenceSelectorTest {
    private val gga = "\$GPGGA,123519.00,a*00"
    private val rmc = "\$GPRMC,123519.00,b*00"
    private val gsv = "\$GPGSV,2,1,08,c*00\r\n\$GPGSV,2,2,08,d*00"
    private val pascd = "\$PASCD,1.000,C,D,0,1,0.00,1.000*00"

    // What the iPhone asks for in the car: GGA, RMC, PASCD, a message interval and per-sentence intervals.
    private val carRequest = setOf(0, 1, 2, 4, 0x8001, 0x8002, 0x8004)

    @Test
    fun onlyTheRequestedSentenceTypesGo() {
        val selector = NmeaSentenceSelector().apply { request(carRequest) }
        assertEquals("$gga\r\n$rmc\r\n$pascd\r\n", selector.select("$gga\r\n$rmc\r\n$gsv\r\n$pascd\r\n"))
    }

    @Test
    fun aFixAlreadySentIsNotSentAgainButWheelSpeedIs() {
        val selector = NmeaSentenceSelector().apply { request(carRequest) }
        selector.select("$gga\r\n$rmc\r\n")
        assertNull(selector.select("$gga\r\n$rmc\r\n"))
        assertEquals("$pascd\r\n", selector.select("$gga\r\n$rmc\r\n$pascd\r\n"))
        assertEquals("$pascd\r\n", selector.select("$gga\r\n$pascd\r\n"))
        val newer = "\$GPGGA,123520.00,a*00"
        assertEquals("$newer\r\n", selector.select("$newer\r\n$rmc\r\n"))
    }

    @Test
    fun aNewRequestSendsTheCurrentFixAgain() {
        val selector = NmeaSentenceSelector().apply { request(carRequest) }
        selector.select("$gga\r\n")
        selector.request(carRequest)
        assertEquals("$gga\r\n", selector.select("$gga\r\n"))
    }

    @Test
    fun aRequestedSatelliteGroupGoesWholeAndOnlyWhenItChanges() {
        val selector = NmeaSentenceSelector().apply { request(setOf(1, 3)) }
        assertEquals("$gga\r\n$gsv\r\n", selector.select("$gga\r\n$gsv\r\n"))
        assertNull(selector.select("$gga\r\n$gsv\r\n"))
    }

    @Test
    fun aRequestNamingNoSentenceTypeKeepsThemAll() {
        val selector = NmeaSentenceSelector().apply { request(setOf(0)) }
        assertEquals("$gga\r\n$gsv\r\n", selector.select("$gga\r\n$gsv\r\n"))
    }

    @Test
    fun eachSentenceTravelsInItsOwnParameter() {
        val frame = Iap2LocationMessages.locationInformation("$gga\r\n$rmc\r\n")
        val fields = Iap2BodyReader.of(frame).list()
        assertEquals(listOf(0, 0), fields.map { it.id })
        assertEquals("$gga\r\n\u0000", fields[0].payload.decodeToString())
        assertEquals("$rmc\r\n\u0000", fields[1].payload.decodeToString())
    }

    @Test
    fun theRequestedIntervalsAreRead() {
        val request = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
            u32(0, 1_000)
            void(1)
            void(2)
            void(4)
            u32(0x8001, 250)
            u32(0x8004, 100)
        }
        val intervals = Iap2LocationMessages.requestedIntervals(request)
        assertEquals(mapOf(0 to 1_000L, 0x8001 to 250L, 0x8004 to 100L), intervals)
        assertEquals("[0=1000,0x8001=250,0x8004=100]", Iap2LocationMessages.describeIntervals(intervals))
    }
}
