package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniDashLogicTest {

    private val okTires = TireReading(listOf(2.4, 2.4, 2.3, 2.2), warning = false)

    // --- Uyarılar ---

    @Test fun calmWhenEverythingFine() {
        assertTrue(MiniAlerts.build(okTires, lowFuel = false, serviceKm = 3_978, serviceDays = 155, pm25In = 4).isEmpty())
    }

    @Test fun unknownValuesAreNotAlerts() {
        assertTrue(MiniAlerts.build(null, null, null, null, null).isEmpty())
    }

    @Test fun lowTireNamesWheelAndBar() {
        val a = MiniAlerts.build(TireReading(listOf(2.4, 2.4, 1.6, 2.2), warning = false), false, null, null, null)
        assertEquals(listOf(MiniAlerts.Kind.TIRE), a.map { it.kind })
        assertEquals(2, a[0].tireLow?.wheel)
        assertEquals(1.6, a[0].tireLow?.bar!!, 1e-9)
        assertEquals(23, TireDecode.psi(a[0].tireLow!!.bar!!))
    }

    @Test fun tireWarningFlagWithoutLowValueStillAlerts() {
        val a = MiniAlerts.build(okTires.copy(warning = true), false, null, null, null)
        assertEquals(MiniAlerts.Kind.TIRE, a.single().kind)
        assertNull(a.single().tireLow)
    }

    @Test fun orderIsImportanceOrder() {
        val a = MiniAlerts.build(TireReading(listOf(1.5, 2.4, 2.4, 2.4)), lowFuel = true, serviceKm = 800, serviceDays = 40, pm25In = 80)
        assertEquals(listOf(MiniAlerts.Kind.TIRE, MiniAlerts.Kind.FUEL_LOW, MiniAlerts.Kind.SERVICE_NEAR, MiniAlerts.Kind.AIR_BAD), a.map { it.kind })
        assertEquals(800, a[2].serviceKm)
        assertEquals(80, a[3].pm25)
    }

    @Test fun healthAndFuelCapComeFirstInImportanceOrder() {
        val a = MiniAlerts.build(
            TireReading(listOf(1.5, 2.4, 2.4, 2.4)), lowFuel = true, serviceKm = 800, serviceDays = 40, pm25In = 80,
            batteryV = 11.4, fuelCapOpen = true,
        )
        assertEquals(
            listOf(
                MiniAlerts.Kind.BATTERY_LOW, MiniAlerts.Kind.TIRE, MiniAlerts.Kind.FUEL_CAP,
                MiniAlerts.Kind.FUEL_LOW, MiniAlerts.Kind.SERVICE_NEAR, MiniAlerts.Kind.AIR_BAD,
            ),
            a.map { it.kind },
        )
        assertEquals(11.4, a[0].batteryV!!, 1e-9)
        val (named, more) = MiniAlerts.visible(a)
        assertEquals(listOf(MiniAlerts.Kind.BATTERY_LOW, MiniAlerts.Kind.TIRE), named.map { it.kind })
        assertEquals(4, more)
    }

    @Test fun healthUsesHealthMathThresholds() {
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 12.6).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.BATTERY_HIGH), MiniAlerts.build(null, null, null, null, null, batteryV = 15.4, batteryHighHeld = true).map { it.kind })
        // 30 sn sürmeyen aşırı gerilim şeride çıkmaz; 14,5 V (çalışan motor) hiç çıkmaz.
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 15.4).isEmpty())
        assertTrue(MiniAlerts.build(null, null, null, null, null, batteryV = 14.5, batteryHighHeld = true).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.FUEL_CAP), MiniAlerts.build(null, null, null, null, null, fuelCapOpen = true).map { it.kind })
    }

    @Test fun serviceThresholdsMatchSahne() {
        assertTrue(MiniAlerts.build(null, null, StageCorners.SERVICE_NEAR_KM, 200, null).any { it.kind == MiniAlerts.Kind.SERVICE_NEAR })
        assertTrue(MiniAlerts.build(null, null, StageCorners.SERVICE_NEAR_KM + 1, 200, null).isEmpty())
        // Gün yaklaşması Sahne'de bakım notu açmaz (yalnızca km ya da geçmiş).
        assertTrue(MiniAlerts.build(null, null, 5_000, 20, null).isEmpty())
        assertEquals(listOf(MiniAlerts.Kind.SERVICE_OVERDUE), MiniAlerts.build(null, null, 0, 0, null).map { it.kind })
        assertEquals(listOf(MiniAlerts.Kind.SERVICE_OVERDUE), MiniAlerts.build(null, null, 4_000, 0, null).map { it.kind })
    }

    @Test fun airOnlyWhenPoor() {
        assertTrue(MiniAlerts.build(null, null, null, null, StageCorners.AIR_BAD_PM25 - 1).isEmpty())
        assertEquals(MiniAlerts.Kind.AIR_BAD, MiniAlerts.build(null, null, null, null, StageCorners.AIR_BAD_PM25).single().kind)
    }

    @Test fun twoNamedThenPlusN() {
        val all = MiniAlerts.build(TireReading(listOf(1.5, 2.4, 2.4, 2.4)), true, 800, 40, 80)
        val (named, more) = MiniAlerts.visible(all)
        assertEquals(2, named.size)
        assertEquals(MiniAlerts.Kind.TIRE, named[0].kind)
        assertEquals(2, more)
        val (one, none) = MiniAlerts.visible(all.take(1))
        assertEquals(1, one.size)
        assertEquals(0, none)
    }

    // --- Navigasyon ---

    @Test fun navHiddenWithoutData() {
        assertFalse(MiniNav.shown(null))
        assertFalse(MiniNav.shown(NavHint(" ", null, null)))
        assertTrue(MiniNav.shown(NavHint(null, 200, null)))
        assertTrue(MiniNav.shown(NavHint("Sağa dönün", null, null)))
    }

    @Test fun navDistanceFormatting() {
        assertEquals("350" to false, MiniNav.distance(347))
        assertEquals("0" to false, MiniNav.distance(3))
        assertEquals("1,0" to true, MiniNav.distance(996))
        assertEquals("1,2" to true, MiniNav.distance(1_240))
        assertEquals("9,9" to true, MiniNav.distance(9_940))
        assertEquals("12" to true, MiniNav.distance(12_400))
        assertEquals("1.5" to true, MiniNav.distance(1_500, '.'))
        assertNull(MiniNav.distance(-1))
        assertNull(MiniNav.distance(null))
    }

    @Test fun navArrival() {
        assertEquals("14:32", MiniNav.arrival(14 * 60 + 32))
        assertEquals("09:05", MiniNav.arrival(9 * 60 + 5))
        assertEquals("2:32", MiniNav.arrival(14 * 60 + 32, is24 = false))
        assertEquals("12:00", MiniNav.arrival(0, is24 = false))
        assertNull(MiniNav.arrival(24 * 60))
        assertNull(MiniNav.arrival(null))
    }

    // --- Ölçüler ---

    @Test fun halfScreenUnderCaptionKeepsEverythingGlanceable() {
        // Tiggo 7 sol yarı: 960 × 648 pencere, başlık 42 dp örtüyor.
        val m = MiniLayout.forSize(960, 648, captionDp = 42)
        assertFalse(m.wide)
        assertTrue(m.pillText)
        assertTrue(m.coverDp > 0)
        assertFalse("bölünmüşte harita yanda, rota satırı yok", m.showNav)
        assertTrue(m.clockSp >= 56f)
        assertTrue(m.valueSp >= 36f)
        assertTrue("koltuk tuşları rahat dokunulur: ${m.seatDp}", m.seatDp >= 120)
    }

    @Test fun captionShrinksUsableHeight() {
        val free = MiniLayout.forSize(960, 560)
        val covered = MiniLayout.forSize(960, 560, captionDp = 42)
        assertTrue("başlık payı kısa pencere kurallarını tetikler", covered.clockSp < free.clockSp)
        assertEquals(MiniLayout.forSize(960, 518), covered)
    }

    @Test fun touchTargetsNeverBelowMinimum() {
        for (w in listOf(640, 760, 960, 1280, 1707, 1920)) for (h in listOf(440, 520, 610, 648, 720)) for (cap in listOf(0, 42)) {
            val m = MiniLayout.forSize(w, h, cap)
            val at = "$w x $h (başlık $cap)"
            assertTrue(at, m.skipDp >= MiniLayout.MIN_TOUCH_DP)
            assertTrue(at, m.playDp >= MiniLayout.MIN_TOUCH_DP)
            assertTrue(at, m.mediaDp >= MiniLayout.MIN_TOUCH_DP)
            assertTrue(at + " koltuk ${m.seatDp}", m.seatDp >= MiniLayout.MIN_TOUCH_DP)
        }
    }

    @Test fun fullScreenUsesWideColumnsWithRoute() {
        // 1920 × 720 ekran, durum çubuğu ve gezinme çubuğu dışında 1920 × 648.
        val m = MiniLayout.forSize(1920, 648)
        assertTrue(m.wide)
        assertTrue(m.showNav)
        assertEquals(0, m.headerDp)
        assertTrue(m.infoDp in 340..420)
        assertTrue(m.seatsDp in 420..600)
        // Bilgi ve koltuk sütunlarından sonra araç sahnesine en az yarı ekran kalır.
        assertTrue(1920 - m.infoDp - m.seatsDp - 2 * m.paddingDp - 2 * m.gapDp >= 800)
        assertTrue(m.clockSp > MiniLayout.forSize(960, 606).clockSp)
    }

    @Test fun tiggo8TopHalfUsesWideRow() {
        // 2560 x ~700 px @240 dpi = 1707 x ~467 dp
        val m = MiniLayout.forSize(1707, 467)
        assertTrue(m.wide)
        assertTrue(m.coverDp > 0)
        assertTrue(m.skipDp >= MiniLayout.MIN_TOUCH_DP)
        assertTrue(MiniLayout.forSize(1707, 480).wide)
    }

    @Test fun narrowerHalvesStayColumn() {
        assertFalse(MiniLayout.forSize(960, 610).wide)
        assertFalse(MiniLayout.forSize(960, 520).wide)
        assertFalse(MiniLayout.forSize(640, 610).wide)
        assertFalse(MiniLayout.forSize(1280, 720).wide)
        assertFalse(MiniLayout.forSize(1707, 960).wide)
        assertFalse(MiniLayout.isWide(1707, 0))
    }

    @Test fun thirdScreenIsCompactWithIconPills() {
        val m = MiniLayout.forSize(640, 610)
        assertFalse(m.pillText)
        assertTrue(m.clockSp < MiniLayout.forSize(960, 610).clockSp)
        assertTrue(m.seatsDp >= 260)
    }

    @Test fun shortWindowThinsMediaBeforeSeats() {
        val m = MiniLayout.forSize(960, 462, captionDp = 42)
        assertEquals(88, m.mediaDp)
        assertEquals(0, m.coverDp)
        assertTrue(m.seatDp >= MiniLayout.MIN_TOUCH_DP)
    }

    // --- Gerçek navigasyon kaynağı ---

    @Test fun navStateBecomesHint() {
        assertNull(MiniNav.hint(NavigationState.NONE, { "x" }, { "Çıkış $it" }))
        val h = MiniNav.hint(NavigationState.DEMO, { if (it == NavManeuver.RIGHT) "Sağa dönün" else null }, { "Çıkış $it" })!!
        assertEquals("Sağa dönün · Atatürk Cd.", h.maneuver)
        assertEquals(300, h.distanceM)
        assertEquals(14 * 60 + 52, h.arrivalMinutesOfDay)
        assertNull(MiniNav.minutesOfDay("25:00"))
        assertEquals(5, MiniNav.minutesOfDay("0:05"))
    }
}
