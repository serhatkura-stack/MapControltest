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
 * Navigasyon kartı (Yandex Haritalar): true iken "Widget ekle" listesinde görünür; false ise gizlenir. [WidgetType.NAVIGATION] türü ve [NavigationCard] durur; kayıtlı düzenlerdeki mevcut
 * navigasyon widget'ları yüklenmeye devam eder. Açmak için yalnızca bu bayrak true yapılır
 * (varsayılan düzenler de eski yerleşimine döner).
 */
const val NAVIGATION_CARD_ENABLED = true

/** Navigasyon kartı varsayılan düzenlere konur mu? Hayır: yalnızca "Widget ekle" ile eklenir. */
const val NAVIGATION_IN_DEFAULT_LAYOUT = false

/** "Widget ekle" listesinde gösterilecek türler (hazır olmayanlar elenir). */
fun addableWidgetTypes(): List<WidgetType> =
    WidgetType.values().filter { NAVIGATION_CARD_ENABLED || it != WidgetType.NAVIGATION }

/** Düzende şu an bulunan widget türleri. */
fun onScreenTypes(layout: List<PlacedWidget>): Set<WidgetType> = layout.map { it.type }.toSet()

/**
 * Bu türden bir widget daha eklenebilir mi: APP kutucuğundan istenildiği kadar, diğerlerinden yalnızca bir tane.
 * Kayıtlı düzende zaten çift olanlar silinmez ve yüklenir; bu yalnızca yeni eklemeyi engeller.
 */
fun isAddable(type: WidgetType, layout: List<PlacedWidget>): Boolean =
    type == WidgetType.APP || type !in onScreenTypes(layout)

/** [layout] verilince "Widget ekle" listesinde seçilebilir türler: ekranda olanlar elenir (APP hariç). */
fun addableWidgetTypes(layout: List<PlacedWidget>): List<WidgetType> =
    addableWidgetTypes().filter { isAddable(it, layout) }

/**
 * Eklenebilir widget türleri ve her birinin desteklediği boyutlar.
 * İlk boyut, "Widget ekle" ile eklendiğinde kullanılan boyuttur.
 * Kural: bir boyut yalnızca anlamlı ölçüde daha fazla bilgi gösteriyorsa vardır. Kayıtlı düzendeki artık
 * geçersiz boyutlar [LayoutStore.snapSpans] ile en yakın izinli boyuta çekilir.
 */
enum class WidgetType(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int, val sizes: List<Span>) {
    MEDIA("media", R.string.launcher_widget_media, R.drawable.ic_mdi_music_note, listOf(Span(4, 2), Span(6, 4))),
    NAVIGATION("navigation", R.string.launcher_widget_navigation, R.drawable.ic_mdi_map_marker, listOf(Span(4, 2), Span(2, 2), Span(6, 2))),
    VEHICLE("vehicle", R.string.launcher_widget_vehicle, R.drawable.ic_mdi_car, listOf(Span(4, 4))),
    LOCK("lock", R.string.launcher_widget_lock, R.drawable.ic_mdi_lock, listOf(Span(1, 2))),
    DRIVE_MODE("drive_mode", R.string.launcher_widget_drive_mode, R.drawable.ic_mdi_car_sports, listOf(Span(2, 2))),
    CONSUMPTION("consumption", R.string.launcher_widget_consumption, R.drawable.ic_mdi_leaf, listOf(Span(2, 2))),
    TRIP("trip", R.string.launcher_widget_trip, R.drawable.ic_mdi_road_variant, listOf(Span(2, 2), Span(3, 2))),
    HEALTH("health", R.string.launcher_widget_health, R.drawable.ic_mdi_lightning_bolt, listOf(Span(2, 2), Span(3, 2))),
    WEATHER("weather", R.string.launcher_widget_weather, R.drawable.ic_mdi_weather_partly_cloudy, listOf(Span(2, 2))),
    SERVICE("service", R.string.launcher_widget_service, R.drawable.ic_mdi_wrench, listOf(Span(2, 2), Span(3, 2))),
    AIR("air", R.string.launcher_widget_air, R.drawable.ic_mdi_air_filter, listOf(Span(2, 2), Span(4, 2))),
    APP("app", R.string.launcher_widget_app, R.drawable.ic_mdi_view_grid, listOf(Span(1, 2))),
    SEAT_HEAT_DRIVER("seat_heat_driver", R.string.launcher_widget_seat_heat_driver, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2))),
    SEAT_HEAT_PASSENGER("seat_heat_passenger", R.string.launcher_widget_seat_heat_passenger, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2))),
    SEAT_VENT_DRIVER("seat_vent_driver", R.string.launcher_widget_seat_vent_driver, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2))),
    SEAT_VENT_PASSENGER("seat_vent_passenger", R.string.launcher_widget_seat_vent_passenger, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2))),
    AWD("awd", R.string.launcher_widget_awd, R.drawable.ic_mdi_car_cog, listOf(Span(4, 4), Span(2, 2))),
    PHOTO("photo", R.string.launcher_widget_photo, R.drawable.ic_mdi_image, listOf(Span(2, 2), Span(4, 2), Span(4, 4))),
    SUNSHADE("sunshade", R.string.launcher_widget_sunshade, R.drawable.ic_mdi_car_sunroof, listOf(Span(2, 2), Span(2, 4))),
    DEFROST("defrost", R.string.launcher_widget_defrost, R.drawable.ic_mdi_defrost_front, listOf(Span(2, 2))),
    STEERING_HEAT("steering_heat", R.string.launcher_widget_steering_heat, R.drawable.ic_mdi_steering_heat, listOf(Span(2, 2), Span(1, 2))),
    SCREEN_OFF("screen_off", R.string.launcher_widget_screen_off, R.drawable.ic_mdi_weather_night, listOf(Span(2, 2), Span(1, 2))),
    BREAK_REMINDER("break_reminder", R.string.launcher_widget_break, R.drawable.ic_mdi_timer_sand, listOf(Span(2, 2))),
    SPEED_LIMIT("speed_limit", R.string.launcher_widget_speed_limit, R.drawable.ic_mdi_speedometer, listOf(Span(2, 2))),
    BATTERY("battery", R.string.launcher_widget_battery, R.drawable.ic_mdi_lightning_bolt, listOf(Span(2, 2))),
    PHONE_REMINDER("phone_reminder", R.string.launcher_widget_phone, R.drawable.ic_mdi_cellphone, listOf(Span(2, 2))),
    CLOCK("clock", R.string.launcher_widget_clock, R.drawable.ic_mdi_clock_outline, listOf(Span(2, 2), Span(4, 2))),
    BODY("body", R.string.launcher_widget_body, R.drawable.ic_mdi_car_door, listOf(Span(2, 2)));

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
 * Varsayılan yerleşim (12×6). Üst dört satır: medya 6×4 (sol), ortada üst üste tüketim 2×2 ve yolculuk 2×2,
 * sağda araç kartı 4×4 (menzil + durum).
 * [oemBar] (araçta OEM alt çubuğu var, bizim alt çubuğumuz yok): alt iki satıra sürücü koltuk ısıtma (2) ·
 * sekiz ince uygulama kutucuğu (tek şerit, 8×1 = 8) · yolcu koltuk ısıtma (2) gelir: 2+8+2 = 12.
 * Sol direksiyonlu kabine göre: sürücü solda, yolcu sağda; yolcu kendi tarafındaki kutucuğa doğrudan dokunur.
 * OEM çubuğu yoksa alt iki satır altı adet 2×2 kutucukla dolar: hava durumu, sağlık, kilit, sürüş modu,
 * sürücü ve yolcu koltuk ısıtma.
 * [NAVIGATION_CARD_ENABLED] açıksa navigasyon eski yerine döner (OEM'de 4 hücre + ince uygulama kutucukları).
 */
fun defaultLayout(oemBar: Boolean = false, appPackages: List<String> = emptyList()): List<PlacedWidget> = if (oemBar) {
    val nav = NAVIGATION_IN_DEFAULT_LAYOUT
    // Uygulama kutucuğu yalnızca 1×2'dir. Navigasyon yokken şerit 8 kutucukla (col 2-9) dolar; varken 4 kutucuk ve col 6'dan başlar.
    val appStart = if (nav) 6 else 2
    val appSlots = if (nav) 4 else DEFAULT_APP_SLOTS
    listOf(
        PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(6, 4)),
        PlacedWidget("consumption", WidgetType.CONSUMPTION, 6, 0, Span(2, 2)),
        PlacedWidget("trip", WidgetType.TRIP, 6, 2, Span(2, 2)),
        PlacedWidget("vehicle", WidgetType.VEHICLE, 8, 0, Span(4, 4)),
        PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 0, 4, Span(2, 2)),
    ) + (if (nav) listOf(PlacedWidget("navigation", WidgetType.NAVIGATION, 2, 4, Span(4, 2))) else emptyList()) +
        // Dört uygulama kutucuğu yan yana: ızgara bunları tek şerit olarak birleştirir. Boşsa (paket yok) yalnız düzenlemede artı görünür.
        (0 until appSlots).map { i ->
            PlacedWidget("app_dock_$i", WidgetType.APP, appStart + i, 4, Span(1, 2), appPackages.getOrNull(i))
        } +
        PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 4, Span(2, 2))
} else listOf(
    PlacedWidget("media", WidgetType.MEDIA, 0, 0, Span(6, 4)),
    PlacedWidget("consumption", WidgetType.CONSUMPTION, 6, 0, Span(2, 2)),
    PlacedWidget("trip", WidgetType.TRIP, 6, 2, Span(2, 2)),
    PlacedWidget("vehicle", WidgetType.VEHICLE, 8, 0, Span(4, 4)),
    // Alt şerit altı adet 2×2 kutucukla dolar (medya yalnızca 4×2 ya da 6×4 olabildiği için sol alt de kutucuk).
    PlacedWidget("weather", WidgetType.WEATHER, 0, 4, Span(2, 2)),
    PlacedWidget("health", WidgetType.HEALTH, 2, 4, Span(2, 2)),
    // Kilit ince bir düğmedir (1×2); yanında bir uygulama kutucuğu ile 2 hücre dolar.
    PlacedWidget("lock", WidgetType.LOCK, 4, 4, Span(1, 2)),
    PlacedWidget("app_dock_0", WidgetType.APP, 5, 4, Span(1, 2), appPackages.getOrNull(0)),
    PlacedWidget("drive_mode", WidgetType.DRIVE_MODE, 6, 4, Span(2, 2)),
    PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 8, 4, Span(2, 2)),
    PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 4, Span(2, 2)),
)

/** OEM çubuklu varsayılanda yan yana uygulama kutucuğu sayısı (her biri 1×2). */
const val DEFAULT_APP_SLOTS = 8

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
        WidgetType.AWD -> AwdTile(activity)
        WidgetType.SUNSHADE -> SunshadeTile(activity)
        WidgetType.DEFROST -> DefrostTile(activity)
        WidgetType.STEERING_HEAT -> SteeringHeatTile(activity)
        WidgetType.PHOTO -> PhotoTile(activity, placed.id)
        WidgetType.SCREEN_OFF -> ScreenOffTile(activity)
        WidgetType.BREAK_REMINDER -> BreakTile(activity)
        WidgetType.SPEED_LIMIT -> SpeedLimitTile(activity)
        WidgetType.BATTERY -> BatteryTile(activity)
        WidgetType.PHONE_REMINDER -> PhoneTile(activity)
        WidgetType.CLOCK -> ClockTile(activity)
        WidgetType.BODY -> BodyTile(activity)
    }
}
