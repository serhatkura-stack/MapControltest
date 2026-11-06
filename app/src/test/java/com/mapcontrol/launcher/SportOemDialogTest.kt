package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportOemDialogTest {

    private val N = DriveAccent.NORMAL
    private val S = DriveAccent.SPORT
    private val E = DriveAccent.ECO

    // --- OEM sürüş modu penceresi kapatma ---

    @Test fun firstObservationIsNotAChange() {
        val c = OemDriveDialogCloser(300)
        assertFalse(c.update(0, S, allowed = true, sportShown = false))
        assertFalse(c.update(1_000, S, allowed = true, sportShown = false))
    }

    @Test fun closesOnceAfterDelayWhenEnteringSport() {
        val c = OemDriveDialogCloser(300)
        assertFalse(c.update(0, N, true, false))
        assertFalse(c.update(1_000, S, true, false))
        assertFalse(c.update(1_200, S, true, false))
        assertTrue(c.update(1_300, S, true, false))
        // Aynı değişim için bir daha gönderilmez.
        assertFalse(c.update(1_800, S, true, false))
        assertFalse(c.update(5_000, S, true, true))
    }

    @Test fun exitFromShownSportAlsoCloses() {
        val c = OemDriveDialogCloser(300)
        c.update(0, S, true, true)
        assertFalse(c.update(100, N, true, true))
        assertTrue(c.update(500, N, true, true))
    }

    @Test fun nonSportChangesAndHiddenSportExitDoNothing() {
        val c = OemDriveDialogCloser(300)
        c.update(0, N, true, false)
        c.update(100, E, true, false)
        assertFalse(c.update(1_000, E, true, false))
        // Spor ekranı açılmadan Spor'dan çıkılırsa (geçiş yok) yayın da yok.
        c.update(2_000, S, true, false)
        assertTrue(c.update(2_300, S, true, false))
        c.update(2_400, N, true, false)
        assertFalse(c.update(3_000, N, true, false))
    }

    @Test fun disallowedNeverSendsAndCancelsPending() {
        val c = OemDriveDialogCloser(300)
        c.update(0, N, false, false)
        c.update(100, S, false, false)
        assertFalse(c.update(1_000, S, false, false))
        // Bekleyen varken seçenek kapanırsa iptal; açılınca eski değişim için gönderilmez.
        c.update(2_000, N, true, false)
        c.update(2_100, S, true, false)
        assertFalse(c.update(2_200, S, false, false))
        assertFalse(c.update(3_000, S, true, false))
    }

    @Test fun dataGapKeepsLastModeAndNewChangeReplacesPending() {
        val c = OemDriveDialogCloser(300)
        c.update(0, N, true, false)
        assertFalse(c.update(100, null, true, false))
        // null sonrası aynı mod değişim sayılmaz.
        assertFalse(c.update(200, N, true, false))
        assertFalse(c.update(1_000, S, true, false))
        // Pencere açıkken hızlıca başka moda geçildi: yalnız son değişim, kendi gecikmesiyle.
        assertFalse(c.update(1_100, E, true, false))
        assertFalse(c.update(1_400, E, true, false))
        c.update(1_500, S, true, false)
        assertFalse(c.update(1_700, S, true, false))
        assertTrue(c.update(1_800, null, true, false))
    }

    // --- Dış sıcaklık kadranı ---

    @Test fun outsideDialFormatAndChoice() {
        assertEquals("17", SportFormat.outside(17.0))
        assertEquals("18", SportFormat.outside(17.5))
        assertEquals("-3", SportFormat.outside(-3.4))
        assertNull(SportFormat.outside(null))
        assertEquals(SportDial.OUTSIDE, SportDial.of("out"))
        assertTrue(SportDial.OUTSIDE in SportDials.offered(false))
        assertEquals(listOf(SportDial.OUTSIDE, SportDial.G, SportDial.INSTANT, SportDial.MAX), SportDials.decode("out,g,inst,max", true))
        // Varsayılan dört kadran değişmedi (dış sıcaklık seçilerek eklenir).
        assertFalse(SportDial.OUTSIDE in SportDials.defaults(true))
        assertEquals("°C", SportGeometry.unit(SportDial.OUTSIDE))
        assertEquals("Dış", SportGeometry.label(SportDial.OUTSIDE))
    }
}
