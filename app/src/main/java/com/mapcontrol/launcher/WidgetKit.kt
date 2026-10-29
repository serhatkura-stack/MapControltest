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
const val GRID_COLS = 12
const val GRID_ROWS = 6

/** Kayıtlı düzenin ızgara sürümü: 1 = eski 6×3, 2 = 12×6 (her değer tam iki katı). */
const val GRID_VERSION = 2

/** Bir kutucuk bu genişlikten (hücre) itibaren "geniş" yerleşime geçer (eski 2 sütun = 4 hücre). */
const val WIDE_MIN_COLS = 4

/** Bir widget'ın ızgaradaki boyutu (sütun × satır). */
data class Span(val w: Int, val h: Int) {
    override fun toString() = "${w}×$h"
}

/**
 * Eklenebilir widget türleri ve her birinin desteklediği boyutlar.
 * İlk boyut, "Widget ekle" ile eklendiğinde kullanılan boyuttur.
 */
enum class WidgetType(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int, val sizes: List<Span>) {
    MEDIA("media", R.string.launcher_widget_media, R.drawable.ic_mdi_music_note, listOf(Span(6, 6), Span(6, 4), Span(4, 2), Span(8, 6))),
    NAVIGATION("navigation", R.string.launcher_widget_navigation, R.drawable.ic_mdi_map_marker, listOf(Span(6, 4), Span(6, 6), Span(6, 2), Span(4, 2))),
    VEHICLE("vehicle", R.string.launcher_widget_vehicle, R.drawable.ic_mdi_car, listOf(Span(6, 4), Span(4, 4))),
    LOCK("lock", R.string.launcher_widget_lock, R.drawable.ic_mdi_lock, listOf(Span(2, 2), Span(3, 2))),
    DRIVE_MODE("drive_mode", R.string.launcher_widget_drive_mode, R.drawable.ic_mdi_car_sports, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    CONSUMPTION("consumption", R.string.launcher_widget_consumption, R.drawable.ic_mdi_leaf, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    TRIP("trip", R.string.launcher_widget_trip, R.drawable.ic_mdi_road_variant, listOf(Span(4, 2), Span(2, 2), Span(3, 2))),
    HEALTH("health", R.string.launcher_widget_health, R.drawable.ic_mdi_lightning_bolt, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    WEATHER("weather", R.string.launcher_widget_weather, R.drawable.ic_mdi_weather_partly_cloudy, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    SERVICE("service", R.string.launcher_widget_service, R.drawable.ic_mdi_wrench, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    AIR("air", R.string.launcher_widget_air, R.drawable.ic_mdi_air_filter, listOf(Span(2, 2), Span(4, 2), Span(3, 2))),
    APP("app", R.string.launcher_widget_app, R.drawable.ic_mdi_view_grid, listOf(Span(1, 2), Span(2, 2))),
    SEAT_HEAT_DRIVER("seat_heat_driver", R.string.launcher_widget_seat_heat_driver, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2), Span(3, 2))),
    SEAT_HEAT_PASSENGER("seat_heat_passenger", R.string.launcher_widget_seat_heat_passenger, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2), Span(3, 2))),
    SEAT_VENT_DRIVER("seat_vent_driver", R.string.launcher_widget_seat_vent_driver, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2), Span(3, 2))),
    SEAT_VENT_PASSENGER("seat_vent_passenger", R.string.launcher_widget_seat_vent_passenger, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2), Span(3, 2)));

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

/** Düzenleme moduna göre görünümünü değiştiren widget (ör. boş uygulama kutusunun artısı yalnız düzenlemede görünür). */
interface EditAware {
    fun setEditing(editing: Boolean)
}

/** Boyutu değişebilen widget: ızgara yerleşimi değişince kendini yeni boyuta göre düzenler. */
interface SizedWidget : LauncherWidget {
    fun applySpan(span: Span)
}

/**
 * Varsayılan yerleşim (12×6): büyük medya, yanında araç kartı (menzil + durum) ve altında tek satırlık "Nereye?".
 * [oemBar] (araçta OEM alt çubuğu var, bizim alt çubuğumuz yok): üst dört satırı medya ve araç alır, alt iki
 * satıra sürücü koltuk ısıtma (2) · navigasyon (4) · dört ince uygulama kutucuğu (tek şerit) · yolcu koltuk ısıtma (2)
 * gelir: 2+4+4+2 = 12. Sol direksiyonlu kabine göre: sürücü solda, yolcu sağda; yolcu kendi tarafındaki kutucuğa doğrudan dokunur.
 */
fun defaultLayout(oemBar: Boolean = false, appPackages: List<String> = emptyList()): List<PlacedWidget> = if (oemBar) {
    listOf(
        PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(6, 4)),
        PlacedWidget("vehicle", WidgetType.VEHICLE, 6, 0, Span(6, 4)),
        PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 0, 4, Span(2, 2)),
        PlacedWidget("navigation", WidgetType.NAVIGATION, 2, 4, Span(4, 2)),
    ) +
        // Dört uygulama kutucuğu yan yana: ızgara bunları tek şerit olarak birleştirir. Boşsa (paket yok) yalnız düzenlemede artı görünür.
        (0 until DEFAULT_APP_SLOTS).map { i ->
            PlacedWidget("app_dock_$i", WidgetType.APP, 6 + i, 4, Span(1, 2), appPackages.getOrNull(i))
        } +
        PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 4, Span(2, 2))
} else listOf(
    PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(6, 6)),
    PlacedWidget("vehicle", WidgetType.VEHICLE, 6, 0, Span(6, 4)),
    PlacedWidget("navigation", WidgetType.NAVIGATION, 6, 4, Span(6, 2)),
)

/** OEM çubuklu varsayılanda yan yana uygulama kutucuğu sayısı. */
const val DEFAULT_APP_SLOTS = 4

/**
 * Eski 6×3 düzeni (sürüm 1) 12×6'ya taşır: konum ve boyut tam iki katı olur. Görünüm aynı kalır;
 * iki katı boyutlar yeni ızgarada her tür için geçerlidir.
 */
fun migrateGridV1(layout: List<PlacedWidget>): List<PlacedWidget> = layout.map {
    it.copy(col = it.col * 2, row = it.row * 2, span = Span(it.span.w * 2, it.span.h * 2))
}

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
        WidgetType.SEAT_HEAT_DRIVER -> SeatHeatTile(activity, vent = false, driverSeat = true)
        WidgetType.SEAT_HEAT_PASSENGER -> SeatHeatTile(activity, vent = false, driverSeat = false)
        WidgetType.SEAT_VENT_DRIVER -> SeatHeatTile(activity, vent = true, driverSeat = true)
        WidgetType.SEAT_VENT_PASSENGER -> SeatHeatTile(activity, vent = true, driverSeat = false)
    }
}
