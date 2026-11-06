package com.mapcontrol.launcher.cluster

import com.mapcontrol.launcher.cluster.ClusterMapMath.Box
import com.mapcontrol.launcher.cluster.ClusterMapMath.Frame
import com.mapcontrol.launcher.cluster.ClusterMapMath.FrameChoice
import com.mapcontrol.launcher.cluster.ClusterMapMath.MapApp
import com.mapcontrol.launcher.cluster.ClusterMapMath.WindowSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMapMathTest {

    @Test fun defaultWindowsAvoidQnx() {
        WindowSize.values().forEach { assertTrue(it.name, ClusterMapMath.isSafe(it.hole)) }
        assertEquals(800, WindowSize.ORTA.hole.width)
        assertEquals(450, WindowSize.ORTA.hole.height)
    }

    @Test fun rejectsQnxZones() {
        assertFalse("hız bloğu", ClusterMapMath.isSafe(Box(400, 150, 1300, 600)))
        assertFalse("üst simgeler", ClusterMapMath.isSafe(Box(510, 100, 1300, 600)))
        assertFalse("alt şerit", ClusterMapMath.isSafe(Box(510, 150, 1300, 650)))
        assertFalse("sağ alt yazı", ClusterMapMath.isSafe(Box(510, 150, 1870, 600)))
        assertFalse("ekran dışı", ClusterMapMath.isSafe(Box(510, 150, 1900, 500)))
        assertFalse("çok küçük", ClusterMapMath.isSafe(Box(600, 200, 800, 300)))
    }

    @Test fun parseBox() {
        assertEquals(Box(510, 150, 1310, 600), ClusterMapMath.parseBox(" 510,150, 1310 ,600"))
        assertNull(ClusterMapMath.parseBox("510,150,1310"))
        assertNull(ClusterMapMath.parseBox("a,b,c,d"))
        assertNull(ClusterMapMath.parseBox("600,150,500,600"))
        assertNull(ClusterMapMath.parseBox(null))
    }

    @Test fun launchBoundsAddCaptionAbove() {
        val b = ClusterMapMath.launchBounds(WindowSize.ORTA.hole)
        assertEquals(Box(510, 150 - ClusterMapMath.CAPTION_PX, 1310, 600), b)
        assertEquals(Box(255, 55, 655, 300), ClusterMapMath.launchBounds(WindowSize.ORTA.hole, 0.5f, 0.5f))
    }

    @Test fun frameChoice() {
        assertEquals(Frame.GECE, ClusterMapMath.resolveFrame(FrameChoice.OTOMATIK, night = true))
        assertEquals(Frame.GUNDUZ, ClusterMapMath.resolveFrame(FrameChoice.OTOMATIK, night = false))
        assertEquals(Frame.UYDU, ClusterMapMath.resolveFrame(FrameChoice.UYDU, night = false))
        assertEquals(FrameChoice.GUNDUZ, FrameChoice.of(" Gunduz"))
        assertEquals(FrameChoice.OTOMATIK, FrameChoice.of("bilinmez"))
        assertEquals(WindowSize.ORTA, WindowSize.of(null))
    }

    @Test fun choosePackage() {
        val navi = setOf(ClusterMapMath.YANDEX_NAVI)
        val maps = setOf(ClusterMapMath.YANDEX_MAPS)
        val google = setOf(ClusterMapMath.GOOGLE_MAPS)
        assertEquals(ClusterMapMath.YANDEX_NAVI, ClusterMapMath.choosePackage(MapApp.NAVI, (navi + maps)::contains, false))
        assertEquals(ClusterMapMath.YANDEX_MAPS, ClusterMapMath.choosePackage(MapApp.MAPS, (navi + maps)::contains, false))
        assertEquals(ClusterMapMath.YANDEX_MAPS, ClusterMapMath.choosePackage(MapApp.NAVI, maps::contains, false))
        assertNull(ClusterMapMath.choosePackage(MapApp.NAVI, google::contains, debug = false))
        assertEquals(ClusterMapMath.GOOGLE_MAPS, ClusterMapMath.choosePackage(MapApp.NAVI, google::contains, debug = true))
    }

    @Test fun innerShadowFades() {
        assertEquals(150, ClusterMapMath.innerShadowAlpha(150, 0))
        assertTrue(ClusterMapMath.innerShadowAlpha(150, 10) < ClusterMapMath.innerShadowAlpha(150, 5))
        assertEquals(0, ClusterMapMath.innerShadowAlpha(150, ClusterMapMath.INNER_SHADOW_PX))
    }

    @Test fun edgesStayDarkForQnxText() {
        Frame.values().forEach { f ->
            val p = ClusterMapMath.palette(f)
            listOf(p.base, p.headerTop).forEach { c ->
                val lum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                assertTrue("${f.key} kenar koyu", lum < 3 * 0x20)
            }
        }
    }
}
