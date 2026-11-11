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
            // Gerçek pencerenin başlık çubuğu QNX üst şeridinin içinde, ekranda kalır.
            assertTrue(it.name, ClusterMapMath.isSafeWindow(it.hole))
            assertTrue(it.name, ClusterMapMath.launchBounds(it.hole).top >= ClusterMapMath.CAPTION_MIN_TOP)
        }
        assertEquals(800, WindowSize.ORTA.hole.width)
        assertEquals(420, WindowSize.ORTA.hole.height)
    }

    @Test fun tamFillsMeasuredSafeZone() {
        val tam = WindowSize.TAM.hole
        assertEquals(Box(500, 120, 1880, 600), tam)
        assertEquals(1380, tam.width)
        assertEquals(480, tam.height)
        assertEquals(WindowSize.TAM, WindowSize.of(null))
        assertEquals(WindowSize.TAM, WindowSize.of("bilinmez"))
        assertEquals(WindowSize.ORTA, WindowSize.of("orta"))
        // Başlık çubuğu içeriğin üstünde y 80–120: QNX üst şeridinde, katmanın opak kısmında.
        val launch = ClusterMapMath.launchBounds(tam)
        assertEquals(Box(500, 80, 1880, 600), launch)
        // Sol hız bloğu (x 200–490) ve alt şerit (y ≥ 620) dışında.
        assertTrue(tam.left >= 490 && tam.bottom <= 620)
        // Hız limiti tabelası (x 710–790, y 225–305) haritanın üstünde süzülebilir: bilerek içeride.
        assertTrue(tam.left < 710 && tam.right > 790 && tam.top < 225 && tam.bottom > 305)
        // Bir piksel bile taşan pencere reddedilir.
        assertFalse(ClusterMapMath.isSafeWindow(Box(499, 120, 1880, 600)))
        assertFalse(ClusterMapMath.isSafeWindow(Box(500, 119, 1880, 600)))
        assertFalse(ClusterMapMath.isSafeWindow(Box(500, 120, 1881, 600)))
        assertFalse(ClusterMapMath.isSafeWindow(Box(500, 120, 1880, 611)))
    }

    @Test fun rejectsQnxZones() {
        assertFalse("hız bloğu", ClusterMapMath.isSafe(Box(400, 150, 1300, 600)))
        assertFalse("üst simgeler", ClusterMapMath.isSafe(Box(510, 100, 1300, 600)))
        assertFalse("alt şerit", ClusterMapMath.isSafe(Box(510, 150, 1300, 650)))
        assertFalse("ekran dışı", ClusterMapMath.isSafe(Box(510, 150, 1900, 500)))
        assertFalse("çok küçük", ClusterMapMath.isSafe(Box(600, 200, 800, 300)))
        // Sağ alt geçici yazı artık haritanın üstünde kalabilir (katman köşeyi koyulaştırır).
        assertTrue("sağ alt yazı", ClusterMapMath.isSafe(Box(510, 150, 1870, 600)))
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
        assertTrue(ClusterMapMath.isSafeWindow(Box(510, 150, 1310, 600)))
        // Başlık payı ekranın en üstüne (y < 80) çıkarsa reddedilir; tek başına y 120 sınırı bunu zaten önler.
        assertEquals(ClusterMapMath.CAPTION_MIN_TOP, 120 - ClusterMapMath.CAPTION_PX)
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

    private val tam = WindowSize.TAM.hole
    private fun cover(x: Int, y: Int, f: Frame = Frame.GECE) =
        ClusterMapMath.veilCover(x + 0.5f, y + 0.5f, tam, ClusterMapMath.veil(f))
    private fun lum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)

    @Test fun veilOpaqueOutsideMapAndOverCaption() {
        Frame.values().forEach { f ->
            assertEquals(1f, cover(300, 500, f), 0f) // hız bloğu
            assertEquals(1f, cover(1000, 650, f), 0f) // alt şerit
            for (x in listOf(500, 900, 1500, 1879)) assertEquals("başlık $x", 1f, cover(x, 100, f), 0f)
        }
    }

    @Test fun veilClearCenterAndSoftEdges() {
        Frame.values().forEach { f ->
            val cx = (tam.left + tam.right) / 2
            assertTrue("${f.key} merkez açık", cover(cx, 380, f) < 0.05f)
            // Kenarlarda zemine erir (opak kutu sınırı yok: kenarda ~1, içeri doğru düzgün azalır).
            assertTrue(cover(tam.left, 380, f) > 0.98f)
            assertTrue(cover(tam.right - 1, 380, f) > 0.98f)
            assertTrue(cover(cx, tam.bottom - 1, f) > 0.98f)
            var prev = 1.01f
            for (x in tam.left until cx step 5) {
                val c = cover(x, 380, f)
                assertTrue("${f.key} x=$x sönüyor", c <= prev + 1e-4f)
                prev = c
            }
            // Erime genişliği: kenardan ~feather kadar içeride hâlâ belirgin örtme, sonrasında hafif.
            assertTrue(cover(tam.left + ClusterMapMath.FEATHER_LEFT / 3, 380, f) > 0.5f)
        }
    }

    @Test fun veilDarkensHintCornerAndTopRow() {
        val v = Frame.GECE
        // Sağ alt geçici yazı (x 1340–1790, y 530–590) sol alttaki aynı yükseklikten belirgin koyu.
        assertTrue(cover(1560, 545, v) > cover(820, 545, v) + 0.3f)
        assertTrue(cover(1560, 545, v) > 0.85f)
        // Üst QNX sırasının altı (y 150) aynı x'te ortadan koyu.
        assertTrue(cover(1250, 160, v) > cover(1250, 330, v) + 0.3f)
        // Oval vinyet: köşeye yakın nokta, kenar ortasından koyu.
        assertTrue(cover(640, 230, v) > cover(1190, 230, v))
    }

    @Test fun dayVeilIsLighterGraphite() {
        val day = ClusterMapMath.veil(Frame.GUNDUZ)
        val night = ClusterMapMath.veil(Frame.GECE)
        assertTrue(lum(day.base) > lum(night.base) + 3 * 0x30)
        assertTrue(lum(day.base) < 3 * 0x80) // hâlâ grafit, beyaz değil
        assertTrue(day.oval < night.oval && day.hint < night.hint && day.topRow < night.topRow)
        Frame.values().forEach { assertEquals(0xFF, (ClusterMapMath.veil(it).base ushr 24) and 0xFF) }
    }

    // --- Premium katmanlar ---

    private fun mix(x: Int, y: Int, f: Frame, accent: Int? = null) =
        ClusterMapMath.overlay(x + 0.5f, y + 0.5f, tam, ClusterMapMath.veil(f), accent, ClusterMapMath.Mix())

    /** Harita pikseli [map] (0..1 gri) katmanın altında: ekranda görünen (kanal ortalaması). */
    private fun seen(m: ClusterMapMath.Mix, map: Float) = ((m.r + m.g + m.b) / 3f) + (1f - m.a) * map

    private val focusX = (tam.left + tam.right) / 2
    private val focusY = Math.round(tam.top + tam.height * ClusterMapMath.LIGHT_CY)

    @Test fun mapStaysReadableUnderGradeAndLight() {
        Frame.values().forEach { f ->
            for ((x, y) in listOf(focusX to focusY, focusX to 360, 900 to 420, 1400 to 320)) {
                val m = mix(x, y, f)
                // Kontrast: beyaz ile siyah harita pikselinin farkı en az %80 korunur.
                assertTrue("${f.key} ($x,$y) kontrast ${seen(m, 1f) - seen(m, 0f)}", seen(m, 1f) - seen(m, 0f) >= 0.80f)
            }
        }
    }

    @Test fun gradeTintsPerStyle() {
        val night = mix(focusX, 360, Frame.GECE)
        assertTrue("gece serin", night.b > night.r)
        val day = mix(focusX, 360, Frame.GUNDUZ)
        assertTrue("gündüz sıcak", day.r > day.b)
        assertEquals(0f, ClusterMapMath.veil(Frame.UYDU).gradeAlpha, 0f)
        Frame.values().forEach {
            val a = ClusterMapMath.veil(it).gradeAlpha
            assertTrue("${it.key} ton çok hafif", a <= 0.12f)
        }
    }

    @Test fun innerLightLiftsLowerMiddleOnly() {
        Frame.values().forEach { f ->
            val v = ClusterMapMath.veil(f)
            val peak = ClusterMapMath.lightAlpha(focusX + 0.5f, focusY + 0.5f, tam, v)
            assertEquals(v.lightAlpha, peak, 0.002f)
            assertTrue(v.lightAlpha in 0.02f..0.08f)
            assertTrue(ClusterMapMath.lightAlpha(focusX.toFloat(), tam.top + 30f, tam, v) < peak / 2)
            assertEquals(0f, ClusterMapMath.lightAlpha(tam.left + 20f, 360f, tam, v), 0f)
            assertEquals(0f, ClusterMapMath.lightAlpha(300f, 360f, tam, v), 0f)
        }
        // Ambiyans vurgusu iç ışığı renklendirir (gece, turuncu → kırmızı kanal maviden fazla).
        val lit = mix(focusX, focusY, Frame.GECE, accent = 0xFF4200)
        val plain = mix(focusX, focusY, Frame.GECE)
        assertTrue(lit.r - lit.b > plain.r - plain.b)
    }

    @Test fun horizonHazeFadesDownFromTop() {
        Frame.values().forEach { f ->
            val v = ClusterMapMath.veil(f)
            assertEquals(v.hazeAlpha, ClusterMapMath.hazeAlpha(tam.top.toFloat(), tam, v), 1e-4f)
            assertEquals(0f, ClusterMapMath.hazeAlpha((tam.top + ClusterMapMath.HAZE_FADE).toFloat(), tam, v), 0f)
            assertEquals(0f, ClusterMapMath.hazeAlpha(100f, tam, v), 0f) // pencere dışında yok
            var prev = 1f
            for (y in tam.top until tam.top + ClusterMapMath.HAZE_FADE step 10) {
                val h = ClusterMapMath.hazeAlpha(y.toFloat(), tam, v)
                assertTrue(h <= prev + 1e-5f)
                prev = h
            }
        }
        val night = ClusterMapMath.veil(Frame.GECE).haze
        assertTrue("gece sisi mavi-siyah", (night and 0xFF) > (night shr 16) && lum(night) < 3 * 0x30)
        val day = ClusterMapMath.veil(Frame.GUNDUZ).haze
        assertTrue("gündüz sisi sıcak-nötr açık", (day shr 16) >= (day and 0xFF) && lum(day) > 3 * 0xB0)
    }

    @Test fun qnxTopRowStaysLegibleAtNight() {
        // (A) simgeleri y≈120, x 1250/1370: altındaki harita beyaz olsa bile gece burası koyu kalır (beyaz simge okunur).
        for (f in listOf(Frame.GECE, Frame.UYDU)) for (x in listOf(1250, 1370)) {
            assertTrue("${f.key} x=$x", seen(mix(x, 135, f), 1f) < 0.35f)
        }
    }

    @Test fun lightGuideLineFadesToNothing() {
        Frame.values().forEach { f ->
            val v = ClusterMapMath.veil(f)
            assertEquals(v.lineAlpha, ClusterMapMath.lineAlpha(ClusterMapMath.LINE_CX, v), 1e-5f)
            assertEquals(0f, ClusterMapMath.lineAlpha(ClusterMapMath.LINE_CX - ClusterMapMath.LINE_HALF, v), 1e-6f)
            assertEquals(0f, ClusterMapMath.lineAlpha(ClusterMapMath.LINE_CX + ClusterMapMath.LINE_HALF + 50, v), 0f)
            assertTrue(ClusterMapMath.lineAlpha(700f, v) < ClusterMapMath.lineAlpha(900f, v))
            assertEquals(ClusterMapMath.lineAlpha(700f, v), ClusterMapMath.lineAlpha(1220f, v), 1e-5f)
        }
        // QNX üst sırasının hemen altında (y 118–122) ve sıra başlığının içinde değil.
        assertTrue(ClusterMapMath.LINE_Y in 118f..122f)
        assertTrue("gece serin beyaz", lum(ClusterMapMath.veil(Frame.GECE).line) > 3 * 0xC8)
        assertTrue("gündüz grafit", lum(ClusterMapMath.veil(Frame.GUNDUZ).line) < 3 * 0x40)
    }

    @Test fun ambientColourOnlyWhenKnownAndReadable() {
        assertEquals(0xFF4200, ClusterMapMath.ambientRgb(45))
        assertNull("bilinmeyen sıra", ClusterMapMath.ambientRgb(12))
        assertNull(ClusterMapMath.ambientRgb(null))
        assertNull(ClusterMapMath.ambientRgb(0))
        assertNull(ClusterMapMath.ambientRgb(-1))
        assertEquals(0x4080FF, ClusterMapMath.ambientRgb(0x4080FF))
        assertNull("çok koyu", ClusterMapMath.ambientRgb(0x101820))
        assertEquals(0x808080, ClusterMapMath.mixRgb(0xFFFFFF, 0x000000, 0.5f))
        assertEquals(0xFFFFFF, ClusterMapMath.mixRgb(0xFFFFFF, 0xFF4200, 0f))
        assertEquals(0xFF4200, ClusterMapMath.mixRgb(0xFFFFFF, 0xFF4200, 1f))
    }

    @Test fun timingIsOneShotAndShort() {
        assertTrue(ClusterMapMath.OPEN_FADE_MS in 500L..700L)
        assertTrue(ClusterMapMath.STYLE_FADE_MS in 300L..500L)
    }
}
