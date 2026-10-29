package com.mapcontrol.launcher

import android.content.Context

/** "Ev" ve "İş" kısayollarının türü. */
enum class NavPlace { HOME, WORK }

/**
 * Ev ve iş adreslerini "launcher_nav" tercihlerinde saklar.
 * Boş adres "ayarlanmamış" demektir; yazma her zaman apply() ile yapılır.
 */
class NavShortcutStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Kayıtlı adres; yoksa boş metin. */
    fun get(kind: NavPlace): String = prefs.getString(keyOf(kind), "").orEmpty()

    /** Adresi kaydeder. Boş (kırpılmış) metin kaydı siler. */
    fun set(kind: NavPlace, address: String) {
        val value = address.trim()
        prefs.edit().apply {
            if (value.isEmpty()) remove(keyOf(kind)) else putString(keyOf(kind), value)
        }.apply()
    }

    private fun keyOf(kind: NavPlace): String = when (kind) {
        NavPlace.HOME -> KEY_HOME
        NavPlace.WORK -> KEY_WORK
    }

    private companion object {
        const val PREFS = "launcher_nav"
        const val KEY_HOME = "home_address"
        const val KEY_WORK = "work_address"
    }
}
