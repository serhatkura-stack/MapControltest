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
        val media = widget("media", WidgetType.MEDIA, 0, 0, Span(3, 3))
        val nav = widget("nav", WidgetType.NAVIGATION, 2, 2, Span(3, 2))
        assertTrue(media.overlaps(nav))
        assertTrue(nav.overlaps(media))
    }

    @Test
    fun overlapsIsFalseForTouchingEdges() {
        val left = widget("left", WidgetType.MEDIA, 0, 0, Span(3, 3))
        val right = widget("right", WidgetType.NAVIGATION, 3, 0, Span(3, 2))
        val below = widget("below", WidgetType.CONSUMPTION, 0, 3, Span(1, 1))
        assertFalse(left.overlaps(right))
        assertFalse(right.overlaps(left))
        assertFalse(left.overlaps(below))
        assertFalse(below.overlaps(left))
    }

    @Test
    fun fitsRejectsOutOfBoundsAndAcceptsEdgeFit() {
        // col + w > GRID_COLS (4 + 3 = 7 > 6)
        assertFalse(widget("a", WidgetType.MEDIA, 4, 0, Span(3, 3)).fits())
        // row + h > GRID_ROWS (1 + 3 = 4 > 3)
        assertFalse(widget("b", WidgetType.MEDIA, 0, 1, Span(3, 3)).fits())
        // negative row and negative column
        assertFalse(widget("c", WidgetType.LOCK, 0, -1, Span(1, 1)).fits())
        assertFalse(widget("d", WidgetType.LOCK, -1, 0, Span(1, 1)).fits())
        // exactly touching the far corner is in bounds
        assertTrue(widget("e", WidgetType.MEDIA, 3, 0, Span(3, 3)).fits())
    }

    @Test
    fun isValidRejectsOverlappingWidgets() {
        val layout = listOf(
            widget("media", WidgetType.MEDIA, 0, 0, Span(3, 3)),
            widget("cons", WidgetType.CONSUMPTION, 2, 2, Span(1, 1)),
        )
        assertFalse(LayoutStore.isValid(layout))
    }

    @Test
    fun isValidRejectsSpanNotAllowedForType() {
        // LOCK only supports 1x1
        val layout = listOf(widget("lock", WidgetType.LOCK, 0, 0, Span(2, 2)))
        assertFalse(LayoutStore.isValid(layout))
    }

    @Test
    fun canPlaceIgnoresWidgetsOwnId() {
        val existing = widget("health", WidgetType.HEALTH, 1, 1, Span(1, 1))
        val layout = listOf(existing)
        // Same id as the one already in the layout: moving it onto its own cell is allowed.
        val moved = existing.copy(col = 1, row = 1)
        assertTrue(LayoutStore.canPlace(layout, moved))
        // A different id on the same cell is not allowed.
        val other = widget("other", WidgetType.LOCK, 1, 1, Span(1, 1))
        assertFalse(LayoutStore.canPlace(layout, other))
    }

    @Test
    fun findSpotReturnsPreferredPositionWhenFree() {
        val layout = listOf(widget("media", WidgetType.MEDIA, 0, 0, Span(3, 3)))
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 1))
        val spot = LayoutStore.findSpot(layout, base, preferCol = 4, preferRow = 0)
        assertEquals(4, spot!!.col)
        assertEquals(0, spot.row)
    }

    @Test
    fun findSpotReturnsFirstFreeSpotWhenPreferredIsTaken() {
        val layout = listOf(widget("media", WidgetType.MEDIA, 0, 0, Span(3, 3)))
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 1))
        // (0,0) is occupied; scanning row by row, left to right, the first free cell is (3,0).
        val spot = LayoutStore.findSpot(layout, base, preferCol = 0, preferRow = 0)
        assertEquals(3, spot!!.col)
        assertEquals(0, spot.row)
    }

    @Test
    fun findSpotReturnsNullWhenGridIsFull() {
        // defaultLayout() covers all 18 cells of the 6x3 grid.
        val base = widget("app", WidgetType.APP, 0, 0, Span(1, 1))
        assertNull(LayoutStore.findSpot(defaultLayout(), base))
    }
}
