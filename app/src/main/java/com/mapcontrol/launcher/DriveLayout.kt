package com.mapcontrol.launcher

import android.content.Context
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Her profilin iki ana ekran düzeni vardır: duran araç için [PARK], giderken için [DRIVE]. */
enum class LayoutMode { PARK, DRIVE }

/**
 * Park/Sürüş düzeni geçiş kararı (saf, JVM'de sınanır).
 *
 * Sürüşe geçiş: hız > 0 kesintisiz [enterMs] sürerse. Parka dönüş (yalnızca Sürüş'teyken), hangisi önce olursa:
 *  - vites P (ya da el freni çekili) kesintisiz [parkSignalMs] sürerse; bu sinyaller bilinmiyorsa hiç gelmez,
 *  - hız 0 kesintisiz [stillFallbackMs] sürerse (kırmızı ışık gibi kısa beklemeler Sürüş'te kalır).
 * Bilinmeyen (null) hız bekleme sayaçlarını sıfırlar. [canSwitch] false iken karar ertelenir, sayaçlar işlemeye devam eder.
 */
class DriveLayoutPolicy(
    private val enterMs: Long = ENTER_MS,
    private val parkSignalMs: Long = PARK_SIGNAL_MS,
    private val stillFallbackMs: Long = STILL_FALLBACK_MS,
) {
    private var applied = LayoutMode.PARK
    private var movingSince: Long? = null
    private var stillSince: Long? = null
    private var parkedSince: Long? = null

    val mode: LayoutMode get() = applied

    fun reset(mode: LayoutMode = LayoutMode.PARK) {
        applied = mode
        movingSince = null; stillSince = null; parkedSince = null
    }

    /**
     * Park sinyali (vites/el freni) bu turda karar için gerekiyor mu? Yalnızca Sürüş'teyken ve hız 0/bilinmiyorken:
     * Park'ta ve hareket halinde sinyal hiçbir sayacı etkilemez, okunmaz (IPC tasarrufu). Gerekmeyince
     * [DriveSignals.invalidate] çağrılmalı ki bayat değer yeniden gerektiğinde bir sayaç başlatmasın.
     */
    fun wantsParkSignal(moving: Boolean?): Boolean = applied == LayoutMode.DRIVE && moving != true

    /** Geçilecek yeni mod, yoksa null. [immediatePark] (demo): sürüşteyken beklemeden Park'a. */
    fun update(now: Long, moving: Boolean?, parked: Boolean, canSwitch: Boolean, immediatePark: Boolean = false): LayoutMode? {
        movingSince = if (moving == true) movingSince ?: now else null
        stillSince = if (moving == false) stillSince ?: now else null
        parkedSince = if (parked && moving != true) parkedSince ?: now else null
        if (!canSwitch) return null
        val to = when (applied) {
            LayoutMode.PARK -> if (movingSince?.let { now - it >= enterMs } == true) LayoutMode.DRIVE else null
            LayoutMode.DRIVE -> when {
                immediatePark -> LayoutMode.PARK
                parkedSince?.let { now - it >= parkSignalMs } == true -> LayoutMode.PARK
                stillSince?.let { now - it >= stillFallbackMs } == true -> LayoutMode.PARK
                else -> null
            }
        }
        if (to != null) reset(to)
        return to
    }

    companion object {
        const val ENTER_MS = 5_000L
        const val PARK_SIGNAL_MS = 2_000L
        const val STILL_FALLBACK_MS = 180_000L
    }
}

/**
 * Düzen geçişinde gösterilecek bilgi: yalnızca otomatik geçişte, gerçekten mod değiştiyse ve ekran kurulduktan
 * (ilk açılış değil) sonra; elle geçişte null. Gösterilecek yeni modu döndürür.
 */
fun layoutNoticeFor(from: LayoutMode, to: LayoutMode, automatic: Boolean, started: Boolean): LayoutMode? =
    if (automatic && started && from != to) to else null

/** Kayıt anahtarları: Park mevcut anahtarı aynen korur (hiçbir şey kaybolmaz). Profil son eki dosya adındadır ([Profiles.key]). */
object LayoutKeys {
    const val PREFS = "launcher_layout"
    const val PARK = "widgets_v1"
    const val DRIVE = "widgets_drive_v1"

    fun key(mode: LayoutMode): String = if (mode == LayoutMode.DRIVE) DRIVE else PARK
    /** Tiggo 8 düzenleri ayrı dosyada tutulur (12×6 bir düzen 12×8'e ya da tersine asla yüklenmez); Tiggo 7 adı değişmez. */
    fun prefsName(profile: Int, vehicle: VehicleProfile = VehicleProfile.TIGGO7): String =
        Profiles.key(PREFS, profile) + if (vehicle == VehicleProfile.TIGGO8) "_t8" else ""
}

/** "Otomatik sürüş düzeni" seçeneği (profil başına, varsayılan açık). Ayarlar satırı için: [get] / [set]. */
object DriveLayoutPrefs {
    private const val PREFS = "launcher_drive_layout"
    private const val KEY = "auto_drive_layout"

    fun key(profile: Int): String = Profiles.key(KEY, profile)
    fun get(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(Profiles.key(context, KEY), true)

    fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(Profiles.key(context, KEY), on).apply()
    }
}

/**
 * Parka dönüş sinyalleri. Değerler araçta henüz ölçülmedi: null iken sinyal okunmaz ve yalnızca
 * [DriveLayoutPolicy.STILL_FALLBACK_MS] yedeği çalışır.
 */
internal object DriveSignals {
    /** ReadOnly ID_GEARBOX_STATE (26) değeri: P=1, R=2, N=3, D=5 (araçta ölçüldü). */
    val PARK_GEAR_VALUE: Int? = 1

    /** ReadOnly ID_HANDBRAKE_STATE (75) değeri: 1 çekili, 2 bırakılıyor, 0 inik, 3 çekiliyor (araçta ölçüldü). */
    val HANDBRAKE_ON_VALUE: Int? = 1

    private val RO = VDEventCarInfo.MODULE_READONLY_INFO
    /** Arka plan (daemon) tek iş parçacığı; boşta kendiliğinden biter (hiç kapatılmayan nesne için). */
    private val io = ThreadPoolExecutor(1, 1, 20L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "drive-signals").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    @Volatile private var cached = false
    @Volatile private var pending = false
    /** [invalidate] sayacı: uçuştaki bir okuma, arada geçersiz kılındıysa sonucunu atar. */
    @Volatile private var generation = 0

    /** Son okunan "park sinyali var" (arka planda tazelenir; ana iş parçacığını bloklamaz). Her çağrı bir okuma başlatır. */
    fun parked(): Boolean {
        if (PARK_GEAR_VALUE == null && HANDBRAKE_ON_VALUE == null) return false
        if (!pending) {
            pending = true
            val gen = generation
            io.execute {
                try {
                    val gear = PARK_GEAR_VALUE?.let { CarSignals.readInt(RO, ReadOnlyID.ID_GEARBOX_STATE) == it } ?: false
                    val brake = HANDBRAKE_ON_VALUE?.let { CarSignals.readInt(RO, ReadOnlyID.ID_HANDBRAKE_STATE) == it } ?: false
                    if (gen == generation) cached = gear || brake
                } finally { pending = false }
            }
        }
        return cached
    }

    /** Sinyal artık gerekmiyor: bayat değeri at (bkz. [DriveLayoutPolicy.wantsParkSignal]). */
    fun invalidate() {
        generation++
        cached = false
    }
}

/**
 * Sürüş varsayılanı: az, büyük, tek bakışlık (7 kart, kaydırma yok, uzun metin yok, Park'a özel widget yok).
 *  - Sol (sürücü tarafı): araç kartı 4×4 (menzil), altında sürücü koltuk ısıtma (sol alt köşe, kolay erişim) ve uyku modu.
 *  - Orta: tam yükseklikte medya 6×6 (en çok kullanılan; büyük kapak, büyük tuşlar).
 *  - Sağ sütun (yolcu tarafı): anlık tüketim, mola hatırlatıcı, en altta yolcu koltuk ısıtma (sağ alt köşe).
 * Uygulama kutucuğu yoktur ([oemBar] ve [appPackages] yalnızca imza uyumu içindir); ızgara boşluksuz dolar.
 */
@Suppress("UNUSED_PARAMETER")
fun defaultDriveLayout(oemBar: Boolean = false, appPackages: List<String> = emptyList(), profile: VehicleProfile = VehicleProfile.TIGGO7): List<PlacedWidget> =
    if (profile == VehicleProfile.TIGGO8) defaultDriveLayoutT8() else listOf(
        PlacedWidget("vehicle", WidgetType.VEHICLE, 0, 0, Span(4, 4)),
        PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 0, 4, Span(2, 2)),
        PlacedWidget("rear_speaker", WidgetType.REAR_SPEAKER, 2, 4, Span(2, 2)),
        PlacedWidget("media", WidgetType.MEDIA, 4, 0, Span(6, 6)),
        PlacedWidget("consumption", WidgetType.CONSUMPTION, 10, 0, Span(2, 2)),
        PlacedWidget("break_reminder", WidgetType.BREAK_REMINDER, 10, 2, Span(2, 2)),
        PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 4, Span(2, 2)),
    )

/**
 * Tiggo 8 (12×8) Sürüş varsayılanı (yer tutucu): Tiggo 7 ile aynı iskelet, iki ek satırla. Sol sütun: araç kartı 4×4,
 * altında iki satır koltuk/arka hoparlör/sürüş modu (sürücü ısıtma sol alt köşede). Orta: medya 6×6, altında
 * yolculuk · sağlık · buğu giderme. Sağ sütun: tüketim, mola, yolcu soğutma, yolcu ısıtma (sağ alt köşe).
 */
private fun defaultDriveLayoutT8(): List<PlacedWidget> = listOf(
    PlacedWidget("vehicle", WidgetType.VEHICLE, 0, 0, Span(4, 4)),
    PlacedWidget("seat_vent_driver", WidgetType.SEAT_VENT_DRIVER, 0, 4, Span(2, 2)),
    PlacedWidget("rear_speaker", WidgetType.REAR_SPEAKER, 2, 4, Span(2, 2)),
    PlacedWidget("seat_heat_driver", WidgetType.SEAT_HEAT_DRIVER, 0, 6, Span(2, 2)),
    PlacedWidget("drive_mode", WidgetType.DRIVE_MODE, 2, 6, Span(2, 2)),
    PlacedWidget("media", WidgetType.MEDIA, 4, 0, Span(6, 6)),
    PlacedWidget("trip", WidgetType.TRIP, 4, 6, Span(2, 2)),
    PlacedWidget("health", WidgetType.HEALTH, 6, 6, Span(2, 2)),
    PlacedWidget("defrost", WidgetType.DEFROST, 8, 6, Span(2, 2)),
    PlacedWidget("consumption", WidgetType.CONSUMPTION, 10, 0, Span(2, 2)),
    PlacedWidget("break_reminder", WidgetType.BREAK_REMINDER, 10, 2, Span(2, 2)),
    PlacedWidget("seat_vent_passenger", WidgetType.SEAT_VENT_PASSENGER, 10, 4, Span(2, 2)),
    PlacedWidget("seat_heat_passenger", WidgetType.SEAT_HEAT_PASSENGER, 10, 6, Span(2, 2)),
)
