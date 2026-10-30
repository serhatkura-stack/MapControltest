package com.mapcontrol.launcher

import android.content.Context

/**
 * Ev ve iş konumlarını (enlem/boylam) "launcher_nav" tercihlerinde, profile göre ayrı saklar.
 * Değer "lat,lon" metnidir ([NavRoute.encode]); eski sürümün adres metni anahtarları okunmaz (yok sayılır).
 * Yazma her zaman apply() ile yapılır.
 */
class NavShortcutStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(PREFS, Profiles.current(context)), Context.MODE_PRIVATE)

    /** Kayıtlı konum; yoksa ya da bozuksa null. */
    fun get(kind: NavPlace): NavPoint? = NavRoute.decode(prefs.getString(keyOf(kind), null))

    fun set(kind: NavPlace, point: NavPoint) {
        prefs.edit().putString(keyOf(kind), NavRoute.encode(point)).apply()
    }

    private fun keyOf(kind: NavPlace): String = when (kind) {
        NavPlace.HOME -> KEY_HOME
        NavPlace.WORK -> KEY_WORK
    }

    private companion object {
        const val PREFS = "launcher_nav"
        // Eski "home_address"/"work_address" (metin) anahtarlarından ayrı; eski kayıtlar yok sayılır.
        const val KEY_HOME = "home_point"
        const val KEY_WORK = "work_point"
    }
}
