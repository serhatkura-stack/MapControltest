package com.mapcontrol.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ana ekran düzenini SharedPreferences'ta JSON olarak saklar.
 * Okunan düzen geçersizse (ızgara dışı, çakışan) varsayılan düzene dönülür.
 */
class LayoutStore(context: Context) {

    /** OEM alt çubuğu olan araçta (Desay) varsayılan düzen farklıdır. */
    private val oemBar = LauncherSafeArea.oemInsetPx(context) > 0

    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(PREFS, Profiles.current(context)), Context.MODE_PRIVATE)

    fun load(): List<PlacedWidget> {
        val raw = prefs.getString(KEY, null) ?: return defaultLayout(oemBar)
        return try {
            val array = JSONArray(raw)
            // Kaldırılmış/bilinmeyen türler (eski kayıtlar) sessizce atlanır; kalan düzen geçerliyse korunur.
            val list = (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val type = WidgetType.fromKey(o.getString("type")) ?: return@mapNotNull null
                PlacedWidget(
                    id = o.getString("id"),
                    type = type,
                    col = o.getInt("col"),
                    row = o.getInt("row"),
                    span = Span(o.getInt("w"), o.getInt("h")),
                    extra = if (o.has("extra") && !o.isNull("extra")) o.getString("extra") else null,
                )
            }
            if (list.isNotEmpty() && isValid(list)) list else defaultLayout(oemBar)
        } catch (e: org.json.JSONException) {
            defaultLayout(oemBar)
        }
    }

    fun save(layout: List<PlacedWidget>) {
        val array = JSONArray()
        layout.forEach { w ->
            array.put(JSONObject().apply {
                put("id", w.id)
                put("type", w.type.key)
                put("col", w.col)
                put("row", w.row)
                put("w", w.span.w)
                put("h", w.span.h)
                w.extra?.let { put("extra", it) }
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** Bu cihazın varsayılan düzeni (sıfırlama için). */
    fun defaults(): List<PlacedWidget> = defaultLayout(oemBar)

    fun reset() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "launcher_layout"
        private const val KEY = "widgets_v1"

        /** Her widget ızgarada ve hiçbiri diğeriyle çakışmıyor mu? */
        fun isValid(layout: List<PlacedWidget>): Boolean =
            layout.all { it.fits() && it.span in it.type.sizes } &&
                layout.map { it.id }.toSet().size == layout.size &&
                layout.indices.none { i -> (i + 1 until layout.size).any { j -> layout[i].overlaps(layout[j]) } }

        /** [candidate] mevcut düzende ([ignoreId] hariç) yerleştirilebilir mi? */
        fun canPlace(layout: List<PlacedWidget>, candidate: PlacedWidget, ignoreId: String? = candidate.id): Boolean =
            candidate.fits() && layout.none { it.id != ignoreId && it.overlaps(candidate) }

        /** Önce [preferCol]/[preferRow] konumunu, olmazsa soldan sağa, yukarıdan aşağı ilk boş yeri dener. */
        fun findSpot(layout: List<PlacedWidget>, base: PlacedWidget, preferCol: Int = base.col, preferRow: Int = base.row): PlacedWidget? {
            val preferred = base.copy(col = preferCol, row = preferRow)
            if (canPlace(layout, preferred)) return preferred
            for (row in 0..GRID_ROWS - base.span.h) for (col in 0..GRID_COLS - base.span.w) {
                val c = base.copy(col = col, row = row)
                if (canPlace(layout, c)) return c
            }
            return null
        }
    }
}
