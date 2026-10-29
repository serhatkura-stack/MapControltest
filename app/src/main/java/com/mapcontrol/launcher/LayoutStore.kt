package com.mapcontrol.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Diskten okunan, türü henüz çözülmemiş kayıt. */
data class RawWidget(val id: String, val typeKey: String, val col: Int, val row: Int, val w: Int, val h: Int, val extra: String?)

/**
 * Ana ekran düzenini SharedPreferences'ta JSON olarak saklar.
 * Okunan düzen geçersizse (ızgara dışı, çakışan) varsayılan düzene dönülür.
 */
class LayoutStore(context: Context) {

    /** OEM alt çubuğu olan araçta (Desay) varsayılan düzen farklıdır. */
    private val oemBar = LauncherSafeArea.hasOemBar(context)

    /** Varsayılan uygulama kutucukları alt çubuktaki uygulama şeridinin varsayılanlarıyla dolar. */
    private val appPackages = if (oemBar) AppDock.installedDefaults(context, DEFAULT_APP_SLOTS) else emptyList()

    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(PREFS, Profiles.current(context)), Context.MODE_PRIVATE)

    fun load(): List<PlacedWidget> {
        val raw = prefs.getString(KEY, null) ?: return defaultLayout(oemBar, appPackages)
        return try {
            val array = JSONArray(raw)
            // Kaldırılmış/bilinmeyen türler (eski kayıtlar) sessizce atlanır; kalan düzen geçerliyse korunur.
            val raws = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RawWidget(
                    id = o.getString("id"),
                    typeKey = o.getString("type"),
                    col = o.getInt("col"),
                    row = o.getInt("row"),
                    w = o.getInt("w"),
                    h = o.getInt("h"),
                    extra = if (o.has("extra") && !o.isNull("extra")) o.getString("extra") else null,
                )
            }
            // Sürüm anahtarı yoksa kayıt eski 6×3 biçimindedir: bir kez ×2 taşınır ve yeni biçimde yazılır.
            val oldGrid = prefs.getInt(KEY_GRID_VERSION, 1) < GRID_VERSION
            val scaled = if (oldGrid) raws.map { it.copy(col = it.col * 2, row = it.row * 2, w = it.w * 2, h = it.h * 2) } else raws
            val migrated = resolveRaw(scaled)
            if (migrated.isNotEmpty() && isValid(migrated)) {
                // Eski ızgara ya da eski koltuk anahtarları dönüştürüldüyse yeni biçim bir kez yazılır (tekrar yüklemede işlem kalmaz).
                if (oldGrid || raws.any { it.typeKey in LEGACY_SEAT_KEYS }) save(migrated)
                migrated
            } else defaultLayout(oemBar, appPackages)
        } catch (e: org.json.JSONException) {
            defaultLayout(oemBar, appPackages)
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
        prefs.edit().putString(KEY, array.toString()).putInt(KEY_GRID_VERSION, GRID_VERSION).apply()
    }

    /** Bu cihazın varsayılan düzeni (sıfırlama için). */
    fun defaults(): List<PlacedWidget> = defaultLayout(oemBar, appPackages)

    fun reset() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "launcher_layout"
        private const val KEY = "widgets_v1"
        private const val KEY_GRID_VERSION = "grid_v"

        /** Eski tek koltuk widget'ı anahtarları (ısıtma / soğutma; iki koltuğu birlikte gösterirdi). */
        private val LEGACY_SEAT_KEYS = mapOf(
            "seat_heat" to (WidgetType.SEAT_HEAT_DRIVER to WidgetType.SEAT_HEAT_PASSENGER),
            "seat_vent" to (WidgetType.SEAT_VENT_DRIVER to WidgetType.SEAT_VENT_PASSENGER),
        )

        /**
         * Kayıtları widget'lara çevirir: bilinmeyen türler atlanır; eski "seat_heat"/"seat_vent" sürücü
         * koltuğuna döner. Eskiden 4 hücre ve üzeri geniş olan (iki koltuk) kayıt, sürücü 2×2 olarak aynı yerde
         * kalır ve sağındaki hücreler boşsa yolcu 2×2 de eklenir. Tekrar çalıştırmak aynı sonucu verir.
         */
        fun resolveRaw(raws: List<RawWidget>): List<PlacedWidget> {
            val result = mutableListOf<PlacedWidget>()
            val passengers = mutableListOf<PlacedWidget>()
            raws.forEach { r ->
                val legacy = LEGACY_SEAT_KEYS[r.typeKey]
                if (legacy != null) {
                    val both = r.w >= WIDE_MIN_COLS
                    val span = if (both) Span(2, 2) else Span(r.w, r.h)
                    result += PlacedWidget(r.id, legacy.first, r.col, r.row, span, r.extra)
                    if (both) passengers += PlacedWidget(r.id + "_passenger", legacy.second, r.col + 2, r.row, Span(2, 2))
                } else {
                    val type = WidgetType.fromKey(r.typeKey) ?: return@forEach
                    result += PlacedWidget(r.id, type, r.col, r.row, Span(r.w, r.h), r.extra)
                }
            }
            // Yolcu eşi yalnız yeri boşsa eklenir; aksi halde sadece sürücü kalır.
            passengers.forEach { p ->
                if (result.none { it.id == p.id } && canPlace(result, p)) result += p
            }
            return result
        }

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
