package com.mapcontrol.launcher

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Düzenlenebilir ana ekran ızgarasının modeli.
 *
 * Orta alan [GRID_COLS] × [GRID_ROWS] hücrelik bir ızgaradır (satır sayısı araç profiline göre). Üst şerit (saat, Ayarlar) ve
 * alt çubuk (klima, uygulamalar) ızgaranın dışındadır ve sabittir.
 */
const val GRID_COLS = 12

/** Izgaranın satır sayısı: etkin [VehicleProfile]'a göre (Tiggo 7: 6, Tiggo 8: 8). */
val GRID_ROWS: Int get() = VehicleProfile.active.gridRows

/** Kayıtlı düzenin ızgara sürümü: 1 = eski 6×3, 2 = 12×6 (her değer tam iki katı). */
const val GRID_VERSION = 2

/** Bir kutucuk bu genişlikten (hücre) itibaren "geniş" yerleşime geçer (eski 2 sütun = 4 hücre). */
const val WIDE_MIN_COLS = 4

/** Bir widget'ın ızgaradaki boyutu (sütun × satır). */
data class Span(val w: Int, val h: Int) {
    override fun toString() = "${w}×$h"
}

/** "Widget ekle" listesinde gösterilecek türler (hazır olmayanlar elenir). */
fun addableWidgetTypes(): List<WidgetType> = WidgetType.values().toList()

/** Düzende şu an bulunan widget türleri. */
fun onScreenTypes(layout: List<PlacedWidget>): Set<WidgetType> = layout.map { it.type }.toSet()

/**
 * Bu türden bir widget daha eklenebilir mi: APP kutucuğundan istenildiği kadar, diğerlerinden yalnızca bir tane.
 * Kayıtlı düzende zaten çift olanlar silinmez ve yüklenir; bu yalnızca yeni eklemeyi engeller.
 */
fun isAddable(type: WidgetType, layout: List<PlacedWidget>): Boolean =
    type == WidgetType.APP || type !in onScreenTypes(layout)

/** Dikkat dağıtan widget'lar ([WidgetType.parkOnly]) Sürüş düzenine eklenemez; Park'ta serbesttir. */
fun isAllowedIn(type: WidgetType, mode: LayoutMode): Boolean = !(type.parkOnly && mode == LayoutMode.DRIVE)

/** Seçici için: [mode] düzenine konabilen türler ([addableWidgetTypes] listesinden). */
fun addableWidgetTypes(mode: LayoutMode): List<WidgetType> =
    addableWidgetTypes().filter { isAllowedIn(it, mode) }

/** Düzen yüklenirken: Sürüş'te eski kayıttan gelen parkOnly widget'lar çizilmez (depodan silinmez, boşluk kalır). */
fun visibleForMode(layout: List<PlacedWidget>, mode: LayoutMode): List<PlacedWidget> =
    layout.filter { isAllowedIn(it.type, mode) }

/** [layout] verilince "Widget ekle" listesinde seçilebilir türler: ekranda olanlar elenir (APP hariç). */
fun addableWidgetTypes(layout: List<PlacedWidget>): List<WidgetType> =
    addableWidgetTypes().filter { isAddable(it, layout) }

/**
 * Eklenebilir widget türleri ve her birinin desteklediği boyutlar.
 * İlk boyut, "Widget ekle" ile eklendiğinde kullanılan boyuttur.
 * Kural: bir boyut yalnızca anlamlı ölçüde daha fazla bilgi gösteriyorsa vardır. Kayıtlı düzendeki artık
 * geçersiz boyutlar [LayoutStore.snapSpans] ile en yakın izinli boyuta çekilir.
 */
enum class WidgetType(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int, val sizes: List<Span>, val parkOnly: Boolean = false) {
    MEDIA("media", R.string.launcher_widget_media, R.drawable.ic_mdi_music_note, listOf(Span(4, 2), Span(6, 4), Span(6, 6))),
    VEHICLE("vehicle", R.string.launcher_widget_vehicle, R.drawable.ic_mdi_car, listOf(Span(4, 4))),
    LOCK("lock", R.string.launcher_widget_lock, R.drawable.ic_mdi_lock, listOf(Span(1, 2))),
    DRIVE_MODE("drive_mode", R.string.launcher_widget_drive_mode, R.drawable.ic_mdi_car_sports, listOf(Span(2, 2))),
    CONSUMPTION("consumption", R.string.launcher_widget_consumption, R.drawable.ic_mdi_leaf, listOf(Span(2, 2))),
    TRIP("trip", R.string.launcher_widget_trip, R.drawable.ic_mdi_road_variant, listOf(Span(2, 2), Span(3, 2), Span(4, 4))),
    HEALTH("health", R.string.launcher_widget_health, R.drawable.ic_mdi_lightning_bolt, listOf(Span(2, 2), Span(4, 4))),
    AIR("air", R.string.launcher_widget_air, R.drawable.ic_mdi_air_filter, listOf(Span(2, 2), Span(4, 2))),
    APP("app", R.string.launcher_widget_app, R.drawable.ic_mdi_view_grid, listOf(Span(1, 2))),
    SEAT_HEAT_DRIVER("seat_heat_driver", R.string.launcher_widget_seat_heat_driver, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2))),
    SEAT_HEAT_PASSENGER("seat_heat_passenger", R.string.launcher_widget_seat_heat_passenger, R.drawable.ic_mdi_car_seat_heater, listOf(Span(2, 2))),
    SEAT_VENT_DRIVER("seat_vent_driver", R.string.launcher_widget_seat_vent_driver, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2))),
    SEAT_VENT_PASSENGER("seat_vent_passenger", R.string.launcher_widget_seat_vent_passenger, R.drawable.ic_mdi_car_seat_vent, listOf(Span(2, 2))),
    AWD("awd", R.string.launcher_widget_awd, R.drawable.ic_mdi_car_cog, listOf(Span(4, 4), Span(2, 2))),
    PHOTO("photo", R.string.launcher_widget_photo, R.drawable.ic_mdi_image, listOf(Span(2, 2), Span(4, 2), Span(4, 4)), parkOnly = true),
    SUNSHADE("sunshade", R.string.launcher_widget_sunshade, R.drawable.ic_mdi_car_sunroof, listOf(Span(2, 2), Span(2, 4))),
    TRUNK("trunk", R.string.launcher_widget_trunk, R.drawable.ic_mdi_car_trunk, listOf(Span(2, 2), Span(2, 4))),
    DEFROST("defrost", R.string.launcher_widget_defrost, R.drawable.ic_mdi_defrost_front, listOf(Span(2, 2))),
    SCREEN_OFF("screen_off", R.string.launcher_widget_screen_off, R.drawable.ic_mdi_weather_night, listOf(Span(2, 2), Span(1, 2)), parkOnly = true),
    BREAK_REMINDER("break_reminder", R.string.launcher_widget_break, R.drawable.ic_mdi_timer_sand, listOf(Span(2, 2))),
    PHONE_REMINDER("phone_reminder", R.string.launcher_widget_phone, R.drawable.ic_mdi_cellphone, listOf(Span(2, 2))),
    CLOCK("clock", R.string.launcher_widget_clock, R.drawable.ic_mdi_clock_outline, listOf(Span(2, 2), Span(4, 2)), parkOnly = true),
    REAR_SPEAKER("rear_speaker", R.string.launcher_widget_rear_speaker, R.drawable.ic_mdi_volume_high, listOf(Span(2, 2), Span(2, 1))),
    // FrostNav özellikleri ([FrostNavTile]): 4 sütunlu boyut büyük biçimdir ve Sahne panelinde kullanılır.
    WEB_PANEL("web_panel", R.string.launcher_widget_web_panel, R.drawable.ic_mdi_web, listOf(Span(2, 2), Span(4, 4))),
    EQUALIZER("equalizer", R.string.launcher_widget_equalizer, R.drawable.ic_mdi_equalizer, listOf(Span(2, 2), Span(4, 4))),
    AUTOMATION("automation", R.string.launcher_widget_automation, R.drawable.ic_mdi_restart, listOf(Span(2, 2), Span(4, 4))),
    SPLIT_SCREEN("split_screen", R.string.launcher_widget_split_screen, R.drawable.ic_mdi_view_split, listOf(Span(2, 2), Span(4, 2), Span(4, 4))),
    CLUSTER_MIRROR("cluster_mirror", R.string.launcher_widget_cluster_mirror, R.drawable.ic_mdi_speedometer, listOf(Span(2, 2), Span(4, 2), Span(4, 4)));

    companion object {
        /** Kaldırılan "battery"/"service" -> HEALTH, "trip_totals" -> TRIP ([HealthMath.REMOVED_KEYS]); bilinmeyen anahtar null. */
        fun fromKey(key: String): WidgetType? = (HealthMath.REMOVED_KEYS[key] ?: key).let { k -> values().firstOrNull { it.key == k } }
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

    fun fits(rows: Int = GRID_ROWS): Boolean = col >= 0 && row >= 0 && col + span.w <= GRID_COLS && row + span.h <= rows
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
 * Park varsayılanı (12×6): duran araçta sahibini karşılayan, zengin ama sakin ekran. İki bant:
 *  - Üst bant (bilgi, 4 satır): sürücü tarafında araç kartı 4×4 (menzil, şema, kilit, uyarılar); ortada medya 4×2,
 *    altında araç sağlığı ve hava kalitesi 2×2; sağda "Toplam" bölümlü yolculuk 4×4.
 *  - Alt bant (kontroller, 2 satır), sol direksiyonlu kabine göre: sürücü koltuk ısıtma · tavan perdesi · bagaj ·
 *    buğu giderme · iki ince uygulama kutucuğu (tek şerit) · yolcu koltuk ısıtma.
 * Izgara boşluksuz dolar. [oemBar] iki durumda da aynı düzeni verir (her iki alt çubukta da uygulamalar düğmesi var);
 * imza [LayoutStore] ile uyum içindir. Uygulama kutucukları [appPackages] ile dolar.
 */
fun defaultLayout(oemBar: Boolean = false, appPackages: List<String> = emptyList(), profile: VehicleProfile = VehicleProfile.TIGGO7): List<PlacedWidget> = listOf(
    PlacedWidget("vehicle", WidgetType.VEHICLE, 0, 0, Span(4, 4)),
    PlacedWidget("media", WidgetType.MEDIA, 4, 0, Span(4, 2)),
    PlacedWidget("health", WidgetType.HEALTH, 4, 2, Span(2, 2)),
    PlacedWidget("air", WidgetType.AIR, 6, 2, Span(2, 2)),
    PlacedWidget("trip", WidgetType.TRIP, 8, 0, Span(4, 4)),
    PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 0, 4, Span(2, 2)),
    PlacedWidget("sunshade", WidgetType.SUNSHADE, 2, 4, Span(2, 2)),
    PlacedWidget("trunk", WidgetType.TRUNK, 4, 4, Span(2, 2)),
    PlacedWidget("defrost", WidgetType.DEFROST, 6, 4, Span(2, 2)),
) + (0 until DEFAULT_PARK_APP_TILES).map { i ->
    PlacedWidget("app_dock_$i", WidgetType.APP, 8 + i, 4, Span(1, 2), appPackages.getOrNull(i))
} + PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 4, Span(2, 2)) +
    if (profile == VehicleProfile.TIGGO8) parkExtraBandT8() else emptyList()

/**
 * Tiggo 8 (12×8) Park'ın ek bandı: Tiggo 7 düzeninin altına 2 satırlık bir 2×2 kutucuk bandı. Üstteki iki bant
 * (kahraman kartlar dahil) Tiggo 7 ile aynı kalır; kahramanlar 4×4'te zaten orantılıdır. Yer tutucudur.
 */
private fun parkExtraBandT8(): List<PlacedWidget> = listOf(
    PlacedWidget("seat_vent_driver", WidgetType.SEAT_VENT_DRIVER, 0, 6, Span(2, 2)),
    PlacedWidget("rear_speaker", WidgetType.REAR_SPEAKER, 2, 6, Span(2, 2)),
    PlacedWidget("consumption", WidgetType.CONSUMPTION, 4, 6, Span(2, 2)),
    PlacedWidget("drive_mode", WidgetType.DRIVE_MODE, 6, 6, Span(2, 2)),
    PlacedWidget("break_reminder", WidgetType.BREAK_REMINDER, 8, 6, Span(2, 2)),
    PlacedWidget("seat_vent_passenger", WidgetType.SEAT_VENT_PASSENGER, 10, 6, Span(2, 2)),
)

/** Park varsayılanındaki ince uygulama kutucuğu sayısı (her biri 1×2, yan yana tek şerit). */
const val DEFAULT_PARK_APP_TILES = 2

/** Varsayılan düzen için okunan kurulu uygulama sayısı üst sınırı ([AppDock.installedDefaults]). */
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
        WidgetType.VEHICLE -> VehicleHeroCard(activity)
        WidgetType.LOCK -> LockTile(activity)
        WidgetType.DRIVE_MODE -> DriveModeTile(activity)
        WidgetType.CONSUMPTION -> ConsumptionTile(activity)
        WidgetType.TRIP -> TripComputerTile(activity)
        WidgetType.HEALTH -> HealthTile(activity)
        WidgetType.AIR -> AirQualityTile(activity)
        WidgetType.APP -> AppShortcutTile(activity, placed.extra, placed.id)
        WidgetType.SEAT_HEAT_DRIVER -> SeatHeatTile(activity, vent = false, driverSeat = true)
        WidgetType.SEAT_HEAT_PASSENGER -> SeatHeatTile(activity, vent = false, driverSeat = false)
        WidgetType.SEAT_VENT_DRIVER -> SeatHeatTile(activity, vent = true, driverSeat = true)
        WidgetType.SEAT_VENT_PASSENGER -> SeatHeatTile(activity, vent = true, driverSeat = false)
        WidgetType.AWD -> AwdTile(activity)
        WidgetType.SUNSHADE -> SunshadeTile(activity)
        WidgetType.TRUNK -> TrunkTile(activity)
        WidgetType.DEFROST -> DefrostTile(activity)
        WidgetType.PHOTO -> PhotoTile(activity, placed.id)
        WidgetType.SCREEN_OFF -> ScreenOffTile(activity)
        WidgetType.BREAK_REMINDER -> BreakTile(activity)
        WidgetType.PHONE_REMINDER -> PhoneTile(activity)
        WidgetType.CLOCK -> ClockTile(activity)
        WidgetType.REAR_SPEAKER -> RearSpeakerTile(activity)
        WidgetType.WEB_PANEL -> WebPanelTile(activity)
        WidgetType.EQUALIZER -> EqualizerTile(activity)
        WidgetType.AUTOMATION -> AutomationTile(activity)
        WidgetType.SPLIT_SCREEN -> SplitScreenTile(activity)
        WidgetType.CLUSTER_MIRROR -> ClusterMirrorTile(activity)
    }
}
