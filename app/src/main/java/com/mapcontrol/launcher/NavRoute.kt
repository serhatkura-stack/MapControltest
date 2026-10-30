package com.mapcontrol.launcher

import java.util.Locale

/** "Ev" ve "İş" kısayollarının türü. */
enum class NavPlace { HOME, WORK }

/** Kaydedilmiş konum (WGS84 derece). */
data class NavPoint(val lat: Double, val lon: Double)

/**
 * Bir navigasyon başlatma tarifi: Android'siz saf veri, birim testle doğrulanır.
 * [pkg] boşsa paket kısıtı yoktur; [extras] yalnızca double değerleridir.
 */
data class NavLaunch(
    val action: String,
    val uri: String?,
    val pkg: String,
    val extras: Map<String, Double> = emptyMap(),
)

/**
 * Konum kodlama ve navigasyon uygulaması intent tarifleri (saf mantık, Android'siz).
 * Koordinatlar her zaman '.' ondalıklı ve yerel ayardan bağımsız yazılır.
 */
object NavRoute {
    const val YANDEX_MAPS = "ru.yandex.yandexmaps"
    const val YANDEX_NAVI = "ru.yandex.yandexnavi"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_BUILD_ROUTE = "ru.yandex.yandexmaps.action.BUILD_ROUTE_ON_MAP"

    /** Yerel ayardan bağımsız, '.' ondalıklı, 6 hane (yaklaşık 0,1 m). */
    fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    /** Saklama biçimi: "lat,lon". */
    fun encode(p: NavPoint): String = "${fmt(p.lat)},${fmt(p.lon)}"

    /** Bozuk, eksik, sayı olmayan ya da aralık dışı değer null verir. */
    fun decode(raw: String?): NavPoint? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return NavPoint(lat, lon)
    }

    /** Yandex Haritalar rota URI'si (orta güven: araçta doğrulanmalı). */
    fun yandexRouteUri(p: NavPoint): String = "yandexmaps://maps.yandex.ru/?rtext=~${fmt(p.lat)},${fmt(p.lon)}&rtt=auto"

    fun googleNavUri(p: NavPoint): String = "google.navigation:q=${fmt(p.lat)},${fmt(p.lon)}"

    fun geoUri(p: NavPoint): String = "geo:${fmt(p.lat)},${fmt(p.lon)}?q=${fmt(p.lat)},${fmt(p.lon)}"

    /**
     * Rota deneme sırası. Uygulama kurulu değilse atlanır; ilk başlatılabilen kazanır:
     * 1) Yandex Haritalar URI, 2) Yandex BUILD_ROUTE_ON_MAP (lat_to/lon_to), 3) Google Maps, 4) genel geo:.
     */
    fun routeAttempts(p: NavPoint, installed: (String) -> Boolean): List<NavLaunch> {
        val list = ArrayList<NavLaunch>()
        if (installed(YANDEX_MAPS)) {
            list += NavLaunch(ACTION_VIEW, yandexRouteUri(p), YANDEX_MAPS)
            list += NavLaunch(ACTION_BUILD_ROUTE, null, YANDEX_MAPS, mapOf("lat_to" to p.lat, "lon_to" to p.lon))
        }
        if (installed(GOOGLE_MAPS)) list += NavLaunch(ACTION_VIEW, googleNavUri(p), GOOGLE_MAPS)
        list += NavLaunch(ACTION_VIEW, geoUri(p), "")
        return list
    }

    /** "Nereye?" araması: Yandex Haritalar'ı açar (uygulamanın başlatıcı intent'i ayrıca kartta denenir). */
    fun searchAttempts(installed: (String) -> Boolean): List<NavLaunch> {
        val list = ArrayList<NavLaunch>()
        if (installed(YANDEX_MAPS)) list += NavLaunch(ACTION_VIEW, "yandexmaps://maps.yandex.ru/?text=", YANDEX_MAPS)
        if (installed(GOOGLE_MAPS)) list += NavLaunch(ACTION_VIEW, "geo:0,0?q=", GOOGLE_MAPS)
        return list
    }
}
