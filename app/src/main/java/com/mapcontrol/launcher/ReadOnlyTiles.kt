package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import androidx.annotation.StringRes
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Yalnızca okuyan yeni kutucuklar: mola, hız limiti, akü, telefon, saat, gövde.
 * Araca hiçbir şey yazılmaz; hepsi [ExtraSource] turundan beslenir. Geçersiz ya da bilinmeyen değer
 * "—" olarak görünür, uydurma sayı gösterilmez.
 */

/**
 * Bu dosyadaki sinyallerin ham değerden çözümü (saf işlevler, JVM'de sınanır).
 *
 * Araçta (Tiggo 7, 2026-10-29/30 kayıtları) ölçülenler: DRIVE_TIME, el freni, kaput, kapı, cam (2 = kapalı).
 * Doğrulanmayanlar her işlevin belgesinde "varsayım" diye işaretlidir.
 */
internal object ReadOnlyDecode {

    /** Kesintisiz sürüş bu süreye (dakika) ulaşınca mola uyarısı çıkar. */
    const val BREAK_AFTER_MIN = 120

    /**
     * Sürüş süresi (ReadOnly 29): dört bayt [b0, b1, dk, sn]. 2026-10-30 kaydı: [0,0,29,45] -> [0,0,29,51] -> ... -> [0,0,30,3],
     * yani üçüncü bayt dakika, dördüncü saniye (60'ta devreder); OEM günlüğü de aynı dakikayı "mDriveMin" diye yazıyor.
     * VARSAYIM (doğrulanmadı): saat ilk iki baytta (b0*256+b1); 1 saati geçen bir sürüş henüz kaydedilmedi.
     * Dakika baytı 60'ı aşarsa (saat ayrı sayılmıyorsa) da toplam doğru çıksın diye dakika 255'e kadar kabul edilir.
     * Toplam dakika döner; boyut/aralık bozuksa null.
     */
    fun driveMinutes(raw: IntArray?): Int? {
        if (raw == null || raw.size != 4) return null
        if (raw.any { it !in 0..255 }) return null
        val hours = raw[0] * 256 + raw[1]
        val minutes = raw[2]
        val seconds = raw[3]
        if (seconds > 59 || hours > 99) return null
        if (raw.all { it == 255 }) return null
        return hours * 60 + minutes
    }

    fun breakDue(minutes: Int?): Boolean = minutes != null && minutes >= BREAK_AFTER_MIN

    /**
     * Hız limiti (CarSetting 86): km/sa doğrudan. 0 = limit yok; 5..200 dışı (0xFF, 0xFFFF) geçersiz.
     * Durum sinyali (85) araçta hep 0 geldi; anlamı bilinmediği için limit onsuz da gösterilir.
     */
    fun speedLimit(raw: Int?): Int? = raw?.takeIf { it in 5..200 }

    /** Akü gerilimi (ReadOnly 76, BATTERY_LEVEL): ham/10 V. Araçta motor çalışırken [0,142] -> 14,2 V. 8,0-16,5 V dışı geçersiz. */
    fun batteryVolts(raw: Int?): Double? = raw?.takeIf { it in 80..165 }?.let { it / 10.0 }

    /**
     * Akü şarjı (ReadOnly 85, IBS_SOC): 1..100 %. Araçta hep [0] okundu; 0 "desteklenmiyor" sayılır (boş aküyü
     * 12 V sistemde göstermek yanıltıcı olurdu), yani null döner.
     */
    fun batterySoc(raw: Int?): Int? = raw?.takeIf { it in 1..100 }

    /** Akü uyarısı: gerilim 11,0 V altında ya da 15,5 V üstünde, ya da şarj %30 altında. */
    fun batteryLow(volts: Double?, soc: Int?): Boolean =
        (volts != null && (volts < 11.0 || volts > 15.5)) || (soc != null && soc < 30)

    const val PAD_EMPTY = 0
    const val PAD_CHARGING = 1
    const val PAD_PRESENT = 2

    /**
     * Kablosuz şarj durumu (CarSetting 44): 0 pedde telefon yok (araçta ölçüldü).
     * VARSAYIM: 1 = şarj oluyor, 2..7 = telefon pedde ama şarj olmuyor; başka değer bilinmiyor (null).
     */
    fun padState(raw: Int?): Int? = when (raw) {
        0 -> PAD_EMPTY
        1 -> PAD_CHARGING
        in 2..7 -> PAD_PRESENT
        else -> null
    }

    /**
     * Telefon unutuldu mu: gerçek bayrak PHONE_FORGOT_ON_WIRLESS_BOARD (46) = 1 ("pedde unutuldu").
     * PHONE_FORGOT (47) araçta pedde telefon yokken de hep 1 okundu; bu yüzden hatırlatma AÇIK ayarı sayılır:
     * 47 = 0 ise hatırlatma kapalıdır ve uyarı çıkmaz. Bayrak okunamadıysa null.
     */
    fun phoneForgotten(onBoard: Int?, reminderOn: Int?): Boolean? = when (onBoard) {
        1 -> reminderOn != 0
        0 -> false
        else -> null
    }

    /**
     * Cam (CarSetting 100..103): 2 = kapalı (araçta ölçüldü); 100 için 2 -> 6 -> 4 -> 2 hareket gözlendi.
     * Kapalı olmayan her geçerli değer (1..15) açık / hareket sayılır. 0 ve aralık dışı: bilinmiyor (null).
     */
    fun windowOpen(raw: Int?): Boolean? = when (raw) {
        2 -> false
        in 1..15 -> true
        else -> null
    }

    /** Kapı, bagaj, kaput (ReadOnly): 0 kapalı, 1 açık (kapı/bagaj/kaput araçta ölçüldü). Başka değer: null. */
    fun flagOpen(raw: Int?): Boolean? = when (raw) {
        0 -> false
        1 -> true
        else -> null
    }

    /** Yakıt kapağı (CarSetting 224): VARSAYIM 0 kapalı, 1 açık (araçta yalnızca 0 görüldü). */
    fun fuelCapOpen(raw: Int?): Boolean? = flagOpen(raw)

    /** El freni (ReadOnly 75): park halinde 1 okundu, yani 1 = çekili (varsayım: 0 = inik). */
    fun handbrakeOn(raw: Int?): Boolean? = flagOpen(raw)
}

/** Gövdede açık olabilen parçalar; [label] kullanıcıya gösterilen ad. */
internal enum class BodyPart(@StringRes val label: Int) {
    DOOR_LF(R.string.launcher_door_lf),
    DOOR_RF(R.string.launcher_door_rf),
    DOOR_LR(R.string.launcher_door_lr),
    DOOR_RR(R.string.launcher_door_rr),
    TRUNK(R.string.launcher_trunk),
    WINDOW_LF(R.string.launcher_body_win_lf),
    WINDOW_RF(R.string.launcher_body_win_rf),
    WINDOW_LR(R.string.launcher_body_win_lr),
    WINDOW_RR(R.string.launcher_body_win_rr),
    HOOD(R.string.launcher_body_hood),
    FUEL_CAP(R.string.launcher_body_fuel_cap),
}

/**
 * [ExtraSource] verisi. Her alan null ise "okunamadı / bilinmiyor". [parts] gövde parçalarının açık (true) /
 * kapalı (false) durumunu taşır; hiç okunamayanlar haritada yoktur.
 */
internal data class ExtraData(
    val connected: Boolean = false,
    val driveMinutes: Int? = null,
    val speedLimit: Int? = null,
    val batteryV: Double? = null,
    val batterySoc: Int? = null,
    val padState: Int? = null,
    val phoneForgotten: Boolean? = null,
    val handbrakeOn: Boolean? = null,
    val parts: Map<BodyPart, Boolean> = emptyMap(),
) {
    /** Gövde hakkında en az bir parça okunabildi mi. */
    val bodyKnown: Boolean get() = parts.isNotEmpty()

    /** Açık parçalar, [BodyPart] sırasıyla. */
    fun openParts(): List<BodyPart> = BodyPart.values().filter { parts[it] == true }

    fun anyWindowOpen(): Boolean = openParts().any { it.name.startsWith("WINDOW_") }

    fun hoodOpen(): Boolean = parts[BodyPart.HOOD] == true
}

/**
 * Salt-okur sinyalleri 4 sn'de bir, yalnızca dinleyen bir widget varken okur (ana ekran gizlenince durur).
 * Tek okuyucu: tüm yeni kutucuklar ve araç kartı aynı turu paylaşır. Animasyon döngüsü yok.
 */
internal object ExtraSource {

    fun interface Listener {
        fun onExtra(data: ExtraData)
    }

    private const val POLL_MS = 4_000L
    private val RO = VDEventCarInfo.MODULE_READONLY_INFO
    private val CS = VDEventCarInfo.MODULE_CAR_SETTING

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ExtraSource").apply { isDaemon = true } }
    private var task: ScheduledFuture<*>? = null
    private var appContext: Context? = null

    @Volatile
    var last: ExtraData = ExtraData()
        private set

    fun add(context: Context, listener: Listener) {
        appContext = context.applicationContext
        listeners += listener
        listener.onExtra(if (LauncherDemo.enabled) ReadOnlyDemo.extra() else last)
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
            val data = if (LauncherDemo.enabled) ReadOnlyDemo.extra() else readAll()
            if (data == last && !LauncherDemo.enabled) return
            last = data
            main.post { listeners.forEach { it.onExtra(data) } }
        } catch (t: Throwable) {
            Log.w("ExtraSource", "poll başarısız", t)
        }
    }

    private fun readAll(): ExtraData {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) {
            appContext?.let { proxy.init(it) }
            return ExtraData()
        }
        fun ro(id: Int) = CarSignals.readInt(RO, id)
        fun cs(id: Int) = CarSignals.readInt(CS, id)
        val parts = buildMap {
            fun set(part: BodyPart, open: Boolean?) { if (open != null) this[part] = open }
            set(BodyPart.DOOR_LF, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_DRIVE_DOOR_STATE)))
            set(BodyPart.DOOR_RF, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_PASSENGER_DOOR_STATE)))
            set(BodyPart.DOOR_LR, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_LFFT_BEHIND_DOOR_STATE)))
            set(BodyPart.DOOR_RR, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_RIGHT_BEHIND_DOOR_STATE)))
            set(BodyPart.TRUNK, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_TRUNK_STATE)))
            set(BodyPart.WINDOW_LF, ReadOnlyDecode.windowOpen(cs(CarSettingID.ID_CAR_FL_WINDOW)))
            set(BodyPart.WINDOW_RF, ReadOnlyDecode.windowOpen(cs(CarSettingID.ID_CAR_FR_WINDOW)))
            set(BodyPart.WINDOW_LR, ReadOnlyDecode.windowOpen(cs(CarSettingID.ID_CAR_RL_WINDOW)))
            set(BodyPart.WINDOW_RR, ReadOnlyDecode.windowOpen(cs(CarSettingID.ID_CAR_RR_WINDOW)))
            set(BodyPart.HOOD, ReadOnlyDecode.flagOpen(ro(ReadOnlyID.ID_HOOD_STATE)))
            set(BodyPart.FUEL_CAP, ReadOnlyDecode.fuelCapOpen(cs(CarSettingID.ID_FUEL_FILLER_CAP)))
        }
        return ExtraData(
            connected = true,
            driveMinutes = ReadOnlyDecode.driveMinutes(CarSignals.read(RO, ReadOnlyID.ID_DRIVE_TIME)),
            speedLimit = ReadOnlyDecode.speedLimit(cs(CarSettingID.ID_NAV_SPEED_LIMIT)),
            batteryV = ReadOnlyDecode.batteryVolts(ro(ReadOnlyID.ID_BATTERY_LEVEL)),
            batterySoc = ReadOnlyDecode.batterySoc(ro(ReadOnlyID.ID_IBS_SOC)),
            padState = ReadOnlyDecode.padState(cs(CarSettingID.ID_CAR_WIRLESS_CHARGE_STATE)),
            phoneForgotten = ReadOnlyDecode.phoneForgotten(
                cs(CarSettingID.ID_CAR_PHONE_FORGOT_ON_WIRLESS_BOARD), cs(CarSettingID.ID_CAR_PHONE_FORGOT),
            ),
            handbrakeOn = ReadOnlyDecode.handbrakeOn(ro(ReadOnlyID.ID_HANDBRAKE_STATE)),
            parts = parts,
        )
    }
}

/** Emülatör demosu için makul değerler (bkz. [LauncherDemo]): 1 sa 12 dk sürüş, %85 akü, 50 limiti, pedde şarj olan telefon. */
internal object ReadOnlyDemo {
    fun extra(): ExtraData = ExtraData(
        connected = true,
        driveMinutes = 72,
        speedLimit = 50,
        batteryV = 14.1,
        batterySoc = 85,
        padState = ReadOnlyDecode.PAD_CHARGING,
        phoneForgotten = false,
        handbrakeOn = true,
        parts = BodyPart.values().associateWith { false },
    )
}

/** [CenteredTile] + [ExtraSource] dinleyicisi; alt sınıflar yalnızca [bind] yazar. */
internal abstract class ExtraTile(
    context: Context,
    iconRes: Int,
    labelRes: Int,
    /** 2x2'de uzun metin değerleri sığsın diye değer yazısı bu boyuta (sp) küçülür; null = varsayılan. */
    private val narrowValueSp: Float? = null,
) : CenteredTile(context, iconRes, labelRes) {

    protected var data = ExtraData()
        private set
    private var alertSurface = false
    private val listener = ExtraSource.Listener {
        data = it
        bind()
    }

    override fun applySpan(span: Span) {
        super.applySpan(span)
        narrowValueSp?.let { if (tier == 0) valueText.textSize = it }
    }

    override fun onVisible() = ExtraSource.add(context, listener)

    override fun onHidden() = ExtraSource.remove(listener)

    override fun release() = ExtraSource.remove(listener)

    override fun bindDetail() = bind()

    /** Sakin uyarı zemini: kart yüzeyi ALERT_SURFACE tonuna geçer; uyarı geçince normale döner. */
    protected fun setAlertSurface(on: Boolean) {
        if (on == alertSurface && view.background != null) return
        alertSurface = on
        view.background = if (on) cardSurface(context, LauncherTheme.ALERT_SURFACE) else cardSurface(context)
    }

    protected abstract fun bind()
}

/**
 * Mola hatırlatıcı: sürüş süresi (ReadOnly DRIVE_TIME) "1 sa 12 dk" + "sürüş". [ReadOnlyDecode.BREAK_AFTER_MIN]
 * dakikayı geçince sakin ALERT zeminine ve "Mola zamanı"na geçer. Süre OEM sayacıdır (kontak açılınca başlar);
 * gerçek mola algılanmaz. Birimler araçta doğrulandı (dk/sn), 1 saat üstü doğrulanmadı.
 */
internal class BreakTile(context: Context) :
    ExtraTile(context, R.drawable.ic_mdi_timer_sand, R.string.launcher_widget_break, narrowValueSp = 32f) {

    override fun bind() {
        val minutes = data.driveMinutes
        if (!data.connected || minutes == null) {
            setAlertSurface(false)
            showMissing()
            return
        }
        val duration = durationText(minutes)
        if (ReadOnlyDecode.breakDue(minutes)) {
            setAlertSurface(true)
            setIcon(R.drawable.ic_mdi_alert, LauncherTheme.ALERT)
            setValue(context.getString(R.string.launcher_break_now), LauncherTheme.ALERT)
            showDetail(context.getString(R.string.launcher_break_driving_for, duration), LauncherTheme.ALERT)
        } else {
            setAlertSurface(false)
            setIcon(R.drawable.ic_mdi_timer_sand, LauncherTheme.TEXT_SECONDARY)
            setValue(duration, LauncherTheme.TEXT)
            showDetail(context.getString(R.string.launcher_break_driving))
        }
    }

    private fun durationText(minutes: Int): String =
        if (minutes >= 60) context.getString(R.string.launcher_break_hm, minutes / 60, minutes % 60)
        else context.getString(R.string.launcher_break_m, minutes)
}

/**
 * Hız limiti: trafik levhası görünümlü yuvarlak (kırmızı halka, beyaz zemin, siyah sayı). Limit yok ya da 0 ise
 * gri halka ve "—". Kaynak CarSetting NAV_SPEED_LIMIT; Tiggo 7'de bu sinyal hep 0 okundu (gösterge tabelası başka
 * yoldan geliyor), yani araçta dolu geldiği doğrulanmadı.
 */
internal class SpeedLimitTile(private val context: Context) : SizedWidget {

    private val sign = SignView(context)
    private val headerIcon = icon(context, R.drawable.ic_mdi_speedometer, HEADER_ICON_DP, LauncherTheme.TEXT_SECONDARY)
    private val label = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
        setText(R.string.launcher_widget_speed_limit)
    }
    private var data = ExtraData()
    private val listener = ExtraSource.Listener {
        data = it
        bind()
    }

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(headerIcon)
            addView(gap(context, 10))
            addView(label)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(sign, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = context.dp(6)
        })
    }

    override val view: View get() = root

    override fun applySpan(span: Span) = bind()

    override fun onVisible() = ExtraSource.add(context, listener)

    override fun onHidden() = ExtraSource.remove(listener)

    override fun release() = ExtraSource.remove(listener)

    private fun bind() {
        val limit = if (data.connected) data.speedLimit else null
        sign.set(limit)
        root.contentDescription = if (limit != null) context.getString(R.string.launcher_speed_limit_cd, limit)
        else context.getString(R.string.launcher_waiting_data)
    }

    /** Tabela: yalnızca değer değişince yeniden çizilir, animasyon yok. */
    private class SignView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private var limit: Int? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun set(value: Int?) {
            if (value == limit) return
            limit = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val d = minOf(width, height).toFloat()
            if (d <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            val stroke = d * RING_FRAC
            val r = d / 2f - stroke / 2f
            val v = limit
            ring.strokeWidth = stroke
            if (v != null) {
                fill.color = SIGN_WHITE
                canvas.drawCircle(cx, cy, r, fill)
                ring.color = SIGN_RED
                canvas.drawCircle(cx, cy, r, ring)
                number.color = SIGN_BLACK
                val text = v.toString()
                number.textSize = d * if (text.length >= 3) 0.34f else 0.46f
                canvas.drawText(text, cx, cy - (number.descent() + number.ascent()) / 2f, number)
            } else {
                ring.color = LauncherTheme.TRACK
                canvas.drawCircle(cx, cy, r, ring)
                number.color = LauncherTheme.TEXT_SECONDARY
                number.textSize = d * 0.40f
                canvas.drawText(context.getString(R.string.launcher_unavailable), cx, cy - (number.descent() + number.ascent()) / 2f, number)
            }
        }
    }

    private companion object {
        const val HEADER_ICON_DP = 28
        const val PADDING_DP = 16
        const val RING_FRAC = 0.13f
        const val SIGN_RED = 0xFFD62828.toInt()
        const val SIGN_WHITE = 0xFFFFFFFF.toInt()
        const val SIGN_BLACK = 0xFF111111.toInt()
    }
}

/**
 * Akü: şarj yüzdesi büyük ("%85"), gerilim küçük ("14,1 V"). Şarj (IBS_SOC) Tiggo 7'de hep 0 okundu ve
 * desteklenmiyor sayılır; o zaman gerilim büyük görünür. Gerilim çözümü sağlık kutucuğuyla aynıdır (ham/1000).
 */
internal class BatteryTile(context: Context) :
    ExtraTile(context, R.drawable.ic_mdi_lightning_bolt, R.string.launcher_widget_battery) {

    override fun bind() {
        val volts = data.batteryV
        val soc = data.batterySoc
        if (!data.connected || (volts == null && soc == null)) {
            setAlertSurface(false)
            showMissing()
            return
        }
        val low = ReadOnlyDecode.batteryLow(volts, soc)
        val voltText = volts?.let { context.getString(R.string.launcher_health_volt_only, String.format(Locale.getDefault(), "%.1f", it)) }
        setAlertSurface(false)
        setIcon(if (low) R.drawable.ic_mdi_alert else R.drawable.ic_mdi_lightning_bolt,
            if (low) LauncherTheme.ALERT else LauncherTheme.TEXT_SECONDARY)
        val color = if (low) LauncherTheme.ALERT else LauncherTheme.TEXT
        if (soc != null) {
            setValue(context.getString(R.string.launcher_percent, soc), color)
            showDetail(voltText)
        } else {
            setValue(voltText.orEmpty(), color)
            showDetail(null)
        }
    }
}

/**
 * Telefon hatırlatıcı: kablosuz şarj padinin durumu ("Telefon şarjda" / "Şarj pedi boş"). Pedde unutulan telefon
 * bayrağı (PHONE_FORGOT_ON_WIRLESS_BOARD) kalkarsa sakin ALERT zeminiyle "Telefonunu al". Araçta yalnızca pedin
 * boş (0) ve telefon pedde (1) durumları araçta ölçüldü (CarSetting 44).
 */
internal class PhoneTile(context: Context) :
    ExtraTile(context, R.drawable.ic_mdi_cellphone, R.string.launcher_widget_phone, narrowValueSp = 30f) {

    override fun bind() {
        val pad = data.padState
        val forgot = data.phoneForgotten == true
        if (!data.connected || (pad == null && data.phoneForgotten == null)) {
            setAlertSurface(false)
            showMissing()
            return
        }
        if (forgot) {
            setAlertSurface(true)
            setIcon(R.drawable.ic_mdi_alert, LauncherTheme.ALERT)
            setValue(context.getString(R.string.launcher_phone_take), LauncherTheme.ALERT)
            showDetail(context.getString(R.string.launcher_phone_on_pad), LauncherTheme.ALERT)
            return
        }
        setAlertSurface(false)
        setIcon(R.drawable.ic_mdi_cellphone, LauncherTheme.TEXT_SECONDARY)
        val state = when (pad) {
            ReadOnlyDecode.PAD_CHARGING -> R.string.launcher_phone_charging
            ReadOnlyDecode.PAD_PRESENT -> R.string.launcher_phone_on_pad
            ReadOnlyDecode.PAD_EMPTY -> R.string.launcher_phone_pad_empty
            else -> null
        }
        if (state == null) {
            showMissing()
            return
        }
        setValue(context.getString(state), if (pad == ReadOnlyDecode.PAD_EMPTY) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        showDetail(null)
    }
}

/**
 * Gövde durumu: kapılar, bagaj, kaput, camlar ve yakıt kapağı. Hepsi kapalıysa sakin "Hepsi kapalı" (altında el freni
 * durumu); açık olan varsa ALERT renginde tek parçanın adı ("Kaput açık") ya da sayı + liste. El freni uyarı değildir.
 */
internal class BodyTile(context: Context) :
    ExtraTile(context, R.drawable.ic_mdi_car_door, R.string.launcher_widget_body, narrowValueSp = 32f) {

    override fun bind() {
        if (!data.connected || !data.bodyKnown) {
            setAlertSurface(false)
            showMissing()
            return
        }
        val open = data.openParts()
        if (open.isEmpty()) {
            setAlertSurface(false)
            setIcon(R.drawable.ic_mdi_car_door, LauncherTheme.TEXT_SECONDARY)
            setValue(context.getString(R.string.launcher_body_all_closed), LauncherTheme.TEXT)
            showDetail(data.handbrakeOn?.let {
                context.getString(if (it) R.string.launcher_body_handbrake_on else R.string.launcher_body_handbrake_off)
            })
            return
        }
        setAlertSurface(true)
        setIcon(R.drawable.ic_mdi_alert, LauncherTheme.ALERT)
        val names = open.map { context.getString(it.label) }
        if (open.size == 1) {
            setValue(context.getString(R.string.launcher_part_open, names[0]), LauncherTheme.ALERT)
            showDetail(null)
        } else {
            setValue(context.getString(R.string.launcher_doors_open_count, open.size), LauncherTheme.ALERT)
            showDetail(names.joinToString(", "), LauncherTheme.ALERT)
        }
    }
}

/**
 * Büyük saat: ortalı saat ve tarih. Araç verisi yok; [TextClock] dakikada bir sistem yayınıyla güncellenir
 * (kendi zamanlayıcımız ya da animasyon yok). 2x2'de saat 56sp, 4x2'de 88sp.
 */
internal class ClockTile(private val context: Context) : SizedWidget {

    private val time = TextClock(context).apply {
        format12Hour = "HH:mm"
        format24Hour = "HH:mm"
        setTextColor(LauncherTheme.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LauncherTheme.TIME_SP)
    }
    private val datePattern: String =
        android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEE d MMMM")
    /** Geniş kutuda tarih iki satır: gün adı üstte, gün ve ay altta (yerel desenlerin ikisi ayrı alınır). */
    private val dateWidePattern: String =
        android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEE") + "'\n'" +
            android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMMM")
    private val date = TextClock(context).apply {
        format12Hour = datePattern
        format24Hour = datePattern
        setTextColor(LauncherTheme.TEXT_SECONDARY)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LauncherTheme.LABEL_SP)
    }

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER
        minimumHeight = context.dp(LauncherTheme.TOUCH_MIN_DP)
    }

    override val view: View get() = root

    /** 2x2: saat üstte, tarih altta (56sp). 4x2: saat solda 80sp, tarih sağda iki satır; kutu alçak olduğundan yan yana. */
    override fun applySpan(span: Span) {
        val wide = span.w >= WIDE_MIN_COLS
        root.removeAllViews()
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        if (wide) {
            root.orientation = LinearLayout.HORIZONTAL
            time.gravity = Gravity.CENTER
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, WIDE_TIME_SP)
            date.format12Hour = dateWidePattern
            date.format24Hour = dateWidePattern
            date.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LauncherTheme.SECTION_SP * 0.75f)
            root.addView(time, LinearLayout.LayoutParams(wrap, wrap))
            root.addView(gap(context, 32))
            root.addView(date, LinearLayout.LayoutParams(wrap, wrap))
        } else {
            root.orientation = LinearLayout.VERTICAL
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LauncherTheme.TIME_SP)
            date.format12Hour = datePattern
            date.format24Hour = datePattern
            date.gravity = Gravity.CENTER
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, LauncherTheme.LABEL_SP)
            root.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, wrap))
            root.addView(date, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, wrap).apply { topMargin = context.dp(6) })
        }
    }

    override fun onVisible() {}

    override fun onHidden() {}

    private companion object {
        const val PADDING_DP = 16
        const val WIDE_TIME_SP = 80f
    }
}
