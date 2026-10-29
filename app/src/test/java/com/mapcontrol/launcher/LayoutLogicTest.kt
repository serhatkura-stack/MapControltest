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
        val nav = widget("nav", WidgetType.NAVIGATION, 4, 4, Span(6, 4))
        assertTrue(media.overlaps(nav))
        assertTrue(nav.overlaps(media))
    }

    @Test
    fun overlapsIsFalseForTouchingEdges() {
        val left = widget("left", WidgetType.MEDIA, 0, 0, Span(6, 6))
        val right = widget("right", WidgetType.NAVIGATION, 6, 0, Span(6, 4))
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
        // LOCK supports 2x2 and 3x2 only
        val layout = listOf(widget("lock", WidgetType.LOCK, 0, 0, Span(4, 2)))
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
    fun addPickerHidesNavigationWhileDisabled() {
        assertTrue(WidgetType.NAVIGATION !in addableWidgetTypes())
        assertTrue(WidgetType.VEHICLE in addableWidgetTypes())
        // Tür ve kayıtlı navigasyon widget'ları yine de yüklenir.
        assertEquals(WidgetType.NAVIGATION, WidgetType.fromKey("navigation"))
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
    fun desayDefaultBottomRowIsDriverSeatFourAppsPassengerSeat() {
        // Navigasyon kartı gizliyken (NAVIGATION_CARD_ENABLED = false) uygulama şeridi boşluğu doldurur.
        assertTrue(!NAVIGATION_CARD_ENABLED)
        val layout = defaultLayout(true)
        assertTrue(layout.none { it.type == WidgetType.NAVIGATION })
        val bottom = layout.filter { it.row == 4 }.sortedBy { it.col }
        assertEquals(
            listOf(WidgetType.SEAT_HEAT_DRIVER) + List(4) { WidgetType.APP } + WidgetType.SEAT_HEAT_PASSENGER,
            bottom.map { it.type },
        )
        assertEquals(Span(2, 2), bottom.first().span)
        assertEquals(0, bottom.first().col)
        assertTrue(bottom.filter { it.type == WidgetType.APP }.all { it.span == Span(2, 2) })
        assertEquals(Span(2, 2), bottom.last().span)
        assertEquals(10, bottom.last().col)
        // 2 + 8 + 2 = 12, boşluksuz
        assertEquals(GRID_COLS, bottom.sumOf { it.span.w })
    }

    @Test
    fun migrationDoublesEveryValueExactly() {
        val old = listOf(
            widget("m", WidgetType.MEDIA, 0, 0, Span(3, 3)),
            widget("v", WidgetType.VEHICLE, 3, 0, Span(3, 2)),
            widget("n", WidgetType.NAVIGATION, 3, 2, Span(3, 1)),
        )
        val migrated = migrateGridV1(old)
        assertEquals(
            listOf(
                widget("m", WidgetType.MEDIA, 0, 0, Span(6, 6)),
                widget("v", WidgetType.VEHICLE, 6, 0, Span(6, 4)),
                widget("n", WidgetType.NAVIGATION, 6, 4, Span(6, 2)),
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
        val migrated = migrateGridV1(old)
        assertTrue(LayoutStore.isValid(migrated))
        assertEquals("pkg.a", migrated.last().extra)
        assertEquals(Span(2, 2), migrated.last().span)
    }

    @Test
    fun everyOldSpanDoubledIsAllowedForItsType() {
        // Eski 6x3 boyut listeleri: her tür için çift katı yeni listede bulunmalı.
        val oldSizes = mapOf(
            WidgetType.MEDIA to listOf(Span(3, 3), Span(3, 2), Span(2, 1), Span(4, 3)),
            WidgetType.NAVIGATION to listOf(Span(3, 2), Span(3, 3), Span(3, 1), Span(2, 1)),
            WidgetType.VEHICLE to listOf(Span(3, 2), Span(2, 2)),
            WidgetType.LOCK to listOf(Span(1, 1)),
            WidgetType.APP to listOf(Span(1, 1)),
            WidgetType.TRIP to listOf(Span(2, 1), Span(1, 1)),
                    )
        oldSizes.forEach { (type, spans) ->
            spans.forEach { assertTrue("$type $it", Span(it.w * 2, it.h * 2) in type.sizes) }
        }
    }

    @Test
    fun finerSpansExist() {
        assertEquals(Span(1, 2), WidgetType.APP.sizes.first())
        assertTrue(Span(2, 2) in WidgetType.APP.sizes)
        listOf(WidgetType.CONSUMPTION, WidgetType.TRIP, WidgetType.HEALTH, WidgetType.WEATHER, WidgetType.SERVICE,
            WidgetType.AIR, WidgetType.LOCK, WidgetType.DRIVE_MODE, WidgetType.SEAT_HEAT_DRIVER, WidgetType.SEAT_VENT_DRIVER, WidgetType.SEAT_HEAT_PASSENGER, WidgetType.SEAT_VENT_PASSENGER)
            .forEach { assertTrue("$it", Span(3, 2) in it.sizes && Span(2, 2) in it.sizes) }
        // Medya / araç / navigasyon: daha küçük asgari boyut yok (en küçüğü eski 2x1 = 4x2).
        assertTrue(WidgetType.MEDIA.sizes.all { it.w >= 4 && it.h >= 2 })
        assertTrue(WidgetType.VEHICLE.sizes.all { it.w >= 4 && it.h >= 4 })
        assertTrue(WidgetType.NAVIGATION.sizes.all { it.w >= 4 && it.h >= 2 })
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
            raw("n", "navigation", 2, 4, 4, 2),
        ))
        assertEquals(listOf(WidgetType.SEAT_HEAT_DRIVER, WidgetType.NAVIGATION), out.map { it.type })
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun legacySeatMigrationIsIdempotent() {
        val once = LayoutStore.resolveRaw(listOf(raw("s", "seat_vent", 0, 0, 4, 2)))
        val again = LayoutStore.resolveRaw(once.map { raw(it.id, it.type.key, it.col, it.row, it.span.w, it.span.h) })
        assertEquals(once, again)
    }
}
