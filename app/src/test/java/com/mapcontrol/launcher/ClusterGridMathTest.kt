package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterGridMathTest {
    @Test fun linesEvery40() {
        val l = ClusterGridMath.lines(1920)
        assertEquals(49, l.size)
        assertEquals(1920, l.last())
        assertEquals(19, ClusterGridMath.lines(720).size)
    }

    @Test fun majorEvery200() {
        assertTrue(ClusterGridMath.isMajor(0))
        assertTrue(ClusterGridMath.isMajor(1000))
        assertFalse(ClusterGridMath.isMajor(40))
        assertFalse(ClusterGridMath.isMajor(1920))
    }

    @Test fun labelsEvery200() {
        assertEquals(listOf(0, 200, 400, 600), ClusterGridMath.labels(720))
        assertEquals(10, ClusterGridMath.labels(1920).size)
    }
}
