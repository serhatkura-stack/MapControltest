package com.mapcontrol.launcher

import com.mapcontrol.launcher.PhoneUiLogic.BannerMode
import com.mapcontrol.launcher.PhoneUiLogic.PanelState
import com.mapcontrol.launcher.PhoneUiLogic.PhoneButtonAction
import com.mapcontrol.launcher.PhoneUiLogic.RelTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Telefon arayüzünün saf kararları. Örnekler yalnızca sahte ad/numara (Ayşe, Mehmet, 0555 000 00 xx). */
class PhoneUiLogicTest {

    private val ayse = PhoneContact("c:1", "Ayşe", "0555 000 00 01", starred = true)
    private val recent = PhoneRecent(RecentType.MISSED, "Mehmet", "0555 000 00 02", 1_000L)

    @Test
    fun panelStateCoversEveryBranch() {
        assertEquals(PanelState.NO_PHONE, PhoneUiLogic.panelState(PhoneSnapshot.EMPTY))
        // Bağlı değilken izin eksikliği de olsa önce "bağlı değil".
        assertEquals(PanelState.NO_PHONE, PhoneUiLogic.panelState(PhoneSnapshot(connected = false, permissionNeeded = true)))
        assertEquals(PanelState.PERMISSION, PhoneUiLogic.panelState(PhoneSnapshot(connected = true, permissionNeeded = true, loaded = true)))
        assertEquals(PanelState.LOADING, PhoneUiLogic.panelState(PhoneSnapshot(connected = true, loaded = false)))
        assertEquals(PanelState.EMPTY, PhoneUiLogic.panelState(PhoneSnapshot(connected = true, loaded = true)))
        assertEquals(PanelState.CONTENT, PhoneUiLogic.panelState(PhoneSnapshot(connected = true, loaded = true, favorites = listOf(ayse))))
        assertEquals(PanelState.CONTENT, PhoneUiLogic.panelState(PhoneSnapshot(connected = true, loaded = true, recents = listOf(recent))))
    }

    @Test
    fun phoneButtonActionFollowsTheRule() {
        // Panel ekrandaysa hiçbir şey (bağlantı ve yoldan bağımsız).
        for (c in listOf(true, false)) for (d in listOf(true, false)) {
            assertEquals(PhoneButtonAction.NONE, PhoneUiLogic.phoneButtonAction(panelOnScreen = true, connected = c, canDial = d))
        }
        assertEquals(PhoneButtonAction.SHEET, PhoneUiLogic.phoneButtonAction(panelOnScreen = false, connected = false, canDial = false))
        assertEquals(PhoneButtonAction.SHEET, PhoneUiLogic.phoneButtonAction(panelOnScreen = false, connected = false, canDial = true))
        assertEquals(PhoneButtonAction.OEM_APP, PhoneUiLogic.phoneButtonAction(panelOnScreen = false, connected = true, canDial = false))
        assertEquals(PhoneButtonAction.SHEET, PhoneUiLogic.phoneButtonAction(panelOnScreen = false, connected = true, canDial = true))
    }

    @Test
    fun bannerModeMapsEveryCallKind() {
        assertEquals(BannerMode.HIDDEN, PhoneUiLogic.bannerMode(CallState.IDLE))
        assertEquals(BannerMode.INCOMING, PhoneUiLogic.bannerMode(CallState(CallKind.INCOMING, "Ayşe", "0555 000 00 01")))
        assertEquals(BannerMode.DIALING, PhoneUiLogic.bannerMode(CallState(CallKind.DIALING)))
        assertEquals(BannerMode.ACTIVE, PhoneUiLogic.bannerMode(CallState(CallKind.ACTIVE, startedAtMs = 5L)))
        assertEquals(BannerMode.ENDED, PhoneUiLogic.bannerMode(CallState(CallKind.ENDED)))
        assertEquals(CallKind.values().size, CallKind.values().map { PhoneUiLogic.bannerMode(CallState(it)) }.toSet().size)
    }

    @Test
    fun formatDurationShapes() {
        assertEquals("0:00", PhoneUiLogic.formatDuration(0))
        assertEquals("0:00", PhoneUiLogic.formatDuration(-4_000))
        assertEquals("0:05", PhoneUiLogic.formatDuration(5_999))
        assertEquals("1:23", PhoneUiLogic.formatDuration(83_000))
        assertEquals("12:00", PhoneUiLogic.formatDuration(720_000))
        assertEquals("59:59", PhoneUiLogic.formatDuration(3_599_000))
        assertEquals("1:00:00", PhoneUiLogic.formatDuration(3_600_000))
        assertEquals("1:02:03", PhoneUiLogic.formatDuration(3_723_000))
    }

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, h: Int, m: Int): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month, day, h, m, 0)
    }.timeInMillis

    @Test
    fun relativeTimeBranches() {
        val now = at(2026, Calendar.NOVEMBER, 6, 15, 30)
        assertEquals(RelTime.Now, PhoneUiLogic.relativeTime(now, now - 30_000, utc))
        // Saat ileri kaymış (gelecekteki kayıt): şimdi.
        assertEquals(RelTime.Now, PhoneUiLogic.relativeTime(now, now + 120_000, utc))
        assertEquals(RelTime.Minutes(1), PhoneUiLogic.relativeTime(now, now - 60_000, utc))
        assertEquals(RelTime.Minutes(12), PhoneUiLogic.relativeTime(now, now - 12 * 60_000, utc))
        assertEquals(RelTime.Minutes(59), PhoneUiLogic.relativeTime(now, now - 59 * 60_000 - 59_000, utc))
        assertEquals(RelTime.Hours(1), PhoneUiLogic.relativeTime(now, now - 3_600_000, utc))
        assertEquals(RelTime.Hours(15), PhoneUiLogic.relativeTime(now, at(2026, Calendar.NOVEMBER, 6, 0, 10), utc))
        assertEquals(RelTime.Yesterday, PhoneUiLogic.relativeTime(now, at(2026, Calendar.NOVEMBER, 5, 23, 50), utc))
        assertEquals(RelTime.Yesterday, PhoneUiLogic.relativeTime(now, at(2026, Calendar.NOVEMBER, 5, 0, 1), utc))
        assertEquals(RelTime.Date(3, Calendar.NOVEMBER), PhoneUiLogic.relativeTime(now, at(2026, Calendar.NOVEMBER, 3, 9, 0), utc))
        // Yıl başı: 1 Ocak'ta 31 Aralık "dün".
        val newYear = at(2027, Calendar.JANUARY, 1, 8, 0)
        assertEquals(RelTime.Yesterday, PhoneUiLogic.relativeTime(newYear, at(2026, Calendar.DECEMBER, 31, 22, 0), utc))
        assertEquals(RelTime.Date(30, Calendar.DECEMBER), PhoneUiLogic.relativeTime(newYear, at(2026, Calendar.DECEMBER, 30, 22, 0), utc))
    }

    @Test
    fun favoriteColumnsFitCellsBetweenMinAndMax() {
        assertEquals(3, PhoneUiLogic.favoriteColumns(0))
        assertEquals(3, PhoneUiLogic.favoriteColumns(300))
        assertEquals(4, PhoneUiLogic.favoriteColumns(392)) // Tiggo 7 panel içi
        assertEquals(5, PhoneUiLogic.favoriteColumns(480))
        assertEquals(6, PhoneUiLogic.favoriteColumns(700)) // sayfa
        assertEquals(6, PhoneUiLogic.favoriteColumns(2000))
        (400..2000 step 37).forEach { w ->
            assertTrue("$w", w / PhoneUiLogic.favoriteColumns(w) >= PhoneUiLogic.FAVORITE_CELL_MIN_DP)
        }
    }

    @Test
    fun recentRowsUseNameOrNumberAndLimit() {
        val rows = PhoneUiLogic.recentRows(listOf(
            recent,
            PhoneRecent(RecentType.INCOMING, null, "0555 000 00 99", 900L),
            PhoneRecent(RecentType.OUTGOING, "  ", "0555 000 00 03", 800L),
            PhoneRecent(RecentType.OUTGOING, "Ayşe", "0555 000 00 01", 700L),
            PhoneRecent(RecentType.MISSED, "Zeynep Kaya", "0555 000 00 06", 600L),
            PhoneRecent(RecentType.INCOMING, "Ev", "0555 000 00 03", 500L),
        ))
        assertEquals(5, rows.size)
        assertEquals("Mehmet", rows[0].label)
        assertTrue(rows[0].missed)
        assertEquals("0555 000 00 99", rows[1].label)
        assertEquals("0555 000 00 03", rows[2].label)
        assertFalse(rows[2].missed)
        assertEquals("0555 000 00 01", rows[3].number)
        assertEquals(2, PhoneUiLogic.recentRows(listOf(recent, recent, recent), max = 2).size)
        assertTrue(PhoneUiLogic.recentRows(emptyList()).isEmpty())
        // Satırın toString'i ad/numara içermez.
        assertFalse(rows[0].toString().contains("Mehmet") || rows[0].toString().contains("0555"))
    }

    @Test
    fun pinningOnlyContactIdsAndToggles() {
        assertTrue(PhoneUiLogic.canPin("c:12"))
        assertTrue(PhoneUiLogic.canPin("v:7"))
        assertFalse(PhoneUiLogic.canPin("n:5550000001"))
        assertFalse(PhoneUiLogic.canPin(""))
        assertEquals(listOf("c:1", "c:2"), PhoneUiLogic.togglePin(listOf("c:1"), "c:2"))
        assertEquals(listOf("c:2"), PhoneUiLogic.togglePin(listOf("c:1", "c:2"), "c:1"))
        assertEquals(emptyList<String>(), PhoneUiLogic.togglePin(listOf("c:1"), "c:1"))
    }

    @Test
    fun everyErrorHasItsOwnText() {
        val ids = PhoneError.values().map { PhoneUiLogic.errorRes(it) }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(PhoneError.values().size, ids.toSet().size)
    }
}
