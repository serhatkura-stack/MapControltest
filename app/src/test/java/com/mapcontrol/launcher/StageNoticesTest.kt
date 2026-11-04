package com.mapcontrol.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aracın altındaki bildirim şeridi: kaynaklar, önem sırası, park tonu ve "+N". */
class StageNoticesTest {

    private fun kinds(l: List<Notice>) = l.map { it.kind }

    @Test
    fun calmCarHasNoNotices() {
        assertTrue(StageNotices.build(null, false, false, false, false, parked = true).isEmpty())
        assertTrue(StageNotices.build(Openings.NONE, false, false, false, false, parked = false).isEmpty())
    }

    @Test
    fun everySourceAppearsInImportanceOrder() {
        val o = Openings(doorFl = true, doorRr = true, trunk = true, hood = true, sunroof = true,
            windows = listOf(BodyPart.WINDOW_LF, BodyPart.WINDOW_RR))
        val l = StageNotices.build(o, fuelCapOpen = true, tireAlert = true, lowFuel = true, serviceNear = true, parked = false)
        assertEquals(
            listOf(NoticeKind.DOOR, NoticeKind.DOOR, NoticeKind.HOOD, NoticeKind.TRUNK, NoticeKind.TIRE, NoticeKind.WINDOW,
                NoticeKind.WINDOW, NoticeKind.SUNROOF, NoticeKind.FUEL_CAP, NoticeKind.FUEL_LOW, NoticeKind.SERVICE),
            kinds(l),
        )
        // Her kapı ve cam ayrı kapsül, parça sırasıyla.
        assertEquals(listOf(BodyPart.DOOR_LF, BodyPart.DOOR_RR), l.filter { it.kind == NoticeKind.DOOR }.map { it.part })
        assertEquals(listOf(BodyPart.WINDOW_LF, BodyPart.WINDOW_RR), l.filter { it.kind == NoticeKind.WINDOW }.map { it.part })
        assertTrue(l.none { it.strong }) // hareket halinde belirgin ton yok
    }

    @Test
    fun parkedTrunkAndWindowsUseStrongToneAndComeFirst() {
        val o = Openings(doorFl = true, trunk = true, windows = listOf(BodyPart.WINDOW_LR))
        val l = StageNotices.build(o, false, tireAlert = true, lowFuel = false, serviceNear = false, parked = true)
        assertEquals(listOf(NoticeKind.TRUNK, NoticeKind.WINDOW, NoticeKind.DOOR, NoticeKind.TIRE), kinds(l))
        assertEquals(listOf(true, true, false, false), l.map { it.strong })
        // Parkta kapı belirgin değil (inip binmek olağan).
        assertTrue(l.first { it.kind == NoticeKind.DOOR }.strong.not())
    }

    @Test
    fun parkedSunroofIsStrongToo() {
        val l = StageNotices.build(Openings(sunroof = true), false, false, false, false, parked = true)
        assertEquals(listOf(Notice(NoticeKind.SUNROOF, strong = true)), l)
    }

    @Test
    fun atMostThreeVisiblePlusCount() {
        val o = Openings(doorFl = true, doorFr = true, trunk = true)
        val all = StageNotices.build(o, false, true, true, true, parked = false)
        assertEquals(6, all.size)
        val (shown, more) = StageNotices.visible(all)
        assertEquals(3, shown.size)
        assertEquals(3, more)
        assertEquals(all.take(3), shown)
        // Genişlik yalnızca 2'ye yetiyorsa 2 + "+4"; hiç yetmiyorsa en önemlisi yine görünür.
        assertEquals(2 to 4, StageNotices.visible(all, fit = 2).let { it.first.size to it.second })
        assertEquals(1 to 5, StageNotices.visible(all, fit = 0).let { it.first.size to it.second })
        // Az bildirimde "+N" yok.
        assertEquals(0, StageNotices.visible(all.take(3)).second)
        assertEquals(0, StageNotices.visible(emptyList<Notice>()).second)
    }

    @Test
    fun stationSearchUris() {
        assertEquals("yandexmaps://maps.yandex.ru/?text=%D0%90%D0%97%D0%A1", NearestStation.yandexUri())
        assertEquals("geo:0,0?q=benzin%20istasyonu", NearestStation.geoUri())
    }
}
