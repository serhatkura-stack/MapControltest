package com.mapcontrol.launcher

import com.mapcontrol.launcher.HealthMath.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMathTest {
    private fun issues(v: Double? = 13.8, c: Int? = 90, fuel: Boolean? = false, km: Int? = 4000, days: Int? = 150) =
        HealthMath.issues(v, c, fuel, km, days)

    @Test fun allNormalHasNoIssues() = assertTrue(issues().isEmpty())

    @Test fun missingValuesAreNotProblems() {
        assertTrue(issues(null, null, null, null, null).isEmpty())
        assertFalse(HealthMath.hasData(null, null, null, null, null))
        assertTrue(HealthMath.hasData(null, null, null, 10, null))
    }

    @Test fun batteryOutOfRange() {
        assertEquals(listOf(Issue.BATTERY_LOW), issues(v = 11.4))
        assertEquals(listOf(Issue.BATTERY_HIGH), issues(v = 15.6))
        assertTrue(issues(v = 12.0).isEmpty())
        assertTrue(issues(v = 15.0).isEmpty())
    }

    @Test fun serviceSoonAndOverdue() {
        assertEquals(listOf(Issue.SERVICE_SOON), issues(km = 900))
        assertEquals(listOf(Issue.SERVICE_SOON), issues(days = 30))
        assertEquals(listOf(Issue.SERVICE_OVERDUE), issues(km = 0))
        assertEquals(listOf(Issue.SERVICE_OVERDUE), issues(days = 0, km = 500))
        assertTrue(issues(km = 1001, days = 31).isEmpty())
    }

    @Test fun mostImportantFirst() {
        assertEquals(
            listOf(Issue.ENGINE_HOT, Issue.BATTERY_LOW, Issue.LOW_FUEL, Issue.SERVICE_SOON),
            issues(v = 11.0, c = 120, fuel = true, km = 500),
        )
    }

    @Test fun removedWidgetKeysMapToTheirReplacements() {
        assertEquals(WidgetType.HEALTH, WidgetType.fromKey("battery"))
        assertEquals(WidgetType.HEALTH, WidgetType.fromKey("service"))
        assertEquals(WidgetType.TRIP, WidgetType.fromKey("trip_totals"))
        assertEquals(null, WidgetType.fromKey("nonsense"))
        assertTrue(WidgetType.values().none { it.key in HealthMath.REMOVED_KEYS })
    }

    @Test fun savedLayoutWithRemovedKeysResolvesWithoutDuplicates() {
        val raws = listOf(
            RawWidget("health", "health", 0, 0, 2, 2, null),
            RawWidget("battery", "battery", 2, 0, 2, 2, null),
            RawWidget("totals", "trip_totals", 4, 0, 2, 2, null),
        )
        val out = LayoutStore.dedupeSingleInstance(LayoutStore.resolveRaw(raws))
        assertEquals(listOf(WidgetType.HEALTH, WidgetType.TRIP), out.map { it.type })
    }

    @Test fun totalsRowsDropMissingSignals() {
        assertTrue(TripTotals.rows(null, null, null).isEmpty())
        assertEquals(listOf(TripTotals.Kind.ODOMETER, TripTotals.Kind.TIME), TripTotals.rows(100, null, 5).map { it.first })
        assertEquals(47, TripTotals.drivingMinutes(47, 3552))
        assertEquals(3552, TripTotals.drivingMinutes(null, 3552))
        // 151 sıfır dönerse sıradaki dolu sinyal seçilir; hepsi sıfırsa satır düşer ("0 dk" gösterilmez).
        assertEquals(3552, TripTotals.drivingMinutes(0, 3552))
        assertEquals(12, TripTotals.drivingMinutes(0, 0, 12, 5))
        assertEquals(5, TripTotals.drivingMinutes(0, 0, null, 5))
        assertNull(TripTotals.drivingMinutes(0, 0, 0, 0))
        assertEquals(1_000L, TripTotals.accumulateMs(0, 1_000, true))
        assertEquals(0L, TripTotals.accumulateMs(0, 1_000, false))
        assertEquals(0L, TripTotals.accumulateMs(0, 60_000, true))
    }
}
