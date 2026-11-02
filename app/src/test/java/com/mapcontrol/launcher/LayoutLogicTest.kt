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
    fun parkDefaultIsExactlyTheDesignedLayoutForBothBars() {
        listOf(false, true).forEach { oem ->
            val l = defaultLayout(oem, List(8) { "p$it" })
            val got = l.map { "${it.type.key}@${it.col},${it.row}:${it.span}" }.sorted()
            val want = listOf(
                "vehicle@0,0:4×4", "media@4,0:4×2", "health@4,2:2×2", "air@6,2:2×2", "trip@8,0:4×4",
                "seat_driver@0,4:2×2", "trunk@2,4:2×2", "rear_speaker@4,4:2×2", "lock@6,4:1×2", "screen_off@7,4:1×2",
                "app@8,4:1×2", "app@9,4:1×2", "seat_passenger@10,4:2×2",
            ).sorted()
            assertEquals("oem=$oem", want, got)
            // Uygulama kutucukları sırayla kurulu varsayılanlarla dolar; APP dışında tür tekrarı yok.
            assertEquals(listOf("p0", "p1"), l.filter { it.type == WidgetType.APP }.sortedBy { it.col }.map { it.extra })
            val nonApp = l.filter { it.type != WidgetType.APP }.map { it.type }
            assertEquals(nonApp.size, nonApp.toSet().size)
        }
    }

    /** Varsayılan düzen kuralları: çakışma yok, boşluk yok, her boyut izinli, mod kuralına uygun. */
    private fun assertCleanDefault(name: String, l: List<PlacedWidget>, mode: LayoutMode) {
        assertTrue("$name geçerli", LayoutStore.isValid(l))
        l.forEach { w -> assertTrue("$name ${w.type} ${w.span} izinli", w.span in w.type.sizes) }
        for (i in l.indices) for (j in i + 1 until l.size) assertTrue("$name çakışma ${l[i].id}/${l[j].id}", !l[i].overlaps(l[j]))
        for (r in 0 until GRID_ROWS) for (c in 0 until GRID_COLS) {
            assertTrue("$name boş hücre $c,$r", l.any { c in it.col until it.col + it.span.w && r in it.row until it.row + it.span.h })
        }
        assertTrue("$name kaldırılmış tür yok", l.none { it.type.key in setOf("sunshade", "defrost") })
    }

    @Test
    fun allDefaultsHaveNoOverlapNoHolesAndOnlyAllowedSpans() {
        listOf(false, true).forEach { oem ->
            assertCleanDefault("park oem=$oem", defaultLayout(oem, List(8) { "p$it" }), LayoutMode.PARK)
            assertCleanDefault("sürüş oem=$oem", defaultDriveLayout(oem, List(8) { "p$it" }), LayoutMode.DRIVE)
        }
    }

    @Test
    fun driveDefaultIsSmallAndSeatsSitAtTheReachableEdges() {
        listOf(false, true).forEach { oem ->
            val l = defaultDriveLayout(oem)
            assertTrue(l.size <= 7)
            assertTrue(l.none { it.type == WidgetType.APP })
            val d = l.single { it.type == WidgetType.SEAT_DRIVER }
            val p = l.single { it.type == WidgetType.SEAT_PASSENGER }
            assertEquals(0, d.col)
            assertEquals(GRID_COLS, p.col + p.span.w)
        }
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
            widget("service", WidgetType.HEALTH, 0, 0, Span(1, 1)),
            widget("trip", WidgetType.TRIP, 3, 0, Span(2, 1)),
            widget("lock", WidgetType.LOCK, 1, 1, Span(1, 1)),
            widget("seat", WidgetType.SEAT_DRIVER, 0, 2, Span(1, 1)),
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
        assertEquals(listOf(Span(2, 2), Span(3, 2), Span(4, 4)), WidgetType.TRIP.sizes)
        assertEquals(listOf(Span(2, 2), Span(4, 4)), WidgetType.HEALTH.sizes)
        listOf(WidgetType.DRIVE_MODE, WidgetType.CONSUMPTION)
            .forEach { assertEquals("$it", listOf(Span(2, 2)), it.sizes) }
        listOf(WidgetType.SEAT_DRIVER, WidgetType.SEAT_PASSENGER)
            .forEach { assertEquals("$it", listOf(Span(2, 2), Span(4, 4)), it.sizes) }
        assertEquals(listOf(Span(2, 2), Span(4, 2)), WidgetType.AIR.sizes)
        assertEquals(listOf(Span(4, 2), Span(6, 4), Span(6, 6)), WidgetType.MEDIA.sizes)
    }

    @Test
    fun snapMovesRemovedSpansToTheirOnlyAllowedSizeKeepingSlot() {
        val out = LayoutStore.snapSpans(listOf(
            widget("a", WidgetType.APP, 2, 4, Span(2, 2)),
            widget("zz", WidgetType.DRIVE_MODE, 0, 4, Span(2, 2)),
            widget("v", WidgetType.VEHICLE, 6, 0, Span(6, 4)),
            widget("c", WidgetType.CONSUMPTION, 0, 0, Span(4, 2)),
            widget("t", WidgetType.TRIP, 0, 2, Span(4, 2)),
            widget("h", WidgetType.HEALTH, 4, 4, Span(4, 2)),
        ))
        assertEquals(Triple(2, 4, Span(1, 2)), out.first { it.id == "a" }.let { Triple(it.col, it.row, it.span) })
        assertEquals(Triple(6, 0, Span(4, 4)), out.first { it.id == "v" }.let { Triple(it.col, it.row, it.span) })
        assertEquals(Span(2, 2), out.first { it.id == "c" }.span)
        assertEquals(Span(3, 2), out.first { it.id == "t" }.span)
        assertEquals(Span(2, 2), out.first { it.id == "h" }.span)
    }

    @Test
    fun snapShrinksLockToOneByTwoInPlace() {
        val out = LayoutStore.snapSpans(listOf(widget("l", WidgetType.LOCK, 6, 4, Span(2, 2))))
        assertEquals(listOf(widget("l", WidgetType.LOCK, 6, 4, Span(1, 2))), out)
    }

    @Test
    fun snapMediaEightBySixBecomesSixBySixWhichIsKept() {
        listOf(Span(8, 6), Span(6, 6)).forEach {
            val out = LayoutStore.snapSpans(listOf(widget("m", WidgetType.MEDIA, 0, 0, it)))
            assertEquals(listOf(widget("m", WidgetType.MEDIA, 0, 0, Span(6, 6))), out)
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
        val out = LayoutStore.snapSpans(base.filter { it.id != "air" } + widget("y", WidgetType.LOCK, 6, 2, Span(1, 1)))
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
        assertEquals(listOf(widget("v", WidgetType.SEAT_DRIVER, 0, 0, Span(2, 2))), out)
    }

    @Test
    fun legacySeatBothSeatsSplitsIntoDriverAndPassenger() {
        val out = LayoutStore.resolveRaw(listOf(raw("seat_heat", "seat_heat", 4, 4, 4, 2)))
        assertEquals(
            listOf(
                widget("seat_heat", WidgetType.SEAT_DRIVER, 4, 4, Span(2, 2)),
                widget("seat_heat_passenger", WidgetType.SEAT_PASSENGER, 6, 4, Span(2, 2)),
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
        assertEquals(listOf(WidgetType.SEAT_DRIVER, WidgetType.AIR), out.map { it.type })
        assertTrue(LayoutStore.isValid(out))
    }

    @Test
    fun legacySeatMigrationIsIdempotent() {
        val once = LayoutStore.resolveRaw(listOf(raw("s", "seat_vent", 0, 0, 4, 2)))
        val again = LayoutStore.resolveRaw(once.map { raw(it.id, it.type.key, it.col, it.row, it.span.w, it.span.h) })
        assertEquals(once, again)
    }

    @Test
    fun separateHeatAndVentSeatsMergeIntoOneSeatPerSide() {
        // Eski kayıt: sürücü ısıtma + sürücü soğutma + yolcu soğutma. Taraf başına tek birleşik koltuk kalır (ilki).
        val raws = listOf(
            raw("hd", "seat_heat_driver", 0, 4, 2, 2),
            raw("vd", "seat_vent_driver", 2, 4, 2, 2),
            raw("vp", "seat_vent_passenger", 10, 4, 2, 2),
        )
        val out = LayoutStore.dedupeSingleInstance(LayoutStore.resolveRaw(raws))
        assertEquals(listOf("hd" to WidgetType.SEAT_DRIVER, "vp" to WidgetType.SEAT_PASSENGER), out.map { it.id to it.type })
        assertTrue(LayoutStore.isValid(out))
        assertTrue(LayoutStore.needsRewrite(raws))
    }

    @Test
    fun removedSunshadeAndDefrostAreDroppedAndCellFreed() {
        val raws = listOf(
            raw("m", "media", 0, 0, 4, 2),
            raw("s", "sunshade", 4, 0, 2, 2),
            raw("d", "defrost", 6, 0, 2, 2),
        )
        val out = LayoutStore.resolveRaw(raws)
        assertEquals(listOf("m"), out.map { it.id })
        assertTrue(LayoutStore.needsRewrite(raws))
        // Boşalan hücreye yeni widget konabilir.
        assertTrue(LayoutStore.canPlace(out, widget("t", WidgetType.TRUNK, 4, 0, Span(2, 2))))
        assertFalse(LayoutStore.needsRewrite(listOf(raw("m", "media", 0, 0, 4, 2))))
    }

    @Test
    fun dedupeKeepsFirstSingleInstanceAndAllApps() {
        val layout = listOf(
            widget("a", WidgetType.MEDIA, 0, 0, Span(4, 2)),
            widget("b", WidgetType.MEDIA, 4, 0, Span(4, 2)),
            widget("app1", WidgetType.APP, 0, 4, Span(1, 2)),
            widget("app2", WidgetType.APP, 1, 4, Span(1, 2)),
            widget("sd", WidgetType.SEAT_DRIVER, 8, 0, Span(2, 2)),
            widget("sp", WidgetType.SEAT_PASSENGER, 10, 0, Span(2, 2)),
        )
        assertEquals(listOf("a", "app1", "app2", "sd", "sp"), LayoutStore.dedupeSingleInstance(layout).map { it.id })
    }

    @Test
    fun addableOffersOnlyAppWhenAlreadyPlaced() {
        val layout = listOf(widget("m", WidgetType.MEDIA, 0, 0, Span(4, 2)), widget("a", WidgetType.APP, 4, 0, Span(1, 2)))
        val offered = addableWidgetTypes(layout)
        assertFalse(WidgetType.MEDIA in offered)
        assertTrue(WidgetType.APP in offered)
        assertTrue(WidgetType.SEAT_DRIVER in offered)
    }
}
