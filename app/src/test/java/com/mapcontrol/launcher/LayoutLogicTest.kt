package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutLogicTest {

    private fun widget(
        id: String,
        type: WidgetType,
        col: Int,
        row: Int,
        span: Span,
    ) = PlacedWidget(id, type, col, row, span)

    @Test
    fun removedWidgetKeysResolveToNull() {
        // LayoutStore.load bu anahtarları atlar (kaldırılan kutucuklar).
        listOf("range", "tires", "doors", "sunroof", "nope").forEach { assertNull(WidgetType.fromKey(it)) }
        assertEquals(WidgetType.LOCK, WidgetType.fromKey("lock"))
    }

    @Test
    fun defaultLayoutIsValid() {
        assertTrue(LayoutStore.isValid(defaultLayout()))
    }

    @Test
    fun overlapsIsTrueForIntersectingWidgets() {
        val media = widget("media", WidgetType.MEDIA, 0, 0, Span(6, 6))
        val nav = widget("nav", WidgetType.AIR, 4, 4, Span(6, 4))
        assertTrue(media.overlaps(nav))
        assertTrue(nav.overlaps(media))
    }

    @Test
    fun overlapsIsFalseForTouchingEdges() {
        val left = widget("left", WidgetType.MEDIA, 0, 0, Span(6, 6))
        val right = widget("right", WidgetType.AIR, 6, 0, Span(6, 4))
        val below = widget("below", WidgetType.CONSUMPTION, 0, 6, Span(2, 2))
        assertFalse(left.overlaps(right))
        assertFalse(right.overlaps(left))
        assertFalse(left.overlaps(below))
        assertFalse(below.overlaps(left))
    }

    @Test
    fun fitsRejectsOutOfBoundsAndAcceptsEdgeFit() {
        // col + w > GRID_COLS (8 + 6 = 14 > 12)
        assertFalse(widget("a", WidgetType.MEDIA, 8, 0, Span(6, 6)).fits())
        // row + h > GRID_ROWS (2 + 6 = 8 > 6)
        assertFalse(widget("b", WidgetType.MEDIA, 0, 2, Span(6, 6)).fits())
        // negative row and negative column
        assertFalse(widget("c", WidgetType.LOCK, 0, -1, Span(2, 2)).fits())
        assertFalse(widget("d", WidgetType.LOCK, -1, 0, Span(2, 2)).fits())
        // exactly touching the far corner is in bounds
        assertTrue(widget("e", WidgetType.MEDIA, 6, 0, Span(6, 6)).fits())
    }

    @Test
    fun isValidRejectsOverlappingWidgets() {
        val layout = listOf(
            widget("media", WidgetType.MEDIA, 0, 0, Span(6, 6)),
            widget("cons", WidgetType.CONSUMPTION, 4, 4, Span(2, 2)),
        )
        assertFalse(LayoutStore.isValid(layout))
    }

    @Test
    fun isValidRejectsSpanNotAllowedForType() {
        // LOCK supports 1x2 only
        val layout = listOf(widget("lock", WidgetType.LOCK, 0, 0, Span(2, 2)))
        assertFalse(LayoutStore.isValid(layout))
    }

    @Test
    fun canPlaceIgnoresWidgetsOwnId() {
        val existing = widget("health", WidgetType.HEALTH, 2, 2, Span(2, 2))
        val layout = listOf(existing)
        // Same id as the one already in the layout: moving it onto its own cell is allowed.
        val moved = existing.copy(col = 2, row = 2)
        assertTrue(LayoutStore.canPlace(layout, moved))
        // A different id on the same cell is not allowed.
        val other = widget("other", WidgetType.LOCK, 2, 2, Span(2, 2))
        assertFalse(LayoutStore.canPlace(layout, other))
    }

    @Test
    fun findSpotReturnsPreferredPositionWhenFree() {
        val layout = listOf(widget("media", WidgetType.MEDIA, 0, 0, Span(6, 6)))
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 2))
        val spot = LayoutStore.findSpot(layout, base, preferCol = 8, preferRow = 0)
        assertEquals(8, spot!!.col)
        assertEquals(0, spot.row)
    }

    @Test
    fun findSpotReturnsFirstFreeSpotWhenPreferredIsTaken() {
        val layout = listOf(widget("media", WidgetType.MEDIA, 0, 0, Span(6, 6)))
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 2))
        // (0,0) is occupied; scanning row by row, left to right, the first free cell is (6,0).
        val spot = LayoutStore.findSpot(layout, base, preferCol = 0, preferRow = 0)
        assertEquals(6, spot!!.col)
        assertEquals(0, spot.row)
    }

    @Test
    fun findSpotReturnsNullWhenGridIsFull() {
        // defaultLayout() covers all 72 cells of the 12x6 grid.
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 2))
        assertNull(LayoutStore.findSpot(defaultLayout(), base))
    }

    @Test
    fun gridIsTwelveBySix() {
        assertEquals(12, GRID_COLS)
        assertEquals(6, GRID_ROWS)
    }

    @Test
    fun addPickerListsEveryRemainingType() {
        assertEquals(WidgetType.values().toList(), addableWidgetTypes())
        assertTrue(WidgetType.VEHICLE in addableWidgetTypes())
    }

    @Test
    fun removedNavigationSpeedLimitAndSteeringKeysAreDroppedOnLoad() {
        listOf("navigation", "speed_limit", "steering_heat").forEach { assertNull(it, WidgetType.fromKey(it)) }
        val out = LayoutStore.resolveRaw(listOf(
            raw("m", "media", 0, 0, 6, 4),
            raw("n", "navigation", 6, 0, 4, 2),
            raw("s", "speed_limit", 6, 2, 2, 2),
            raw("h", "steering_heat", 8, 2, 2, 2),
            raw("l", "lock", 10, 2, 1, 2),
        ))
        assertEquals(listOf(WidgetType.MEDIA, WidgetType.LOCK), out.map { it.type })
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun defaultLayoutsCoverTheWholeGridWithoutOverlap() {
        val area = { l: List<PlacedWidget> -> l.sumOf { it.span.w * it.span.h } }
        listOf(defaultLayout(false), defaultLayout(true)).forEach {
            assertTrue(LayoutStore.isValid(it))
            assertEquals(GRID_COLS * GRID_ROWS, area(it))
        }
    }

    @Test
    fun defaultTopAreaIsMediaTwoTilesAndFourByFourVehicle() {
        listOf(defaultLayout(false), defaultLayout(true)).forEach { layout ->
            fun at(t: WidgetType) = layout.single { it.type == t }
            assertEquals(Triple(0, 0, Span(6, 4)), at(WidgetType.MEDIA).let { Triple(it.col, it.row, it.span) })
            assertEquals(Triple(6, 0, Span(2, 2)), at(WidgetType.CONSUMPTION).let { Triple(it.col, it.row, it.span) })
            assertEquals(Triple(6, 2, Span(2, 2)), at(WidgetType.TRIP).let { Triple(it.col, it.row, it.span) })
            assertEquals(Triple(8, 0, Span(4, 4)), at(WidgetType.VEHICLE).let { Triple(it.col, it.row, it.span) })
            // APP dışında tür tekrarı yok
            val nonApp = layout.filter { it.type != WidgetType.APP }.map { it.type }
            assertEquals(nonApp.size, nonApp.toSet().size)
        }
    }

    @Test
    fun desayDefaultBottomRowIsDriverSeatEightThinAppsPassengerSeat() {
        val layout = defaultLayout(true)
        val bottom = layout.filter { it.row == 4 }.sortedBy { it.col }
        assertEquals(
            listOf(WidgetType.SEAT_HEAT_DRIVER) + List(8) { WidgetType.APP } + WidgetType.SEAT_HEAT_PASSENGER,
            bottom.map { it.type },
        )
        assertEquals(Span(2, 2), bottom.first().span)
        assertEquals(0, bottom.first().col)
        assertTrue(bottom.filter { it.type == WidgetType.APP }.all { it.span == Span(1, 2) })
        assertEquals(Span(2, 2), bottom.last().span)
        assertEquals(10, bottom.last().col)
        // 2 + 8×1 + 2 = 12, boşluksuz
        assertEquals(GRID_COLS, bottom.sumOf { it.span.w })
    }

    @Test
    fun migrationDoublesEveryValueExactly() {
        val old = listOf(
            widget("m", WidgetType.MEDIA, 0, 0, Span(3, 2)),
            widget("v", WidgetType.MEDIA, 3, 0, Span(3, 2)),
            widget("n", WidgetType.AIR, 3, 2, Span(2, 1)),
        )
        val migrated = migrateGridV1(old)
        assertEquals(
            listOf(
                widget("m", WidgetType.MEDIA, 0, 0, Span(6, 4)),
                widget("v", WidgetType.MEDIA, 6, 0, Span(6, 4)),
                widget("n", WidgetType.AIR, 6, 4, Span(4, 2)),
            ),
            migrated,
        )
        assertTrue(LayoutStore.isValid(migrated))
    }

    @Test
    fun migratedDenseOldLayoutStaysValidAndKeepsExtra() {
        val old = listOf(
            widget("service", WidgetType.SERVICE, 0, 0, Span(1, 1)),
            widget("trip", WidgetType.TRIP, 3, 0, Span(2, 1)),
            widget("lock", WidgetType.LOCK, 1, 1, Span(1, 1)),
            widget("seat", WidgetType.SEAT_HEAT_DRIVER, 0, 2, Span(1, 1)),
            PlacedWidget("app", WidgetType.APP, 5, 2, Span(1, 1), "pkg.a"),
        )
        // Çift katı boyutlar artık hep izinli değildir: kayıt yüklenirken snapSpans ile en yakın izinli boyuta çekilir.
        val migrated = LayoutStore.snapSpans(migrateGridV1(old))
        assertTrue(LayoutStore.isValid(migrated))
        assertEquals("pkg.a", migrated.last().extra)
        assertEquals(Span(1, 2), migrated.last().span)
    }

    @Test
    fun everyOldSpanDoubledSnapsToAnAllowedSpan() {
        // Eski 6x3 boyut listeleri: çift katı artık izinli olmayabilir; snapSpans en yakın izinli boyuta çeker.
        val oldSizes = mapOf(
            WidgetType.AIR to listOf(Span(2, 1), Span(1, 1)),
            WidgetType.VEHICLE to listOf(Span(3, 2), Span(2, 2)),
            WidgetType.LOCK to listOf(Span(1, 1)),
            WidgetType.APP to listOf(Span(1, 1)),
            WidgetType.TRIP to listOf(Span(2, 1), Span(1, 1)),
                    )
        oldSizes.forEach { (type, spans) ->
            spans.forEach {
                val out = LayoutStore.snapSpans(listOf(widget("x", type, 0, 0, Span(it.w * 2, it.h * 2))))
                assertEquals("$type $it", 1, out.size)
                assertTrue("$type $it", out.single().span in type.sizes)
            }
        }
    }

    @Test
    fun allowedSpansFollowTheSizeRule() {
        assertEquals(Span(1, 2), WidgetType.APP.sizes.first())
        assertEquals(listOf(Span(1, 2)), WidgetType.APP.sizes)
        assertEquals(listOf(Span(1, 2)), WidgetType.LOCK.sizes)
        assertEquals(listOf(Span(4, 4)), WidgetType.VEHICLE.sizes)
        assertEquals(listOf(Span(2, 2)), WidgetType.CONSUMPTION.sizes)
        listOf(WidgetType.TRIP, WidgetType.HEALTH).forEach { assertEquals("$it", listOf(Span(2, 2), Span(3, 2)), it.sizes) }
        listOf(WidgetType.DRIVE_MODE, WidgetType.WEATHER, WidgetType.SEAT_HEAT_DRIVER, WidgetType.SEAT_VENT_DRIVER,
            WidgetType.SEAT_HEAT_PASSENGER, WidgetType.SEAT_VENT_PASSENGER)
            .forEach { assertEquals("$it", listOf(Span(2, 2)), it.sizes) }
        assertEquals(listOf(Span(2, 2), Span(3, 2)), WidgetType.SERVICE.sizes)
        assertEquals(listOf(Span(2, 2), Span(4, 2)), WidgetType.AIR.sizes)
        assertEquals(listOf(Span(4, 2), Span(6, 4)), WidgetType.MEDIA.sizes)
    }

    @Test
    fun snapMovesRemovedSpansToTheirOnlyAllowedSizeKeepingSlot() {
        val out = LayoutStore.snapSpans(listOf(
            widget("a", WidgetType.APP, 2, 4, Span(2, 2)),
            widget("zz", WidgetType.WEATHER, 0, 4, Span(2, 2)),
            widget("v", WidgetType.VEHICLE, 6, 0, Span(6, 4)),
            widget("c", WidgetType.CONSUMPTION, 0, 0, Span(4, 2)),
            widget("t", WidgetType.TRIP, 0, 2, Span(4, 2)),
            widget("h", WidgetType.HEALTH, 4, 4, Span(4, 2)),
        ))
        assertEquals(Triple(2, 4, Span(1, 2)), out.first { it.id == "a" }.let { Triple(it.col, it.row, it.span) })
        assertEquals(Triple(6, 0, Span(4, 4)), out.first { it.id == "v" }.let { Triple(it.col, it.row, it.span) })
        assertEquals(Span(2, 2), out.first { it.id == "c" }.span)
        assertEquals(Span(3, 2), out.first { it.id == "t" }.span)
        assertEquals(Span(3, 2), out.first { it.id == "h" }.span)
    }

    @Test
    fun snapShrinksLockToOneByTwoInPlace() {
        val out = LayoutStore.snapSpans(listOf(widget("l", WidgetType.LOCK, 6, 4, Span(2, 2))))
        assertEquals(listOf(widget("l", WidgetType.LOCK, 6, 4, Span(1, 2))), out)
    }

    @Test
    fun snapMediaEightBySixAndSixBySixBecomeSixByFour() {
        listOf(Span(8, 6), Span(6, 6)).forEach {
            val out = LayoutStore.snapSpans(listOf(widget("m", WidgetType.MEDIA, 0, 0, it)))
            assertEquals(listOf(widget("m", WidgetType.MEDIA, 0, 0, Span(6, 4))), out)
        }
    }

    @Test
    fun snapAirThreeByTwoBecomesTwoByTwo() {
        val out = LayoutStore.snapSpans(listOf(widget("a", WidgetType.AIR, 2, 2, Span(3, 2))))
        assertEquals(listOf(widget("a", WidgetType.AIR, 2, 2, Span(2, 2))), out)
    }

    @Test
    fun snapLeavesValidLayoutIdenticalAndDefaultsAreStable() {
        listOf(defaultLayout(false), defaultLayout(true)).forEach { assertEquals(it, LayoutStore.snapSpans(it)) }
    }

    @Test
    fun snapUsesFindSpotWhenSmallestDoesNotFitAndDropsWhenNoRoom() {
        // 1x1 benzeri küçük kayıt: en küçük izinli (2x2) komşuyla çakışır, başka yere taşınır.
        val moved = LayoutStore.snapSpans(listOf(
            widget("a", WidgetType.APP, 0, 0, Span(1, 2)),
            widget("l", WidgetType.LOCK, 0, 0, Span(1, 1)),
        ))
        assertEquals(2, moved.size)
        assertTrue(LayoutStore.isValid(moved))
        // Izgara tamamen dolu: yer kalmayan tek widget düşer, diğerleri aynen kalır.
        val base = defaultLayout(false)
        val out = LayoutStore.snapSpans(base.filter { it.id != "lock" } + widget("y", WidgetType.LOCK, 6, 4, Span(1, 1)))
        assertEquals(base.size, out.size)
        val full = LayoutStore.snapSpans(base + widget("z", WidgetType.CONSUMPTION, 0, 0, Span(1, 1)))
        assertEquals(base, full)
    }

    @Test
    fun narrowAppTilesPackSixSideBySide() {
        val apps = List(6) { PlacedWidget("a$it", WidgetType.APP, it * 2, 0, Span(1, 2)) }
        // aralarında bir hücre boşluk bırakıldığında geçerli; bitişik 1x2 kutucuklar da çakışmaz
        assertTrue(LayoutStore.isValid(apps))
        val packed = List(12) { PlacedWidget("a$it", WidgetType.APP, it, 0, Span(1, 2)) }
        assertTrue(LayoutStore.isValid(packed))
    }

    private fun raw(id: String, key: String, col: Int, row: Int, w: Int, h: Int) = RawWidget(id, key, col, row, w, h, null)

    @Test
    fun legacySeatSingleBecomesDriver() {
        val out = LayoutStore.resolveRaw(listOf(raw("v", "seat_vent", 0, 0, 2, 2)))
        assertEquals(listOf(widget("v", WidgetType.SEAT_VENT_DRIVER, 0, 0, Span(2, 2))), out)
    }

    @Test
    fun legacySeatBothSeatsSplitsIntoDriverAndPassenger() {
        val out = LayoutStore.resolveRaw(listOf(raw("seat_heat", "seat_heat", 4, 4, 4, 2)))
        assertEquals(
            listOf(
                widget("seat_heat", WidgetType.SEAT_HEAT_DRIVER, 4, 4, Span(2, 2)),
                widget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 6, 4, Span(2, 2)),
            ),
            out,
        )
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun legacySeatBothSeatsKeepsOnlyDriverWhenNeighbourIsTaken() {
        // Sağdaki hücreleri sonradan gelen bir widget tutuyor: yolcu eklenmez.
        val out = LayoutStore.resolveRaw(listOf(
            raw("s", "seat_heat", 0, 4, 4, 2),
            raw("n", "air", 2, 4, 4, 2),
        ))
        assertEquals(listOf(WidgetType.SEAT_HEAT_DRIVER, WidgetType.AIR), out.map { it.type })
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun legacySeatMigrationIsIdempotent() {
        val once = LayoutStore.resolveRaw(listOf(raw("s", "seat_vent", 0, 0, 4, 2)))
        val again = LayoutStore.resolveRaw(once.map { raw(it.id, it.type.key, it.col, it.row, it.span.w, it.span.h) })
        assertEquals(once, again)
    }

    @Test
    fun saveKeepsHiddenParkOnlyWidgets() {
        val saved = listOf(
            widget("media", WidgetType.MEDIA, 0, 0, Span(4, 2)),
            widget("photo", WidgetType.PHOTO, 4, 0, Span(2, 2)),
            widget("clock", WidgetType.CLOCK, 6, 0, Span(2, 2)),
        )
        val shown = visibleForMode(saved, LayoutMode.DRIVE)
        val hidden = saved.filter { it !in shown }
        val edited = listOf(widget("media", WidgetType.MEDIA, 0, 2, Span(4, 2)))
        val out = LayoutStore.mergeHidden(edited, hidden)
        assertEquals(setOf("media", "photo", "clock"), out.map { it.id }.toSet())
        assertEquals(hidden, out.filter { it.id != "media" })
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun saveRelocatesHiddenWidgetWhenEditedLayoutCoversIt() {
        val hidden = listOf(widget("photo", WidgetType.PHOTO, 4, 0, Span(2, 2)))
        val edited = listOf(widget("media", WidgetType.MEDIA, 4, 0, Span(4, 2)))
        val out = LayoutStore.mergeHidden(edited, hidden)
        assertEquals(2, out.size)
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun dedupeKeepsFirstSingleInstanceAndAllApps() {
        val layout = listOf(
            widget("a", WidgetType.MEDIA, 0, 0, Span(4, 2)),
            widget("b", WidgetType.MEDIA, 4, 0, Span(4, 2)),
            widget("app1", WidgetType.APP, 0, 4, Span(1, 2)),
            widget("app2", WidgetType.APP, 1, 4, Span(1, 2)),
            widget("sd", WidgetType.SEAT_HEAT_DRIVER, 8, 0, Span(2, 2)),
            widget("sp", WidgetType.SEAT_HEAT_PASSENGER, 10, 0, Span(2, 2)),
        )
        assertEquals(listOf("a", "app1", "app2", "sd", "sp"), LayoutStore.dedupeSingleInstance(layout).map { it.id })
    }

    @Test
    fun addableOffersOnlyAppWhenAlreadyPlaced() {
        val layout = listOf(widget("m", WidgetType.MEDIA, 0, 0, Span(4, 2)), widget("a", WidgetType.APP, 4, 0, Span(1, 2)))
        val offered = addableWidgetTypes(layout)
        assertFalse(WidgetType.MEDIA in offered)
        assertTrue(WidgetType.APP in offered)
        assertTrue(WidgetType.SEAT_HEAT_DRIVER in offered)
    }
}
