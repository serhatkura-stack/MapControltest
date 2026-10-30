package com.mapcontrol.launcher

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
 * Yolculuk sinyallerinin ham değerden çözümü (saf işlevler, JVM'de sınanır). Tiggo 7'de 2026-10-29'da gösterge
 * TripB sayfasıyla karşılaştırıldı: mesafe, ortalama tüketim ve ortalama hız onda bir birimdir.
 */
internal object TripDecode {
    /** Anlık tüketim (sinyal 70): ham/10 L/100 km. 0xFFFF (araç duruyor) ve aralık dışı değer geçersizdir. */
    fun instantL100(raw: Int?): Double? = raw?.takeIf { it in 0..999 }?.let { it / 10.0 }

    /** Gösterge hızı (sinyal 119): km/sa doğrudan. Aralık dışı değer geçersizdir. */
    fun speedKmh(raw: Int?): Int? = raw?.takeIf { it in 0..300 }

    /**
     * Gösterge hızı (119) varsa o, yoksa eski hız sinyali (0) kullanılır; ilki 0/geçersizse ikincisi denenir.
     * Tiggo 7'de sinyal 0 hareket ederken de 0/geçersiz kalıyordu.
     */
    fun speed(gauge: Int?, legacy: Int?): Int? {
        val g = speedKmh(gauge)
        if (g != null && g > 0) return g
        val l = speedKmh(legacy)
        if (l != null && l > 0) return l
        return g ?: l
    }

    /** Hareket kararı: hız > 0 ya da anlık tüketim geçerli. Hiçbiri okunamadıysa null (bilinmiyor). */
    fun moving(speedKmh: Int?, instantL100: Double?): Boolean? = when {
        speedKmh != null && speedKmh > 0 -> true
        instantL100 != null -> true
        speedKmh != null -> false
        else -> null
    }

    /** Sıfırlamadan beri mesafe (sinyal 40): ham/10 km. */
    fun tripKm(raw: Int?): Double? = raw?.takeIf { it >= 0 }?.let { it / 10.0 }

    /** Sıfırlamadan beri ortalama tüketim (sinyal 37): ham/10 L/100 km. */
    fun avgL100(raw: Int?): Double? = raw?.takeIf { it in 1..999 }?.let { it / 10.0 }

    /** Sıfırlamadan beri ortalama hız (sinyal 41): ham/10, gösterge gibi tam sayıya yuvarlanmadan kırpılır (456 -> 45). */
    fun avgSpeedKmh(raw: Int?): Int? = raw?.takeIf { it in 1..3000 }?.let { it / 10 }
}

/**
 * Yolculuk bilgisayarı verisi. Çevrimler Tiggo 7'de doğrulandı (2026-10-29, gösterge TripB ile eşleşti);
 * [driveAvgL100] hâlâ doğrulanmadı (okunan [0,0] = 0). [instantL100] duruyorken 0xFFFF (geçersiz) gelir.
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
        // Hız: gösterge hızı (119); eski sinyal (0) bu araçta hareket ederken 0/geçersiz kalıyordu ("Araç duruyor" hatası).
        // Anlık tüketim duruyorken 0xFFFF döner; geçerli geldiğinde araç hareket ediyordur.
        val instant = TripDecode.instantL100(ro(ReadOnlyID.ID_INSTANTANEOUS_CONSUMPTION))
        val speed = TripDecode.speed(ro(ReadOnlyID.ID_SPEED_GAUGE_DISPLAY), ro(ReadOnlyID.ID_CAR_SPEED))
        return TripData(
            connected = true,
            moving = TripDecode.moving(speed, instant),
            instantL100 = instant,
            driveAvgL100 = ro(ReadOnlyID.ID_AVERAGE_FUEL_CONS_AFTER_RUNNING)?.takeIf { it in 1..999 }?.let { it / 10.0 },
            tripKm = TripDecode.tripKm(ro(ReadOnlyID.ID_GRAND_TOTAL_KM_AFTER_CLEAR)),
            avgL100 = TripDecode.avgL100(ro(ReadOnlyID.ID_AVERAGE_OIL_CONSUMPTION_AFTER_CLEAR)),
            avgSpeedKmh = TripDecode.avgSpeedKmh(ro(ReadOnlyID.ID_AVERAGE_SPEED_AFTER_CLEAR)),
            batteryV = ReadOnlyDecode.batteryVolts(ro(ReadOnlyID.ID_BATTERY_LEVEL)),
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

/**
 * Yolculuk ailesinin (tüketim, yolculuk, sağlık) ortak kartı. İçerik her boyutta kartın ortasındadır:
 * başlık satırı (küçük ikon + etiket), büyük değer, isteğe bağlı küçük alt başlık. 3 sütun ve üzerinde
 * sağda ince bir ayraçla ayrılmış, dikey ortalanmış bir değer bloğu daha vardır; kart iki yarıya bölünüp
 * dolu görünür. Yalnızca okur; yeni veri uydurmaz.
 */
internal abstract class TripCard(
    protected val context: Context,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
) : SizedWidget {

    protected var data = TripData()
        private set

    /** Sağ blok gösterilen boyut (>= 3 sütun). */
    protected var wide = false
        private set

    private val listener = TripSource.Listener {
        data = it
        bind()
    }

    protected val headerIcon: ImageView = icon(context, iconRes, TITLE_ICON_DP, LauncherTheme.TEXT_SECONDARY)
    private val titleText = text(context, TITLE_SP, LauncherTheme.TEXT_SECONDARY).apply { setText(labelRes) }
    private val valueText = text(context, VALUE_SP, medium = true).apply { gravity = Gravity.CENTER }
    private val subtitleText = text(context, TITLE_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 2).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val mainColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(headerIcon)
            addView(gap(context, 8))
            addView(titleText)
        })
        addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(subtitleText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private val divider = View(context).apply {
        setBackgroundColor(LauncherTheme.TRACK)
        visibility = View.GONE
    }

    /** Sağ blok: dikey ortalanmış, başa hizalı etiketli satırlar. */
    private val rightBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    protected val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
        gravity = Gravity.CENTER_VERTICAL
        addView(mainColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(divider, LinearLayout.LayoutParams(context.dp(1), context.dp(DIVIDER_DP)).apply {
            marginStart = context.dp(8)
            marginEnd = context.dp(16)
        })
        addView(rightBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, RIGHT_WEIGHT))
    }

    override val view: View get() = root

    override fun onVisible() = TripSource.add(context, listener)

    override fun onHidden() = TripSource.remove(listener)

    override fun release() = TripSource.remove(listener)

    override fun applySpan(span: Span) {
        wide = span.w >= 3
        bind()
    }

    protected abstract fun bind()

    protected fun one(value: Double): String = String.format(java.util.Locale.getDefault(), "%.1f", value)

    protected fun setIcon(@DrawableRes res: Int, tint: Int) {
        headerIcon.setImageResource(res)
        headerIcon.imageTintList = ColorStateList.valueOf(tint)
    }

    protected fun setValue(value: CharSequence, color: Int, sizeSp: Float = VALUE_SP) {
        valueText.textSize = sizeSp
        valueText.setTextColor(color)
        valueText.setTextSmooth(unitStyled(value))
    }

    protected fun setSubtitle(subtitle: CharSequence?, color: Int = LauncherTheme.TEXT_SECONDARY) {
        subtitleText.setTextColor(color)
        subtitleText.text = subtitle
        subtitleText.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Sağ blok satırları (etiket null ise yalnız değer). Dar boyutta ya da satır yoksa blok ve ayraç gizlenir
     * ve sol sütun tüm kartı alır.
     */
    protected fun showRows(rows: List<Pair<String?, String>>) {
        rightBlock.removeAllViews()
        val show = wide && rows.isNotEmpty()
        rows.forEachIndexed { i, (label, value) ->
            rightBlock.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (label != null) addView(text(context, TITLE_SP, LauncherTheme.TEXT_SECONDARY).apply { text = label })
                addView(unitRow(value))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = context.dp(6)
            })
        }
        rightBlock.visibility = if (show) View.VISIBLE else View.GONE
        divider.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** "7,9 L/100 km" -> sayı büyük, birim küçük ve ikincil tonda. */
    private fun unitRow(value: String) = text(context, ROW_VALUE_SP, medium = true).apply {
        val i = value.indexOf(' ')
        text = if (i < 0) value else android.text.SpannableStringBuilder(value).apply {
            setSpan(android.text.style.RelativeSizeSpan(0.75f), i, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(LauncherTheme.TEXT_SECONDARY), i, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    protected fun describe(vararg parts: String?) {
        root.contentDescription = parts.filterNotNull().joinToString(", ")
    }

    protected fun showMissing() {
        setValue(context.getString(R.string.launcher_unavailable), LauncherTheme.TEXT_SECONDARY)
        setSubtitle(context.getString(R.string.launcher_waiting_data))
        showRows(emptyList())
        describe(context.getString(R.string.launcher_waiting_data))
    }

    protected companion object {
        /** Okunurluk alt sınırı: 2×2 kartta değer en az 28 sp, etiketler en az 18 sp. */
        const val VALUE_SP = 44f
        const val TITLE_SP = 18f
        const val ROW_VALUE_SP = 28f
        const val TITLE_ICON_DP = 24
        const val PADDING_DP = 12
        const val DIVIDER_DP = 64
        const val RIGHT_WEIGHT = 1f
    }
}

/**
 * Anlık tüketim: yalnızca büyük, okunur bir rakam. Araç duruyorsa "Araç duruyor". Renk değişmez:
 * sürücüyü uyarmaz, bilgi verir.
 */
internal class ConsumptionTile(context: Context) :
    TripCard(context, R.drawable.ic_mdi_leaf, R.string.launcher_widget_consumption) {

    override fun bind() {
        val now = data.instantL100
        when {
            data.connected && now == null && data.moving == false -> {
                val idle = context.getString(R.string.launcher_consumption_idle)
                setValue(idle, LauncherTheme.TEXT_SECONDARY, IDLE_SP)
                setSubtitle(null)
                describe(context.getString(R.string.launcher_widget_consumption), idle)
            }
            !data.connected || now == null -> showMissing()
            else -> {
                val value = context.getString(R.string.launcher_l100_value, one(now))
                setValue(value, LauncherTheme.TEXT)
                setSubtitle(null)
                describe(context.getString(R.string.launcher_widget_consumption), value)
            }
        }
        showRows(emptyList())
    }

    private companion object {
        const val IDLE_SP = 32f
    }
}

/**
 * Yolculuk: gösterge TripB sayfası gibi "Sıfırlamadan beri". 2×2'de başlık, büyük mesafe ve alt başlık;
 * 3×2'de mesafe solda, sağda ortalama tüketim ve ortalama hız.
 */
internal class TripComputerTile(context: Context) :
    TripCard(context, R.drawable.ic_mdi_road_variant, R.string.launcher_widget_trip) {

    override fun bind() {
        val km = data.tripKm
        if (!data.connected || km == null) {
            showMissing()
            return
        }
        val distance = context.getString(R.string.launcher_km_value, one(km))
        setValue(distance, LauncherTheme.TEXT)
        setSubtitle(context.getString(R.string.launcher_trip_since_reset))
        val entries = listOfNotNull(
            data.avgL100?.let { context.getString(R.string.launcher_trip_avg_cons_label) to context.getString(R.string.launcher_l100_value, one(it)) },
            data.avgSpeedKmh?.let { context.getString(R.string.launcher_trip_avg_speed_label) to context.getString(R.string.launcher_kmh_value, it) },
        )
        showRows(entries)
        describe(context.getString(R.string.launcher_trip_since_reset), distance, *entries.map { "${it.first} ${it.second}" }.toTypedArray())
    }
}

/**
 * Araç sağlığı: tek bir durum ("İyi" / "Kontrol et"). Yalnızca eldeki veriden türetilir, araca bir şey göndermez:
 * akü 12,0-15,0 V dışı, motor suyu > 110 °C ya da yakıt azaldı uyarısı "Kontrol et" yapar.
 * 2×2'de durum sözcüğü büyük ve ortada (sorun varsa sebebi altında); 3×2'de sağda akü gerilimi ve motor suyu.
 */
internal class HealthTile(context: Context) :
    TripCard(context, R.drawable.ic_mdi_lightning_bolt, R.string.launcher_widget_health) {

    override fun bind() {
        val v = data.batteryV
        val coolant = data.coolantC
        val lowFuel = data.lowFuel
        if (!data.connected || (v == null && coolant == null && lowFuel == null)) {
            setIcon(R.drawable.ic_mdi_lightning_bolt, LauncherTheme.TEXT_SECONDARY)
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
        val status = context.getString(if (bad) R.string.launcher_health_check else R.string.launcher_health_good)
        setValue(status, if (bad) LauncherTheme.ALERT else LauncherTheme.TEXT, if (bad && wide) STATUS_WIDE_BAD_SP else STATUS_SP)
        val why = reasons.joinToString(", ") { context.getString(it) }
        setSubtitle(why.ifEmpty { null }, LauncherTheme.ALERT)
        val rows = listOfNotNull(
            v?.let { context.getString(R.string.launcher_health_row_battery) to context.getString(R.string.launcher_health_volt_only, one(it)) },
            coolant?.let { context.getString(R.string.launcher_health_row_engine) to context.getString(R.string.launcher_temp_c, it) },
        )
        showRows(rows)
        describe(context.getString(R.string.launcher_widget_health), status, why.ifEmpty { null }, *rows.map { it.second }.toTypedArray())
    }

    private companion object {
        const val STATUS_SP = 44f
        /** 3×2'de sol yarı dar kalır: uzun "Kontrol et" bu boyda sığar. */
        const val STATUS_WIDE_BAD_SP = 32f
        const val BATTERY_MIN_V = 12.0
        const val BATTERY_MAX_V = 15.0
        const val COOLANT_MAX_C = 110
    }
}
