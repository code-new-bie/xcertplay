package com.shilapi.xcertplay

import com.shilapi.xcertplay.ClusterVirtualDisplay.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterVirtualDisplayTest {
    @Test
    fun theLargestDisplayNamedFissionIsTheCluster() {
        val cluster = Target(2, "fission_bg_xdjaVirtualSurface", 1920, 720)
        val candidates = listOf(Target(1, "HDMI", 1920, 1080), Target(3, "Fission_small", 640, 240), cluster)
        assertEquals(cluster, ClusterVirtualDisplay.choose(candidates))
        assertNull(ClusterVirtualDisplay.choose(listOf(Target(1, "HDMI", 1920, 1080))))
        assertNull("A display without a size is no use", ClusterVirtualDisplay.choose(listOf(Target(4, "fission_x", 0, 0))))
    }

    @Test
    fun theStreamIsTheDisplayAtTheChosenScaleKeptEven() {
        val cluster = Target(2, "fission_bg", 1920, 720)
        assertEquals(1920 to 720, ClusterVirtualDisplay.streamSize(cluster, 100))
        assertEquals(1592 to 596, ClusterVirtualDisplay.streamSize(cluster, 83))
        assertEquals(1286 to 482, ClusterVirtualDisplay.streamSize(cluster, 67))
        assertEquals(292, ClusterVirtualDisplay.physicalWidthMm(cluster))
    }
}
