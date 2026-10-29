package com.mapcontrol.launcher

import android.app.Activity
import androidx.annotation.DrawableRes
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
enum class WidgetType(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int, val sizes: List<Span>) {
    MEDIA("media", R.string.launcher_widget_media, R.drawable.ic_mdi_music_note, listOf(Span(3, 3), Span(3, 2), Span(2, 1), Span(4, 3))),
    NAVIGATION("navigation", R.string.launcher_widget_navigation, R.drawable.ic_mdi_map_marker, listOf(Span(3, 2), Span(3, 3), Span(3, 1), Span(2, 1))),
    VEHICLE("vehicle", R.string.launcher_widget_vehicle, R.drawable.ic_mdi_car, listOf(Span(3, 2), Span(2, 2))),
    LOCK("lock", R.string.launcher_widget_lock, R.drawable.ic_mdi_lock, listOf(Span(1, 1))),
    DRIVE_MODE("drive_mode", R.string.launcher_widget_drive_mode, R.drawable.ic_mdi_car_sports, listOf(Span(1, 1), Span(2, 1))),
    CONSUMPTION("consumption", R.string.launcher_widget_consumption, R.drawable.ic_mdi_leaf, listOf(Span(1, 1), Span(2, 1))),
    TRIP("trip", R.string.launcher_widget_trip, R.drawable.ic_mdi_road_variant, listOf(Span(2, 1), Span(1, 1))),
    HEALTH("health", R.string.launcher_widget_health, R.drawable.ic_mdi_lightning_bolt, listOf(Span(1, 1), Span(2, 1))),
    WEATHER("weather", R.string.launcher_widget_weather, R.drawable.ic_mdi_weather_partly_cloudy, listOf(Span(1, 1), Span(2, 1))),
    SERVICE("service", R.string.launcher_widget_service, R.drawable.ic_mdi_wrench, listOf(Span(1, 1), Span(2, 1))),
    AIR("air", R.string.launcher_widget_air, R.drawable.ic_mdi_air_filter, listOf(Span(1, 1), Span(2, 1))),
    APP("app", R.string.launcher_widget_app, R.drawable.ic_mdi_view_grid, listOf(Span(1, 1))),
    SEAT_HEAT("seat_heat", R.string.launcher_widget_seat_heat, R.drawable.ic_mdi_car_seat_heater, listOf(Span(1, 1), Span(2, 1))),
    SEAT_VENT("seat_vent", R.string.launcher_widget_seat_vent, R.drawable.ic_mdi_car_seat_vent, listOf(Span(1, 1), Span(2, 1))),
    APP_ROW("app_row", R.string.launcher_widget_app_row, R.drawable.ic_mdi_view_grid, listOf(Span(3, 1)));

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

/**
 * Varsayılan yerleşim: büyük medya, yanında araç kartı (menzil + durum) ve altında tek satırlık "Nereye?".
 * [oemBar] (araçta OEM alt çubuğu var, bizim alt çubuğumuz yok): ızgara üçe böler; medya ve araç üst iki
 * satırı alır, alt satıra navigasyon · uygulama şeridi · koltuk ısıtma gelir (alt çubuğun işlevleri).
 */
fun defaultLayout(oemBar: Boolean = false): List<PlacedWidget> = if (oemBar) listOf(
    PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(3, 2)),
    PlacedWidget("vehicle", WidgetType.VEHICLE, 3, 0, Span(3, 2)),
    PlacedWidget("navigation", WidgetType.NAVIGATION, 0, 2, Span(2, 1)),
    PlacedWidget("app_row", WidgetType.APP_ROW, 2, 2, Span(3, 1)),
    PlacedWidget("seat_heat", WidgetType.SEAT_HEAT, 5, 2, Span(1, 1)),
) else listOf(
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
        WidgetType.LOCK -> LockTile(activity)
        WidgetType.DRIVE_MODE -> DriveModeTile(activity)
        WidgetType.CONSUMPTION -> ConsumptionTile(activity)
        WidgetType.TRIP -> TripComputerTile(activity)
        WidgetType.HEALTH -> HealthTile(activity)
        WidgetType.WEATHER -> WeatherTile(activity)
        WidgetType.SERVICE -> ServiceTile(activity)
        WidgetType.AIR -> AirQualityTile(activity)
        WidgetType.APP -> AppShortcutTile(activity, placed.extra, placed.id)
        WidgetType.SEAT_HEAT -> SeatHeatTile(activity)
        WidgetType.SEAT_VENT -> SeatHeatTile(activity, vent = true)
        WidgetType.APP_ROW -> AppRowWidget(activity)
    }
}
