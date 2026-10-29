package com.mapcontrol.launcher

import android.app.Activity
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Düzenlenebilir ana ekran ızgarasının modeli.
 *
 * Orta alan [GRID_COLS] × [GRID_ROWS] hücrelik bir ızgaradır. Üst şerit (saat, Ayarlar) ve
 * alt çubuk (klima, uygulamalar) ızgaranın dışındadır ve sabittir.
 */
const val GRID_COLS = 6
const val GRID_ROWS = 3

/** Bir widget'ın ızgaradaki boyutu (sütun × satır). */
data class Span(val w: Int, val h: Int) {
    override fun toString() = "${w}×$h"
}

/**
 * Eklenebilir widget türleri ve her birinin desteklediği boyutlar.
 * İlk boyut, "Widget ekle" ile eklendiğinde kullanılan boyuttur.
 */
enum class WidgetType(val key: String, @StringRes val label: Int, val sizes: List<Span>) {
    MEDIA("media", R.string.launcher_widget_media, listOf(Span(3, 3), Span(3, 2), Span(2, 1), Span(4, 3))),
    NAVIGATION("navigation", R.string.launcher_widget_navigation, listOf(Span(3, 2), Span(3, 3), Span(3, 1), Span(2, 1))),
    VEHICLE("vehicle", R.string.launcher_widget_vehicle, listOf(Span(3, 2), Span(2, 2))),
    RANGE("range", R.string.launcher_widget_range, listOf(Span(1, 1), Span(2, 1))),
    LOCK("lock", R.string.launcher_widget_lock, listOf(Span(1, 1))),
    TIRES("tires", R.string.launcher_widget_tires, listOf(Span(1, 1), Span(2, 1))),
    DRIVE_MODE("drive_mode", R.string.launcher_widget_drive_mode, listOf(Span(1, 1), Span(2, 1))),
    DOORS("doors", R.string.launcher_widget_doors, listOf(Span(1, 1), Span(2, 1))),
    SUNROOF("sunroof", R.string.launcher_widget_sunroof, listOf(Span(1, 1), Span(2, 1))),
    WEATHER("weather", R.string.launcher_widget_weather, listOf(Span(1, 1), Span(2, 1))),
    APP("app", R.string.launcher_widget_app, listOf(Span(1, 1)));

    companion object {
        fun fromKey(key: String): WidgetType? = values().firstOrNull { it.key == key }
    }
}

/**
 * Izgaraya yerleştirilmiş bir widget. [extra] türe özel ayardır (APP için paket adı).
 * Konum ve boyut her zaman ızgara sınırları içindedir ve diğer widget'larla çakışmaz.
 */
data class PlacedWidget(
    val id: String,
    val type: WidgetType,
    val col: Int,
    val row: Int,
    val span: Span,
    val extra: String? = null,
) {
    fun overlaps(other: PlacedWidget): Boolean =
        col < other.col + other.span.w && other.col < col + span.w &&
            row < other.row + other.span.h && other.row < row + span.h

    fun fits(): Boolean = col >= 0 && row >= 0 && col + span.w <= GRID_COLS && row + span.h <= GRID_ROWS
}

/** Boyutu değişebilen widget: ızgara yerleşimi değişince kendini yeni boyuta göre düzenler. */
interface SizedWidget : LauncherWidget {
    fun applySpan(span: Span)
}

/** Varsayılan yerleşim: büyük medya, yanında araç kartı (menzil + durum) ve altında tek satırlık "Nereye?". */
fun defaultLayout(): List<PlacedWidget> = listOf(
    PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(3, 3)),
    PlacedWidget("vehicle", WidgetType.VEHICLE, 3, 0, Span(3, 2)),
    PlacedWidget("navigation", WidgetType.NAVIGATION, 3, 2, Span(3, 1)),
)

/** Widget örneğini türüne göre üretir. Activity gerekir: bazı widget'lar diyalog açar. */
object WidgetFactory {
    fun create(activity: Activity, placed: PlacedWidget): SizedWidget = when (placed.type) {
        WidgetType.MEDIA -> MediaCard(activity)
        WidgetType.NAVIGATION -> NavigationCard(activity)
        WidgetType.VEHICLE -> VehicleHeroCard(activity)
        WidgetType.RANGE -> RangeTile(activity)
        WidgetType.LOCK -> LockTile(activity)
        WidgetType.TIRES -> TireTile(activity)
        WidgetType.DRIVE_MODE -> DriveModeTile(activity)
        WidgetType.DOORS -> DoorsTile(activity)
        WidgetType.SUNROOF -> SunroofTile(activity)
        WidgetType.WEATHER -> WeatherTile(activity)
        WidgetType.APP -> AppShortcutTile(activity, placed.extra, placed.id)
    }
}
