package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Panoramik tavan: ham değer çözümü, perde konum tahmini, çizim geometrisi ve demo ekstrası. */
class RoofStateTest {

    private val eps = 1e-4f

    @Test
    fun shadeDecodesMeasuredStates() {
        assertEquals(RoofDecode.Motion.CLOSED, RoofDecode.shade(1)?.motion)
        assertEquals(RoofDecode.Motion.OPENING, RoofDecode.shade(2)?.motion)
        assertEquals(RoofDecode.Motion.STOPPED, RoofDecode.shade(3)?.motion)
        assertEquals(RoofDecode.Motion.CLOSING, RoofDecode.shade(4)?.motion)
        assertEquals(RoofDecode.Motion.OPEN, RoofDecode.shade(5)?.motion)
        assertEquals(RoofDecode.Motion.STOPPED, RoofDecode.shade(6)?.motion)
        // Desteklenmiyor / gelmedi / geçersiz: bilinmiyor.
        assertNull(RoofDecode.shade(7))
        assertNull(RoofDecode.shade(0))
        assertNull(RoofDecode.shade(null))
        assertNull(RoofDecode.shade(Int.MIN_VALUE))
        assertNull(RoofDecode.shade(-3))
        assertNull(RoofDecode.shade(255))
        // Yüzde gelen modeller: 8..100.
        assertEquals(RoofDecode.Motion.PERCENT, RoofDecode.shade(40)?.motion)
        assertEquals(0.4f, RoofDecode.shade(40)!!.fraction, eps)
    }

    @Test
    fun glassDecodesPosiThenOperate() {
        assertEquals(RoofDecode.Glass(0f), RoofDecode.glass(1, 2))
        assertEquals(RoofDecode.Glass(0f, tilt = true), RoofDecode.glass(12, null))
        assertEquals(0.4f, RoofDecode.glass(5, null)!!.fraction, eps)
        assertEquals(1f, RoofDecode.glass(11, null)!!.fraction, eps)
        assertEquals(0.55f, RoofDecode.glass(55, null)!!.fraction, eps)
        assertEquals(1f, RoofDecode.glass(100, null)!!.fraction, eps)
        // Konum okunamazsa SPF_OPERATE: 3/6/7 açık, 2/5 kapalı, 4 hareket = bilinmiyor.
        assertEquals(RoofDecode.Glass(1f), RoofDecode.glass(0, 3))
        assertEquals(RoofDecode.Glass(0f), RoofDecode.glass(null, 5))
        assertNull(RoofDecode.glass(0, 4))
        assertNull(RoofDecode.glass(300, 0))
        assertNull(RoofDecode.glass(null, null))
    }

    @Test
    fun edgeAlertNeedsOpenRoofAndHasHysteresis() {
        assertFalse(RoofDecode.edgeAlert(open = false, speedKmh = 130, raining = true, wasOn = false))
        assertTrue(RoofDecode.edgeAlert(open = true, speedKmh = 10, raining = true, wasOn = false))
        assertFalse(RoofDecode.edgeAlert(open = true, speedKmh = 85, raining = false, wasOn = false))
        assertTrue(RoofDecode.edgeAlert(open = true, speedKmh = 90, raining = false, wasOn = false))
        assertTrue(RoofDecode.edgeAlert(open = true, speedKmh = 85, raining = false, wasOn = true))
        assertFalse(RoofDecode.edgeAlert(open = true, speedKmh = 79, raining = false, wasOn = true))
        assertFalse(RoofDecode.edgeAlert(open = true, speedKmh = null, raining = false, wasOn = true))
    }

    @Test
    fun trackerEstimatesShadeFromMotionTime() {
        val t = RoofTracker(openMs = 10_000, closeMs = 20_000)
        val closed = t.update(1, 1, 2, 0, false, 0)!!
        assertEquals(0f, closed.shade!!, eps)
        assertEquals(0L, closed.shadeMs)
        // Açılıyor: hedef tam açık, kalan süre tam açılma süresi.
        val opening = t.update(2, 1, 2, 0, false, 1_000)!!
        assertEquals(1f, opening.shade!!, eps)
        assertEquals(10_000L, opening.shadeMs)
        // Aynı ham değer tekrar gelince durum değişmez (çizim yeniden başlamaz).
        assertEquals(opening, t.update(2, 1, 2, 0, false, 4_000))
        // 4 sn sonra yarıda durdu: %40'ta kalır.
        val stopped = t.update(6, 1, 2, 0, false, 5_000)!!
        assertEquals(0.4f, stopped.shade!!, eps)
        assertEquals(0L, stopped.shadeMs)
        // Kapanıyor: %40'tan 0'a, 0,4 × 20 sn.
        val closing = t.update(4, 1, 2, 0, false, 6_000)!!
        assertEquals(0f, closing.shade!!, eps)
        assertEquals(8_000L, closing.shadeMs)
        assertEquals(0.3f, t.estimate(8_000), eps)
        // Tam açık okununca kesin değer.
        assertEquals(1f, t.update(5, 1, 2, 0, false, 9_000)!!.shade!!, eps)
    }

    @Test
    fun trackerFirstReadingsWithoutHistory() {
        assertEquals(RoofDecode.SHADE_UNKNOWN_MID, RoofTracker().update(6, null, null, null, false, 0)!!.shade!!, eps)
        val closing = RoofTracker(closeMs = 10_000).update(4, null, null, null, false, 0)!!
        assertEquals(0f, closing.shade!!, eps)
        assertEquals(10_000L, closing.shadeMs)
        // Hiç tavan sinyali yok: null (taban görseli olduğu gibi).
        assertNull(RoofTracker().update(0, 0, 0, 50, false, 0))
        assertNull(RoofTracker().update(null, null, null, null, false, 0))
    }

    @Test
    fun trackerKeepsLastGlassWhileMovingAndLightsEdge() {
        val t = RoofTracker()
        assertEquals(0.5f, t.update(5, 6, 3, 0, false, 0)!!.glass, eps)
        // SPF_POSI okunamadı, SPF_OPERATE 4 (hareket): son bilinen konum korunur.
        assertEquals(0.5f, t.update(5, 0, 4, 0, false, 100)!!.glass, eps)
        assertTrue(t.update(5, 6, 3, 100, false, 200)!!.edgeAlert)
        assertTrue(t.update(5, 6, 3, 85, false, 300)!!.edgeAlert)
        assertFalse(t.update(5, 6, 3, 60, false, 400)!!.edgeAlert)
        assertTrue(t.update(5, 6, 3, 0, true, 500)!!.edgeAlert)
        // Kapalı cam yağmurda da yanmaz.
        assertFalse(t.update(5, 1, 2, 120, true, 600)!!.edgeAlert)
        // Yalnız cam sinyali: perde bilinmiyor (null), cam yine çizilir.
        val glassOnly = RoofTracker().update(0, 12, 0, 0, false, 0)!!
        assertNull(glassOnly.shade)
        assertTrue(glassOnly.tilt)
        assertTrue(glassOnly.glassOpen)
    }

    @Test
    fun geometryMatchesArt() {
        assertEquals(CarAnimSpec.ROOF_X1, CarAnimSpec.shadeEdgeX(0f), eps)
        assertEquals(CarAnimSpec.ROOF_X0, CarAnimSpec.shadeEdgeX(1f), eps)
        assertEquals(CarAnimSpec.ROOF_X1 - 0.3f * CarAnimSpec.roofW, CarAnimSpec.shadeEdgeX(0.3f), eps)
        assertEquals(CarAnimSpec.ROOF_X0, CarAnimSpec.shadeEdgeX(2f), eps)
        val closed = CarAnimSpec.glassPanelX(0f)
        assertEquals(CarAnimSpec.ROOF_FRONT_X0, closed[0], eps)
        assertEquals(CarAnimSpec.ROOF_X1, closed[1], eps)
        val hole0 = CarAnimSpec.roofHoleX(0f)
        assertEquals(0f, hole0[1] - hole0[0], eps)
        val full = CarAnimSpec.glassPanelX(1f)
        // Tam açıkta panel çerçevenin içinde kalır ve açıklık kadar arkaya kaymıştır.
        assertTrue(full[0] > CarAnimSpec.ROOF_X0)
        assertEquals(CarAnimSpec.ROOF_GLASS_TRAVEL, CarAnimSpec.roofHoleX(1f).let { it[1] - it[0] }, eps)
        assertEquals(CarAnimSpec.ROOF_GLASS_TRAVEL * 0.4f, CarAnimSpec.roofHoleX(0.4f).let { it[1] - it[0] }, eps)
        // Katmanların piksel boyu (2x) cam kutusuyla aynı: 552 × 318.
        assertEquals(552, (CarAnimSpec.roofW * CarAnimSpec.ROOF_ART_SCALE).toInt())
        assertEquals(318, ((CarAnimSpec.ROOF_Y1 - CarAnimSpec.ROOF_Y0) * CarAnimSpec.ROOF_ART_SCALE).toInt())
        // Eski buz mavisi tavan kutusu yeni cam kutusunun içinde.
        val s = CarAnimSpec.sunroof.box
        assertTrue(s.x >= CarAnimSpec.ROOF_X0 && s.right <= CarAnimSpec.ROOF_X1 && s.y >= CarAnimSpec.ROOF_Y0 && s.bottom <= CarAnimSpec.ROOF_Y1)
    }

    @Test
    fun motionEasesThenTracksLinearly() {
        val m = RoofMotion()
        m.snap(0f)
        assertTrue(m.done(0))
        m.set(1f, 0L, 0)
        assertEquals(0f, m.at(0), eps)
        assertEquals(0.5f, m.at(CarAnimSpec.ROOF_EASE_MS / 2), eps)
        assertEquals(1f, m.at(CarAnimSpec.ROOF_EASE_MS), eps)
        assertFalse(m.tracking(10))
        // Gerçek hareket: doğrusal, yarıda gelen hedef o anki yerden başlar.
        m.snap(0f)
        m.set(1f, 10_000L, 0)
        assertTrue(m.tracking(1_000))
        assertEquals(0.25f, m.at(2_500), eps)
        m.set(0f, 0L, 2_500)
        assertEquals(0.25f, m.at(2_500), eps)
        assertEquals(0f, m.at(2_500 + CarAnimSpec.ROOF_EASE_MS), eps)
    }

    @Test
    fun demoExtraParses() {
        val r = RoofDemo.parse("shade=30,glass=40")!!
        assertEquals(0.3f, r.shade!!, eps)
        assertEquals(0.4f, r.glass, eps)
        assertFalse(r.tilt)
        assertFalse(r.edgeAlert)
        assertTrue(RoofDemo.parse("shade=100, glass=60, speed=120")!!.edgeAlert)
        assertTrue(RoofDemo.parse("glass=60,rain")!!.edgeAlert)
        assertNull(RoofDemo.parse("glass=60,rain")!!.shade)
        // Kapalı camda kenar ışığı yok.
        assertFalse(RoofDemo.parse("shade=0,alert")!!.edgeAlert)
        assertTrue(RoofDemo.parse("shade=100,tilt")!!.tilt)
        assertEquals(1f, RoofDemo.parse("shade=250")!!.shade!!, eps)
        assertNull(RoofDemo.parse(""))
        assertNull(RoofDemo.parse("nonsense"))
        assertNotNull(RoofDemo.parse("SHADE=0"))
    }
}
