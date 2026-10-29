package com.mapcontrol.launcher

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.extra.project.carinfo.proxy.Constants
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.event.VDEvent
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Tek sinyali okur (VDBus.getOnce). Cevap yoksa null; CarInfoProxy.getItemValues bunu {0} ile gizler. */
internal object CarSignals {
    fun read(moduleId: Int, id: Int): IntArray? = try {
        val bundle = Bundle().apply { putInt(Constants.CMD_ID, id) }
        VDBus.getDefault().getOnce(VDEvent(moduleId, bundle))?.payload?.getIntArray(Constants.VALUE)
    } catch (e: RuntimeException) {
        null
    }

    /** Çok elemanlı cevaplar VehicleMetricsCache gibi bayt bayt birleştirilir; tek eleman olduğu gibi. */
    fun readInt(moduleId: Int, id: Int): Int? {
        val v = read(moduleId, id) ?: return null
        if (v.isEmpty()) return null
        return if (v.size == 1) v[0] else v.fold(0) { acc, b -> (acc shl 8) or (b and 0xFF) }
    }
}

/**
 * Yolculuk bilgisayarı verisi. Çevrimler Tiggo 7'de doğrulandı (2026-10-29, gösterge TripB ile eşleşti);
 * [driveAvgL100] hâlâ doğrulanmadı (okunan [0,0] = 0). [instantL100] yalnızca araç hareket ederken geçerli.
 */
internal data class TripData(
    val connected: Boolean = false,
    val moving: Boolean? = null,
    val instantL100: Double? = null,
    val driveAvgL100: Double? = null,
    val tripKm: Double? = null,
    val avgL100: Double? = null,
    val avgSpeedKmh: Int? = null,
    val batteryV: Double? = null,
    val coolantC: Int? = null,
    val lowFuel: Boolean? = null,
    /** Servise kalan km / gün (ICMT). Araçta 2026-10-29'da okundu, gösterge menüsüyle henüz karşılaştırılmadı. */
    val serviceKm: Int? = null,
    val serviceDays: Int? = null,
    /** Kabin içi / dışı PM2.5 (µg/m³). */
    val pm25In: Int? = null,
    val pm25Out: Int? = null,
)

/**
 * Yolculuk sinyallerini 2 sn'de bir, yalnızca dinleyen bir widget varken okur (ana ekran gizlenince durur).
 * Tek okuyucu: birden çok widget aynı turu paylaşır. Yalnızca okur.
 */
internal object TripSource {

    fun interface Listener {
        fun onTrip(data: TripData)
    }

    private const val POLL_MS = 2_000L
    private val RO = VDEventCarInfo.MODULE_READONLY_INFO
    private val HVAC = VDEventCarInfo.MODULE_HVAC

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "TripSource").apply { isDaemon = true } }
    private var task: ScheduledFuture<*>? = null
    private var appContext: Context? = null

    @Volatile
    var last: TripData = TripData()
        private set

    fun add(context: Context, listener: Listener) {
        appContext = context.applicationContext
        listeners += listener
        listener.onTrip(if (LauncherDemo.enabled) LauncherDemo.trip() else last)
        synchronized(this) {
            if (task == null) task = io.scheduleWithFixedDelay({ poll() }, 0, POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun remove(listener: Listener) {
        listeners -= listener
        synchronized(this) {
            if (listeners.isEmpty()) {
                task?.cancel(false)
                task = null
            }
        }
    }

    private fun poll() {
        // Yakalanmayan hata scheduleWithFixedDelay'i sessizce iptal eder; döngü sürsün.
        try {
            val data = if (LauncherDemo.enabled) LauncherDemo.trip() else readAll()
            if (data == last && !LauncherDemo.enabled) return
            last = data
            main.post { listeners.forEach { it.onTrip(data) } }
        } catch (t: Throwable) {
            Log.w("TripSource", "poll başarısız", t)
        }
    }

    private fun readAll(): TripData {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) {
            appContext?.let { proxy.init(it) }
            return TripData()
        }
        fun ro(id: Int) = CarSignals.readInt(RO, id)
        fun hvac(id: Int) = CarSignals.readInt(HVAC, id)
        // Anlık tüketim duruyorken 0xFFFF döner; yalnızca hızı > 0 iken geçerli.
        val speed = ro(ReadOnlyID.ID_CAR_SPEED)
        val moving = speed?.let { it in 1..300 }
        return TripData(
            connected = true,
            moving = moving,
            instantL100 = if (moving == true) ro(ReadOnlyID.ID_INSTANTANEOUS_CONSUMPTION)?.takeIf { it in 0..999 }?.let { it / 10.0 } else null,
            driveAvgL100 = ro(ReadOnlyID.ID_AVERAGE_FUEL_CONS_AFTER_RUNNING)?.takeIf { it in 1..999 }?.let { it / 10.0 },
            tripKm = ro(ReadOnlyID.ID_GRAND_TOTAL_KM_AFTER_CLEAR)?.takeIf { it >= 0 }?.let { it / 10.0 },
            avgL100 = ro(ReadOnlyID.ID_AVERAGE_OIL_CONSUMPTION_AFTER_CLEAR)?.takeIf { it in 1..999 }?.let { it / 10.0 },
            avgSpeedKmh = ro(ReadOnlyID.ID_AVG_SPEED)?.takeIf { it in 1..300 },
            batteryV = ro(ReadOnlyID.ID_IBS_VOLTAGE)?.takeIf { it in 5_000..20_000 }?.let { it / 1000.0 },
            coolantC = ro(ReadOnlyID.ID_WATER_TEMPERATURE)?.let { if (it > 200) it / 10 else it }?.takeIf { it in -40..150 },
            lowFuel = ro(ReadOnlyID.ID_LOW_FUEL_WARNING)?.let { it == 1 },
            serviceKm = ro(ReadOnlyID.ID_ICMT_DISTANCE)?.takeIf { it in 0..99_999 },
            serviceDays = ro(ReadOnlyID.ID_ICMT_DAY)?.takeIf { it in 0..9_999 },
            pm25In = hvac(HvacID.ID_AC_PM25_INCAR)?.takeIf { it in 0..999 },
            pm25Out = hvac(HvacID.ID_AC_PM25_OUTCAR)?.takeIf { it in 0..999 },
        )
    }
}

/** Yolculuk widget'larının ortak iskeleti: görünürken [TripSource]'u dinler. */
internal abstract class TripTile(
    context: Context,
    iconRes: Int,
    labelRes: Int,
) : VehicleTile(context, iconRes, labelRes) {

    protected var data = TripData()
        private set
    private val listener = TripSource.Listener {
        data = it
        bind()
    }

    override fun onVisible() = TripSource.add(context, listener)

    override fun onHidden() = TripSource.remove(listener)

    override fun release() = TripSource.remove(listener)

    override fun bindDetail() = bind()

    protected abstract fun bind()

    protected fun one(value: Double) = String.format(java.util.Locale.getDefault(), "%.1f", value)
}

/** Anlık tüketim: büyük rakam; geniş boyutta bu sürüşün ortalaması. Renk değişmez: sürücüyü uyarmaz, bilgi verir. */
internal class ConsumptionTile(context: Context) :
    TripTile(context, R.drawable.ic_mdi_leaf, R.string.launcher_widget_consumption) {

    override fun bind() {
        val now = data.instantL100
        if (data.connected && now == null && data.moving == false) {
            setValue(context.getString(R.string.launcher_consumption_idle), LauncherTheme.TEXT_SECONDARY)
            showDetail(averages())
            return
        }
        if (!data.connected || now == null) {
            showMissing()
            return
        }
        setValue(context.getString(R.string.launcher_l100_value, one(now)), LauncherTheme.TEXT)
        showDetail(averages())
    }

    /** Orta boyutta bu sürüşün ortalaması; geniş boyutta yolculuk ortalaması da alt satırda. */
    private fun averages(): CharSequence? = listOfNotNull(
        data.driveAvgL100?.let { context.getString(R.string.launcher_drive_avg, one(it)) },
        if (tier >= 2) data.avgL100?.let { context.getString(R.string.launcher_trip_avg, one(it)) } else null,
    ).joinToString("\n").ifEmpty { null }
}

/** Yolculuk: yolculuk km; geniş boyutta ortalama tüketim ve ortalama hız. */
internal class TripComputerTile(context: Context) :
    TripTile(context, R.drawable.ic_mdi_road_variant, R.string.launcher_widget_trip) {

    override fun bind() {
        val km = data.tripKm
        if (!data.connected || km == null) {
            showMissing()
            return
        }
        setValue(context.getString(R.string.launcher_km_value, one(km)), LauncherTheme.TEXT)
        val parts = listOfNotNull(
            data.avgL100?.let { context.getString(R.string.launcher_l100_value, one(it)) },
            data.avgSpeedKmh?.let { context.getString(R.string.launcher_kmh_value, it) },
        )
        // Orta boyutta tek satır (ilk ölçü); geniş boyutta ortalama tüketim ve ortalama hız alt alta.
        showDetail((if (tier >= 2) parts else parts.take(1)).joinToString("\n").ifEmpty { null })
    }
}

/**
 * Araç sağlığı: tek bir durum ("İyi" / "Kontrol et"). Yalnızca eldeki veriden türetilir, araca bir şey göndermez:
 * akü 12,0-15,0 V dışı, motor suyu > 110 °C ya da yakıt azaldı uyarısı "Kontrol et" yapar.
 * Geniş boyutta sayılar (ve varsa sebep) detayda görünür.
 */
internal class HealthTile(context: Context) :
    TripTile(context, R.drawable.ic_mdi_lightning_bolt, R.string.launcher_widget_health) {

    override fun bind() {
        val v = data.batteryV
        val coolant = data.coolantC
        val lowFuel = data.lowFuel
        if (!data.connected || (v == null && coolant == null && lowFuel == null)) {
            showMissing()
            return
        }
        val reasons = buildList {
            if (lowFuel == true) add(R.string.launcher_health_low_fuel)
            if (v != null && (v < BATTERY_MIN_V || v > BATTERY_MAX_V)) add(R.string.launcher_health_battery)
            if (coolant != null && coolant > COOLANT_MAX_C) add(R.string.launcher_health_hot)
        }
        val bad = reasons.isNotEmpty()
        setIcon(if (bad) R.drawable.ic_mdi_alert else R.drawable.ic_mdi_lightning_bolt,
            if (bad) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        setValue(context.getString(if (bad) R.string.launcher_health_check else R.string.launcher_health_good),
            if (bad) LauncherTheme.ALERT else LauncherTheme.TEXT)
        val numbers = when {
            v != null && coolant != null -> context.getString(R.string.launcher_health_numbers, one(v), coolant)
            v != null -> context.getString(R.string.launcher_health_volt_only, one(v))
            coolant != null -> context.getString(R.string.launcher_temp_c, coolant)
            else -> null
        }
        val why = reasons.joinToString(", ") { context.getString(it) }
        // Orta boyutta tek satır: sorun varsa sebebi, yoksa sayıları; geniş boyutta ikisi de.
        val lines = listOfNotNull(why.ifEmpty { null }, numbers)
        showDetail((if (tier >= 2) lines else lines.take(1)).joinToString("\n").ifEmpty { null })
    }

    private companion object {
        const val BATTERY_MIN_V = 12.0
        const val BATTERY_MAX_V = 15.0
        const val COOLANT_MAX_C = 110
    }
}
