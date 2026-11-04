package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportLogicTest {

    // --- Kapı (debounce) ---

    @Test fun sportNeedsOneSecondOfStableSignal() {
        val g = SportGate(1_000)
        assertNull(g.update(0, DriveAccent.SPORT, true))
        assertNull(g.update(900, DriveAccent.SPORT, true))
        assertEquals(true, g.update(1_000, DriveAccent.SPORT, true))
        assertTrue(g.active)
        assertNull(g.update(1_500, DriveAccent.SPORT, true))
    }

    @Test fun flickerDoesNotSwitch() {
        val g = SportGate(1_000)
        g.update(0, DriveAccent.SPORT, true)
        g.update(600, DriveAccent.NORMAL, true)
        assertNull(g.update(1_200, DriveAccent.SPORT, true))
        assertNull(g.update(2_100, DriveAccent.SPORT, true))
        assertEquals(true, g.update(2_200, DriveAccent.SPORT, true))
        // Spor'dan tek okumalık sapma kapatmaz.
        g.update(3_000, DriveAccent.ECO, true)
        assertNull(g.update(3_500, DriveAccent.SPORT, true))
        assertTrue(g.active)
    }

    @Test fun unknownKeepsStateAndDeferredWhileBusy() {
        val g = SportGate(1_000)
        g.reset(true)
        assertNull(g.update(0, null, true))
        assertNull(g.update(0, DriveAccent.NORMAL, false))
        assertNull(g.update(2_000, DriveAccent.NORMAL, false))
        assertTrue(g.active)
        assertEquals(false, g.update(2_100, DriveAccent.NORMAL, true))
    }

    // --- Devir ve vites ---

    @Test fun rpmIsRawTimesFour() {
        assertEquals(752, SportMath.rpm(188))
        assertEquals(0, SportMath.rpm(0))
        assertEquals(3_400, SportMath.rpm(850))
        assertNull(SportMath.rpm(null))
        assertNull(SportMath.rpm(-1))
        assertNull(SportMath.rpm(0xFFFF))
    }

    @Test fun needleCoversTheArcAndClamps() {
        assertEquals(135f, SportMath.needleDeg(0f), 0.01f)
        assertEquals(405f, SportMath.needleDeg(8_000f), 0.01f)
        assertEquals(270f, SportMath.needleDeg(4_000f), 0.01f)
        assertEquals(405f, SportMath.needleDeg(12_000f), 0.01f)
        assertEquals(135f, SportMath.needleDeg(-50f), 0.01f)
    }

    @Test fun gearText() {
        assertEquals("P", SportMath.gearText(1))
        assertEquals("R", SportMath.gearText(2))
        assertEquals("N", SportMath.gearText(3))
        assertEquals("D", SportMath.gearText(5))
        assertEquals("D4", SportMath.gearText(5, 4))
        assertEquals("D", SportMath.gearText(5, 0))
        assertEquals("—", SportMath.gearText(null))
        // Sürüşte D sinyali 6/7'ye de geçer (araçta görüldü); eski biçimleyici 4'ü D sayar: hepsi "D", kademe uydurulmaz.
        assertEquals("D", SportMath.gearText(4))
        assertEquals("D", SportMath.gearText(6))
        assertEquals("D", SportMath.gearText(7))
        assertEquals("D", SportMath.gearText(11))
        assertEquals("—", SportMath.gearText(0))
        assertEquals("—", SportMath.gearText(16))
    }

    // --- 0–100 ve en yüksek hız ---

    /** Her 100 ms'de 1 km/sa artış: 0'dan sonra ilk örnek 1 km/sa. */
    private fun accelerate(s: SportSession, from: Long, toKmh: Int, stepMs: Long = 100, startKmh: Int = 0): Long {
        var t = from
        var v = startKmh
        while (v < toKmh) {
            v++
            t += stepMs
            s.update(t, v, 3_000)
        }
        return t
    }

    @Test fun zeroToHundredFromStandstill() {
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(500, 0, 800)
        // Son 0 örneği t=500, ilk örnek t=600 (1 km/sa), ikinci t=700 (2): geriye uzatma kalkışı t=500 bulur;
        // 100 km/sa t=600+99*100.
        accelerate(s, 500, 100)
        assertEquals(10_000L, s.best0to100Ms)
        assertEquals(100, s.maxSpeedKmh)
        assertFalse(s.timing)
    }

    @Test fun crossingIsInterpolated() {
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(1_000, 20, 3_000) // başlangıç (kalkış 0..1 000 arasında)
        s.update(8_000, 90, 4_000) // geriye uzatma t=0'ın da öncesini verir: son 0 örneğine (0) kırpılır
        s.update(9_000, 110, 4_500) // 100'e yarı yolda: 8 500
        assertEquals(8_500L, s.best0to100Ms)
        assertEquals(110, s.maxSpeedKmh)
    }

    @Test fun sparseSamplesEstimateTheLaunch() {
        // ~1 Hz okuma: gerçek kalkış t=500, sabit 12 km/sa/sn (gerçek 0–100 = 8 333 ms).
        val s = SportSession()
        s.update(0, 0, 800)
        var t = 1_000L
        while (t <= 9_000L) {
            s.update(t, (12 * (t - 500) / 1000).toInt(), 3_000)
            t += 1_000
        }
        assertEquals(8_333.0, s.best0to100Ms!!.toDouble(), 5.0)
    }

    @Test fun singleMovingSampleUsesMidpoint() {
        // İkinci hareket örneği gelmeden 100 aşılırsa (aradeğerle) kalkış son 0 ile ilk hareket örneğinin ortası.
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(1_000, 10, 3_000)
        s.update(9_000, 10, 4_000) // hız aynı: kestirim yapılamaz
        s.update(10_000, 110, 4_500) // 100'e geçiş: 9 900
        assertEquals(9_400L, s.best0to100Ms)
    }

    @Test fun firstSampleAlreadyAboveTargetDoesNotTime() {
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(10_000, 104, 4_000)
        s.update(11_000, 110, 4_000)
        assertNull(s.best0to100Ms)
    }

    @Test fun rollingStartDoesNotTime() {
        val s = SportSession()
        s.update(0, 40, 2_000)
        s.update(1_000, 60, 3_000)
        s.update(3_000, 105, 5_000)
        assertNull(s.best0to100Ms)
        assertEquals(105, s.maxSpeedKmh)
    }

    @Test fun stopCancelsAndTimeoutCancels() {
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(100, 5, 2_000)
        s.update(2_000, 30, 3_000)
        s.update(3_000, 0, 900) // durdu: iptal
        assertFalse(s.timing)
        s.update(3_100, 3, 2_000) // yeniden başlar
        assertTrue(s.timing)
        s.update(34_000, 60, 3_000) // 30 sn geçti
        assertFalse(s.timing)
        s.update(35_000, 101, 4_000)
        assertNull(s.best0to100Ms)
    }

    @Test fun keepsBestOfDrive() {
        val s = SportSession()
        s.update(0, 0, 800)
        accelerate(s, 0, 100, stepMs = 110) // 10,89 sn
        val slow = s.best0to100Ms!!
        var t = 20_000L
        s.update(t, 0, 800)
        t = accelerate(s, t, 100, stepMs = 90) // 8,91 sn
        val fast = s.best0to100Ms!!
        assertTrue(fast < slow)
        s.update(t + 1_000, 0, 800)
        accelerate(s, t + 1_000, 100, stepMs = 150)
        assertEquals(fast, s.best0to100Ms)
    }

    @Test fun newDriveAfterEngineOffResets() {
        val s = SportSession()
        s.update(0, 0, 800)
        accelerate(s, 0, 120)
        assertEquals(120, s.maxSpeedKmh)
        s.update(20_000, 0, 0)
        s.update(60_000, 0, 0) // kısa duruş (start-stop): korunur
        s.update(61_000, 0, 800)
        assertEquals(120, s.maxSpeedKmh)
        s.update(70_000, 0, 0)
        s.update(70_000 + SportSession.NEW_DRIVE_OFF_MS, 0, 0)
        s.update(70_000 + SportSession.NEW_DRIVE_OFF_MS + 5_000, 0, 750) // motor çalıştı: yeni sürüş
        assertNull(s.maxSpeedKmh)
        assertNull(s.best0to100Ms)
    }

    @Test fun newDriveWithChangeOnlySamples() {
        // Değer yalnız değişince gelir: kapalı örnekten sonra 2 dk boyunca örnek yok, sonra motor çalışır.
        val s = SportSession()
        s.update(0, 0, 800)
        accelerate(s, 0, 80)
        s.update(30_000, 0, 0)
        s.update(30_000 + SportSession.NEW_DRIVE_OFF_MS + 1, 0, 780)
        assertNull(s.maxSpeedKmh)
    }

    @Test fun disconnectCountsAsOff() {
        // Kontak kapanınca servis düşerse çağıran devir 0, hız bilinmiyor verir.
        val s = SportSession()
        s.update(0, 0, 800)
        accelerate(s, 0, 90)
        s.update(40_000, null, 0)
        s.update(40_000 + SportSession.NEW_DRIVE_OFF_MS, 0, 760)
        assertNull(s.maxSpeedKmh)
    }

    @Test fun unknownSpeedCancelsRun() {
        val s = SportSession()
        s.update(0, 0, 800)
        s.update(100, 10, 2_000)
        s.update(200, null, 2_000)
        assertFalse(s.timing)
    }

    // --- G süzgeci ---

    @Test fun gravityIsRemovedForPlainAccelerometer() {
        val f = GFilter(linear = false)
        var t = 0L
        // Eğik duran ekran: yerçekimi y ve z'ye dağılmış, hareket yok.
        repeat(200) { f.add(t, 0f, 8f, 5.6f); t += 66_666_000L }
        assertEquals(0f, f.lateral, 0.01f)
        assertEquals(0f, f.longitudinal, 0.01f)
        // Sola dönüş: ani 0,5 g ivme -x; yolcu sağa itilir, kısa sürede görünür.
        repeat(8) { f.add(t, -0.5f * GFilter.G, 8f, 5.6f); t += 66_666_000L }
        assertTrue(f.lateral > 0.3f)
    }

    @Test fun linearAccelerationIsSmoothed() {
        val f = GFilter(linear = true)
        var t = 0L
        f.add(t, 0f, 0f, 0f)
        t += 66_666_000L
        f.add(t, -GFilter.G, 0f, 0f)
        assertTrue(f.lateral in 0.05f..0.5f) // tek örnek sıçratmaz
        repeat(60) { t += 66_666_000L; f.add(t, -GFilter.G, 0f, -GFilter.G / 2) }
        assertEquals(1f, f.lateral, 0.02f)
        assertEquals(-0.5f, f.longitudinal, 0.02f)
        assertEquals(1.118f, f.magnitude, 0.03f)
    }

    @Test fun gSignsFollowFeltForce() {
        fun settle(x: Float, z: Float): GFilter = GFilter(linear = true).also { f ->
            var t = 0L
            repeat(60) { f.add(t, x, 0f, z); t += 66_666_000L }
        }
        // Sola dönüş (ivme -x): sağa itiş, nokta sağa (+).
        assertEquals(0.5f, settle(-0.5f * GFilter.G, 0f).lateral, 0.02f)
        // Sağa dönüş (ivme +x): sola itiş (-).
        assertEquals(-0.4f, settle(0.4f * GFilter.G, 0f).lateral, 0.02f)
        // Fren (araç geriye ivmelenir, +z sürücüye/arkaya): öne itiş (+), ekranda nokta yukarı.
        assertEquals(0.6f, settle(0f, 0.6f * GFilter.G).longitudinal, 0.02f)
        // Gaz (ivme öne, -z): arkaya itiş (-), nokta aşağı.
        assertEquals(-0.3f, settle(0f, -0.3f * GFilter.G).longitudinal, 0.02f)
    }

    // --- Biçim ---

    @Test fun formatsWithComma() {
        assertEquals("9,6", SportFormat.seconds(9_600L))
        assertEquals("9,6", SportFormat.seconds(9_640L))
        assertEquals("9,8", SportFormat.l100(9.8))
        assertEquals("0,0", SportFormat.g(0.02f))
        assertEquals("-0,3", SportFormat.g(-0.3f))
        assertNull(SportFormat.seconds(null))
    }
}
