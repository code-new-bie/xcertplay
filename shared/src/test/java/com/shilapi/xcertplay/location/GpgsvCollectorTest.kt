package com.shilapi.xcertplay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpgsvCollectorTest {
    private val first = "\$GPGSV,2,1,07,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45*75"
    private val second = "\$GPGSV,2,2,07,15,10,050,32,24,61,121,44,25,05,220,30*7D"

    @Test fun completeGroupIsKeptInOrder() {
        val collector = GpgsvCollector()
        collector.onSentence("$first\r\n", 0)
        assertNull("An unfinished group is not sent", collector.latest(0))
        collector.onSentence(second, 10)
        assertEquals("$first\r\n$second\r\n", collector.latest(100))
    }

    @Test fun otherTalkersAndSentencesAreIgnored() {
        val collector = GpgsvCollector()
        collector.onSentence("\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,1.0,545.4,M,0.0,M,,*00", 0)
        collector.onSentence("\$BDGSV,1,1,04,01,40,083,46*55", 0)
        assertNull(collector.latest(0))
    }

    @Test fun outOfOrderGroupIsDropped() {
        val collector = GpgsvCollector()
        collector.onSentence(second, 0)
        assertNull(collector.latest(0))
        collector.onSentence(first, 1)
        collector.onSentence(first, 2) // repeated 1/2 restarts the group
        collector.onSentence(second, 3)
        assertEquals("$first\r\n$second\r\n", collector.latest(3))
    }

    @Test fun staleSatellitesAreNotSent() {
        val collector = GpgsvCollector(maxAgeMs = 5_000)
        collector.onSentence("\$GPGSV,1,1,00*79", 0)
        assertEquals("\$GPGSV,1,1,00*79\r\n", collector.latest(5_000))
        assertNull(collector.latest(5_001))
    }
}
