package com.mapcontrol.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** "Ev" ve "İş" kısayollarının türü. */
enum class NavPlace { HOME, WORK }

/** Son hedef: görünen ad, yeniden başlatmak için gereken intent verisi ve hedef paket. */
data class NavRecent(val label: String, val uri: String, val pkg: String)

/**
 * Ev ve iş adreslerini ve son 3 hedefi "launcher_nav" tercihlerinde saklar.
 * Boş adres "ayarlanmamış" demektir; yazma her zaman apply() ile yapılır.
 */
class NavShortcutStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(PREFS, Profiles.current(context)), Context.MODE_PRIVATE)

    /** Kayıtlı adres; yoksa boş metin. */
    fun get(kind: NavPlace): String = prefs.getString(keyOf(kind), "").orEmpty()

    /** Adresi kaydeder. Boş (kırpılmış) metin kaydı siler. */
    fun set(kind: NavPlace, address: String) {
        val value = address.trim()
        prefs.edit().apply {
            if (value.isEmpty()) remove(keyOf(kind)) else putString(keyOf(kind), value)
        }.apply()
    }

    /** Son hedefler, en yeni başta (en fazla [MAX_RECENTS]). Bozuk kayıt boş liste verir. */
    fun recents(): List<NavRecent> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("l")
                val uri = o.optString("u")
                if (label.isEmpty() || uri.isEmpty()) null else NavRecent(label, uri, o.optString("p"))
            }.take(MAX_RECENTS)
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /** Hedefi listenin başına ekler; aynı ad varsa eskisi silinir, liste 3'te kalır. */
    fun addRecent(label: String, uri: String, pkg: String) {
        val name = label.trim()
        if (name.isEmpty() || uri.isEmpty()) return
        val list = listOf(NavRecent(name, uri, pkg)) + recents().filter { !it.label.equals(name, ignoreCase = true) }
        val arr = JSONArray()
        for (r in list.take(MAX_RECENTS)) {
            arr.put(JSONObject().put("l", r.label).put("u", r.uri).put("p", r.pkg))
        }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    private fun keyOf(kind: NavPlace): String = when (kind) {
        NavPlace.HOME -> KEY_HOME
        NavPlace.WORK -> KEY_WORK
    }

    companion object {
        const val MAX_RECENTS = 3
        private const val PREFS = "launcher_nav"
        private const val KEY_HOME = "home_address"
        private const val KEY_WORK = "work_address"
        private const val KEY_RECENTS = "recents"
    }
}
