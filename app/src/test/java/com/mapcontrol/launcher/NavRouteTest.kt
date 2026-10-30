package com.mapcontrol.launcher

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavRouteTest {

    private val p = NavPoint(41.0082, 28.9784)

    @Test fun formatUsesDotEvenInTurkishLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("41.008200,28.978400", NavRoute.encode(p))
            assertEquals("yandexmaps://maps.yandex.ru/?rtext=~41.008200,28.978400&rtt=auto", NavRoute.yandexRouteUri(p))
            assertEquals("google.navigation:q=41.008200,28.978400", NavRoute.googleNavUri(p))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test fun negativeCoordinates() {
        assertEquals("-33.868800,-151.209300", NavRoute.encode(NavPoint(-33.8688, -151.2093)))
        assertEquals("geo:-33.868800,-151.209300?q=-33.868800,-151.209300", NavRoute.geoUri(NavPoint(-33.8688, -151.2093)))
    }

    @Test fun encodeDecodeRoundTrip() {
        val d = NavRoute.decode(NavRoute.encode(p))!!
        assertEquals(p.lat, d.lat, 1e-6)
        assertEquals(p.lon, d.lon, 1e-6)
    }

    @Test fun decodeRejectsBadInput() {
        assertNull(NavRoute.decode(null))
        assertNull(NavRoute.decode(""))
        assertNull(NavRoute.decode("Kadıköy, İstanbul"))
        assertNull(NavRoute.decode("41.0"))
        assertNull(NavRoute.decode("41.0,28.0,3"))
        assertNull(NavRoute.decode("91.0,28.0"))
        assertNull(NavRoute.decode("41.0,181.0"))
        assertNull(NavRoute.decode("NaN,1"))
    }

    @Test fun routeOrderWithYandexAndGoogle() {
        val a = NavRoute.routeAttempts(p) { true }
        assertEquals(4, a.size)
        assertEquals(NavRoute.YANDEX_MAPS, a[0].pkg)
        assertEquals(NavRoute.ACTION_VIEW, a[0].action)
        assertEquals(NavRoute.ACTION_BUILD_ROUTE, a[1].action)
        assertEquals(mapOf("lat_to" to 41.0082, "lon_to" to 28.9784), a[1].extras)
        assertEquals(NavRoute.GOOGLE_MAPS, a[2].pkg)
        assertEquals("", a[3].pkg)
        assertEquals(true, a[3].uri!!.startsWith("geo:"))
    }

    @Test fun routeFallsBackWhenYandexMissing() {
        val a = NavRoute.routeAttempts(p) { it == NavRoute.GOOGLE_MAPS }
        assertEquals(listOf(NavRoute.GOOGLE_MAPS, ""), a.map { it.pkg })
        assertEquals(1, NavRoute.routeAttempts(p) { false }.size)
    }

    @Test fun searchAttempts() {
        assertEquals("yandexmaps://maps.yandex.ru/?text=", NavRoute.searchAttempts { true }[0].uri)
        assertEquals(0, NavRoute.searchAttempts { false }.size)
    }
}
