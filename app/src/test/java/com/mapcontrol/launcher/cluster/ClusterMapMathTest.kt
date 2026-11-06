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
        WindowSize.values().forEach {
            assertTrue(it.name, ClusterMapMath.isSafe(it.hole))
            // Gerçek pencere (başlık payıyla) da güvenli: çerçeve ölse bile başlık QNX simgelerine (y<140) girmez.
            assertTrue(it.name, ClusterMapMath.isSafeWindow(it.hole))
            assertTrue(it.name, ClusterMapMath.launchBounds(it.hole).top >= 140)
        }
        assertEquals(800, WindowSize.ORTA.hole.width)
        assertEquals(420, WindowSize.ORTA.hole.height)
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
        assertEquals(Box(510, 180 - ClusterMapMath.CAPTION_PX, 1310, 600), b)
        assertEquals(Box(255, 70, 655, 300), ClusterMapMath.launchBounds(WindowSize.ORTA.hole, 0.5f, 0.5f))
    }

    @Test fun safeWindowCountsCaption() {
        // Görünür kısım güvenli ama başlık çubuğu (40 px yukarı) QNX simge şeridine giriyor.
        assertTrue(ClusterMapMath.isSafe(Box(510, 150, 1310, 600)))
        assertFalse(ClusterMapMath.isSafeWindow(Box(510, 150, 1310, 600)))
    }

    @Test fun onlyYandexIsFramed() {
        assertTrue(ClusterMapMath.canFrame(ClusterMapMath.YANDEX_NAVI, false))
        assertTrue(ClusterMapMath.canFrame(ClusterMapMath.YANDEX_MAPS, false))
        assertFalse(ClusterMapMath.canFrame(ClusterMapMath.GOOGLE_MAPS, false))
        assertTrue(ClusterMapMath.canFrame(ClusterMapMath.GOOGLE_MAPS, true))
        assertFalse(ClusterMapMath.canFrame("com.waze", true))
        assertFalse(ClusterMapMath.canFrame(null, true))
    }

    private fun dump(display: Int, mode: String, bounds: String, pkg: String = ClusterMapMath.YANDEX_MAPS) = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #$display (activities from top to bottom):
          * Task{ca0c8fb #820 visible=true type=standard mode=$mode translucent=false A=10166:$pkg U=0 StackId=820 sz=1}
            mBounds=Rect($bounds)
            mMinWidth=-1 mMinHeight=-1
        Display #0 (activities from top to bottom):
          * Task{1 #1 visible=true type=home mode=fullscreen translucent=false I=com.android.launcher/.Home U=0 StackId=1 sz=1}
            mBounds=Rect(0, 0 - 0, 0)
    """.trimIndent()

    @Test fun taskCheck() {
        val exp = Box(510, 140, 1310, 600)
        val pkg = ClusterMapMath.YANDEX_MAPS
        assertEquals(ClusterMapMath.TaskCheck.OK, ClusterMapMath.checkTask(dump(2, "freeform", "510, 140 - 1310, 600"), pkg, 2, exp))
        assertEquals(ClusterMapMath.TaskCheck.OK, ClusterMapMath.checkTask(dump(2, "freeform", "512, 141 - 1308, 600"), pkg, 2, exp))
        // Harita zaten açıktı: görev tam ekran taşındı.
        assertEquals(ClusterMapMath.TaskCheck.WRONG, ClusterMapMath.checkTask(dump(2, "fullscreen", "0, 0 - 0, 0"), pkg, 2, exp))
        assertEquals(ClusterMapMath.TaskCheck.WRONG, ClusterMapMath.checkTask(dump(2, "freeform", "0, 0 - 0, 0"), pkg, 2, exp))
        assertEquals(ClusterMapMath.TaskCheck.WRONG, ClusterMapMath.checkTask(dump(3, "freeform", "510, 140 - 1310, 600"), pkg, 2, exp))
        // Okunamayan çıktı ya da görev yok: bilinmiyor.
        assertEquals(ClusterMapMath.TaskCheck.UNKNOWN, ClusterMapMath.checkTask(null, pkg, 2, exp))
        assertEquals(ClusterMapMath.TaskCheck.UNKNOWN, ClusterMapMath.checkTask("Permission Denial: can't dump", pkg, 2, exp))
        assertEquals(ClusterMapMath.TaskCheck.UNKNOWN,
            ClusterMapMath.checkTask(dump(2, "freeform", "510, 140 - 1310, 600", pkg = "com.other"), pkg, 2, exp))
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
