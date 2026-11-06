package com.mapcontrol.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mapcontrol.R
import com.mapcontrol.weather.OpenMeteoWeatherClient
import com.mapcontrol.weather.OpenMeteoWeatherLabels
import com.mapcontrol.weather.OpenMeteoWeatherSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Üst şerit (sol direksiyonlu araç için): solda dokunma işleri (ayarlar, Wi-Fi, düzenle), ortada saat,
 * sağda yalnızca bakılan bilgi (tarih, hava durumu). Wi-Fi ve Bluetooth tek kapsülde, iki ayrı dokunma alanı; telefon pedde şarj olurken kapsüle üçüncü bir gösterge eklenir.
 * Saat sistemin dakika yayınıyla (ACTION_TIME_TICK) güncellenir; kendi zamanlayıcısı yok.
 */
class HeaderBar(
    private val context: Context,
    private val onSettings: () -> Unit,
    private val onEditHome: () -> Unit,
    private val onProfile: () -> Unit = {},
    private val onWifi: (View) -> Unit = {},
    /** Bluetooth yarısına dokunma: launcher içindeki Bluetooth paneli (simgenin altında); verilmezse eski yol (OEM uygulaması). */
    private val onBluetooth: ((View) -> Unit)? = null,
) : LauncherWidget, Themeable {

    private val time = text(context, LauncherTheme.TIME_SP, medium = true)
    private val date = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
    private val weatherTemp = text(context, LauncherTheme.BODY_SP, medium = true)
    private val weatherLabel = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
    private val weatherBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
    private val weatherIcon = icon(context, R.drawable.ic_mdi_thermometer, 32, LauncherTheme.TEXT)
    private val weatherClient = OpenMeteoWeatherClient(context)
    private var weatherStart: LauncherStartup.Deferred? = null
    private var tickRegistered = false

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = updateClock()
    }

    private val weatherListener = object : OpenMeteoWeatherClient.Listener {
        override fun onWeatherUpdated(snapshot: OpenMeteoWeatherSnapshot) = bindWeather(snapshot)
        override fun onWeatherFailed() {
            // Son bilinen hava durumu kalır; hiç yoksa alan gizli kalır.
        }
    }

    /** Wi-Fi durumu (bağlı + seviye, bağlı değil, hotspot); dokununca Wi-Fi paneli. Olaylarla güncellenir, polling yok. */
    private val wifiGlyph = WifiGlyph(context)
    private var wifiUi = WifiUi(WifiUi.Kind.OFF)
    private val wifiStatus = WifiStatus(context) { if (it != wifiUi) bindWifi(it) }

    /**
     * FrostNav web paneli sunucusu çalışıyorsa Wi-Fi simgesinde küçük nokta. Durum sunucunun sahibinden okunur
     * ([FrostNavFeatures.readWebPanel]); başlık görünürken değişince hemen ([FrostNavFeatures.watchWebPanel]), yedek olarak
     * [WEB_POLL_MS]'de bir, arka planda.
     */
    private val features = LauncherEdition.frostNav(context)
    private val webIo = LauncherIo.serial()
    private var webUnwatch: (() -> Unit)? = null
    private val webHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val webPoll = object : Runnable {
        override fun run() {
            webIo.runSafely {
                val on = try { features.readWebPanel().running } catch (e: RuntimeException) { false }
                webHandler.post { setWebRunning(on) }
            }
            webHandler.postDelayed(this, WEB_POLL_MS)
        }
    }

    /** Web paneli durumu biliniyorsa (ör. Wi-Fi paneli okudu) noktayı hemen günceller. */
    fun setWebRunning(on: Boolean) {
        wifiGlyph.webDot = on
    }
    private val wifiButton = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onWifi(this) }
        pressFeedback()
    }

    /** Bluetooth durumu (kapalı, açık, telefon bağlı); dokununca launcher'dan çıkmadan Bluetooth paneli açılır. Olaylarla güncellenir. */
    private var btUi = BtUi.OFF
    private val btImage = icon(context, R.drawable.ic_mdi_bluetooth_off, LauncherTheme.ICON_M_DP + 4)
    private val btStatus = BluetoothStatus(context) { if ((LauncherDemo.bluetooth() ?: it) != btUi) bindBluetooth(it) }
    /** Kablosuz şarj göstergesi: yalnızca telefon pedde şarj olurken görünür (dokunulmaz); [ExtraSource]'tan beslenir. */
    private val chargeImage = icon(context, R.drawable.ic_mdi_cellphone_charging, LauncherTheme.ICON_M_DP + 4)
    private val chargeBox = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        contentDescription = context.getString(R.string.launcher_phone_charging_icon)
    }
    private val chargeDivider = View(context).apply { visibility = View.GONE }
    private val extraListener = ExtraSource.Listener { bindCharging(ReadOnlyDecode.phoneCharging(it.padState)) }
    private val btButton = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { v -> onBluetooth?.invoke(v) ?: openOemBluetooth(context) }
        pressFeedback()
    }

    /** Sol grup (dokunma): ayarlar, Wi-Fi, düzenle. Sürücü tarafı. */
    private val leftGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Sağ grup (yalnızca bakılır): sürüş rozeti, tarih, hava. */
    private val rightGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    override val view: View = FrameLayout(context).apply {
        // Sahne: cam düğmelerin gölgesi başlığın dışına taşar.
        if (LauncherTheme.sahne) {
            clipChildren = false
            leftGroup.clipChildren = false
            rightGroup.clipChildren = false
        }
        // Saat, sol/sağ grupların genişliğinden bağımsız olarak ekranın tam ortasında durur.
        addView(leftGroup, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(time, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(rightGroup, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        fill()
    }

    /** Sahne: cam yüzey (kare 22dp köşe, kapsül hap); Serbest: düz kart yüzeyi. */
    private fun surface(radiusDp: Float): android.graphics.drawable.Drawable =
        // Spor: sol düğmelerdeki talaşlı metal çerçevenin hap biçimi (hava ve bağlantı kapsülü aynı dilde).
        if (sport) SportBezelPillDrawable(context)
        else if (LauncherTheme.sahne) GlassDrawable(context, radiusDp, shadow = 0.55f, control = true)
        else rounded(context, LauncherTheme.CARD, radiusDp, LauncherTheme.CARD_BORDER)

    /** Sahne yazı stilleri: ince büyük saat, orta ağırlıkta tarih ve hava (taslak ölçüleri). */
    private fun styleTexts() {
        if (!LauncherTheme.sahne) return
        // Saat sakin: menzil tek kahraman kalsın (saat araçta başka yerde de var).
        time.textSize = 44f
        // Gece ince (300) saat koyu zeminde silikti: iki temada 400.
        SahneFonts.apply(time, 400, -0.02f, dayBump = false)
        date.textSize = 22f
        SahneFonts.apply(date, 400)
        SahneFonts.apply(weatherTemp, 600, dayBump = false)
        SahneFonts.apply(weatherLabel, 400, dayBump = false)
        time.setTextColor(Sahne.NUMERAL)
        date.setTextColor(if (noticeText == null) Sahne.HEADER_SUB else Sahne.INK)
        weatherLabel.setTextColor(Sahne.HEADER_SUB)
        weatherTemp.setTextColor(Sahne.INK)
    }

    /** Grupların çocuklarını kurar; saat/tarih/hava metin görünümleri korunur, düğmeler ve rozet o anki temayla yeniden kurulur. */
    private fun fill() {
        leftGroup.removeAllViews()
        rightGroup.removeAllViews()
        if (sport) { fillSport(); return }
        styleTexts()

        if (SHOW_PROFILE_IN_HEADER) {
            leftGroup.addView(profileButton())
            leftGroup.addView(gap(context, 16))
        }
        leftGroup.addView(settingsButton())
        leftGroup.addView(gap(context, 16))
        leftGroup.addView(connectivityCapsule())
        leftGroup.addView(gap(context, 16))
        leftGroup.addView(squareButton(if (LauncherTheme.sahne) R.drawable.ic_sahne_pen_fill else R.drawable.ic_mdi_pencil, context.getString(R.string.launcher_edit_title), onEditHome))

        driveBadge()?.let { rightGroup.addView(it) }
        rightGroup.addView(date)
        rightGroup.addView(gap(context, if (LauncherTheme.sahne) 20 else 24))
        rightGroup.addView(weatherCapsule())
    }

    // --- Spor modu ---

    /** Spor modunda (kronograf dili) mı: düğmeler talaşlı metal çerçeve, saat ince metal plakada, tarih saatin altında. */
    private var sport = false
    private val clockPlate = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    /**
     * Spor görünümüne geçer ya da geri döner. Yerleşim (sol düğmeler, ortada saat, sağda hava) ve dokunma alanları aynı;
     * yalnız yüzeyler, yazı ağırlıkları ve renkler değişir, tarih sağ gruptan saatin altına gelir. Geri dönüşte her şey
     * kurulumdaki haliyle yeniden kurulur.
     */
    fun setSport(on: Boolean) {
        if (sport == on) return
        sport = on
        val frame = view as FrameLayout
        (date.parent as? android.view.ViewGroup)?.removeView(date)
        if (on) {
            frame.removeView(time)
            clockPlate.removeAllViews()
            clockPlate.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            clockPlate.addView(date, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            clockPlate.background = SportPillDrawable(context, 16f, 2f)
            clockPlate.setPadding(context.dp(34), 0, context.dp(34), context.dp(3))
            frame.addView(clockPlate, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        } else {
            clockPlate.removeAllViews()
            frame.removeView(clockPlate)
            frame.addView(time, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Tarih sağ gruba ilk kurulumdaki (varsayılan) ölçülerle döner.
            date.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        weatherBox.removeAllViews()
        weatherBox.setPadding(0, 0, 0, 0)
        wifiGlyph.sportInk = if (on) SportInk.ICON else null
        fill()
        lastWeather?.let { bindWeather(it) }
        applyClockVisibility()
        view.invalidate()
    }

    /** Spor ekranında analog saat kadranı seçili: dijital saat spor modunda gizlenir, tarih kalır. */
    private var sportClockHidden = false

    fun setSportClockHidden(hidden: Boolean) {
        if (sportClockHidden == hidden) return
        sportClockHidden = hidden
        applyClockVisibility()
    }

    private fun applyClockVisibility() {
        val hide = sport && sportClockHidden
        time.visibility = if (hide) View.GONE else View.VISIBLE
        if (!sport) return
        // Yalnız tarih kalınca plaka daralır (yan kapsüllerle aynı boy), tarih ortada biraz büyük ve daha açık; saat varken
        // tarih saatin altında küçük ve sönük.
        date.textSize = if (hide) DATE_ONLY_SP else 14f
        date.setTextColor(if (noticeText != null) SportInk.INK else if (hide) SportInk.INK_2 else SportInk.INK_3)
        val padH = context.dp(if (hide) DATE_ONLY_PAD_DP else 34)
        clockPlate.setPadding(padH, 0, padH, if (hide) 0 else context.dp(3))
        (clockPlate.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val h = if (hide) context.dp(LauncherTheme.TOUCH_MIN_DP) else FrameLayout.LayoutParams.MATCH_PARENT
            if (lp.height != h) {
                lp.height = h
                clockPlate.layoutParams = lp
            }
        }
    }

    private fun fillSport() {
        time.textSize = 34f
        SahneFonts.apply(time, 300, 0.02f, dayBump = false)
        time.setTextColor(SportInk.INK)
        date.textSize = 14f
        SahneFonts.apply(date, 400, 0.04f, dayBump = false)
        date.setTextColor(if (noticeText == null) SportInk.INK_3 else SportInk.INK)
        SahneFonts.apply(weatherTemp, 600, dayBump = false)
        SahneFonts.apply(weatherLabel, 400, dayBump = false)
        weatherTemp.setTextColor(SportInk.INK)
        weatherLabel.setTextColor(SportInk.INK_2)
        leftGroup.addView(settingsButton())
        leftGroup.addView(gap(context, 16))
        leftGroup.addView(connectivityCapsule())
        leftGroup.addView(gap(context, 16))
        leftGroup.addView(squareButton(R.drawable.ic_sahne_pen_fill, context.getString(R.string.launcher_edit_title), onEditHome))
        rightGroup.addView(weatherCapsule())
        applyClockVisibility()
    }

    /** Hava kapsülü: yalnızca hava; veri gelene kadar gizli kalır. Dokunma işi yok, sağ grupta bakılır. */
    private fun weatherCapsule(): View {
        val h = context.dp(LauncherTheme.TOUCH_MIN_DP)
        val icon = context.dp(CLUSTER_ICON_DP)
        val sahne = LauncherTheme.sahne
        weatherTemp.textSize = if (sahne) 26f else CLUSTER_TEXT_SP
        weatherLabel.textSize = if (sahne) 22f else CLUSTER_TEXT_SP
        weatherBox.setPadding(context.dp(if (sahne) 22 else 24), 0, context.dp(if (sahne) 26 else 24), 0)
        weatherBox.background = surface(999f)
        weatherBox.addView(weatherIcon, LinearLayout.LayoutParams(icon, icon))
        weatherBox.addView(gap(context, if (sahne) 12 else 8))
        weatherBox.addView(weatherTemp)
        weatherBox.addView(gap(context, if (sahne) 12 else 8))
        weatherBox.addView(weatherLabel)
        weatherBox.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
        return weatherBox
    }

    /**
     * Bağlantı kapsülü: Wi-Fi ve Bluetooth tek sakin hapta, aralarında ince çizgi. Her yarı kendi dokunma alanını
     * (en az [LauncherTheme.TOUCH_MIN_DP] genişlik) ve basma geri bildirimini korur; Wi-Fi paneli Wi-Fi yarısının altında açılır.
     */
    private fun connectivityCapsule(): View {
        val size = context.dp(LauncherTheme.TOUCH_MIN_DP)
        val glyph = context.dp(if (LauncherTheme.sahne) 28 else LauncherTheme.ICON_M_DP + 4)
        (wifiGlyph.parent as? android.view.ViewGroup)?.removeView(wifiGlyph)
        (btImage.parent as? android.view.ViewGroup)?.removeView(btImage)
        (chargeImage.parent as? android.view.ViewGroup)?.removeView(chargeImage)
        (chargeBox.parent as? android.view.ViewGroup)?.removeView(chargeBox)
        (chargeDivider.parent as? android.view.ViewGroup)?.removeView(chargeDivider)
        // Yerinde tema yenilemesinde düğmeler hâlâ eski kapsüldedir: önce oradan çıkarılır (yoksa yeniden kurulum gerekirdi).
        (wifiButton.parent as? android.view.ViewGroup)?.removeView(wifiButton)
        (btButton.parent as? android.view.ViewGroup)?.removeView(btButton)
        wifiButton.removeAllViews()
        btButton.removeAllViews()
        chargeBox.removeAllViews()
        wifiButton.addView(wifiGlyph, LinearLayout.LayoutParams(glyph, glyph))
        btButton.addView(btImage, LinearLayout.LayoutParams(glyph, glyph))
        chargeBox.addView(chargeImage, LinearLayout.LayoutParams(glyph, glyph))
        if (LauncherTheme.sahne) chargeImage.setImageResource(R.drawable.ic_sahne_charge_fill)
        chargeImage.imageTintList = android.content.res.ColorStateList.valueOf(if (sport) SportInk.ICON else if (LauncherTheme.sahne) Sahne.HEADER_ICON else LauncherTheme.TEXT)
        val half = { LinearLayout.LayoutParams(if (LauncherTheme.sahne) context.dp(68) else size, LinearLayout.LayoutParams.MATCH_PARENT) }
        fun divider() = View(context).apply {
            setBackgroundColor(if (sport) 0x33FFFFFF else if (LauncherTheme.sahne) Sahne.CONTROL_BORDER else LauncherTheme.TEXT_SECONDARY)
            alpha = if (LauncherTheme.sahne) 1f else 0.3f
            layoutParams = LinearLayout.LayoutParams(context.dp(1), context.dp(28))
        }
        chargeDivider.apply {
            setBackgroundColor(if (sport) 0x33FFFFFF else if (LauncherTheme.sahne) Sahne.HAIR_2 else LauncherTheme.TEXT_SECONDARY)
            alpha = if (LauncherTheme.sahne) 1f else 0.3f
            layoutParams = LinearLayout.LayoutParams(context.dp(1), context.dp(28))
        }
        bindWifi(wifiUi)
        bindBluetooth(btUi)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = surface(999f)
            if (LauncherTheme.sahne) setPadding(context.dp(6), 0, context.dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, size)
            addView(wifiButton, half())
            addView(divider())
            addView(btButton, half())
            addView(chargeDivider)
            addView(chargeBox, half())
        }
    }

    private fun bindWifi(ui: WifiUi) {
        wifiUi = ui
        wifiGlyph.bind(ui)
        // Ağ adı başlıkta gösterilmez (yalnızca simge durumu); ad Wi-Fi panelindeki bağlı ağ satırındadır.
        wifiButton.alpha = if (ui.kind == WifiUi.Kind.OFF) 0.85f else 1f
        wifiButton.contentDescription = context.getString(when (ui.kind) {
            WifiUi.Kind.OFF -> R.string.launcher_wifi_off
            WifiUi.Kind.HOTSPOT -> R.string.launcher_wifi_hotspot
            WifiUi.Kind.CONNECTED -> R.string.launcher_wifi_connected
        })
    }

    private fun bindBluetooth(ui: BtUi) {
        btUi = LauncherDemo.bluetooth() ?: ui
        btImage.setImageResource(when (btUi) {
            BtUi.OFF -> R.drawable.ic_mdi_bluetooth_off
            // Sahne başlığında da dolu ikon (aracın alt çubuğundaki dolu ikon diliyle; kapalı/bağlı halleri zaten dolu).
            BtUi.ON -> R.drawable.ic_mdi_bluetooth
            BtUi.CONNECTED -> R.drawable.ic_mdi_bluetooth_connect
        })
        btImage.imageTintList = android.content.res.ColorStateList.valueOf(when {
            sport -> if (btUi == BtUi.OFF) (SportInk.ICON and 0x00FFFFFF) or 0xA6000000.toInt() else SportInk.ICON
            LauncherTheme.sahne -> if (btUi == BtUi.OFF) Sahne.HEADER_ICON_OFF else Sahne.HEADER_ICON
            btUi == BtUi.OFF -> LauncherTheme.TEXT_SECONDARY
            else -> LauncherTheme.TEXT
        })
        btButton.alpha = if (btUi == BtUi.OFF) 0.85f else 1f
        btButton.contentDescription = context.getString(when (btUi) {
            BtUi.OFF -> R.string.launcher_bt_off
            BtUi.ON -> R.string.launcher_bt_on
            BtUi.CONNECTED -> R.string.launcher_bt_connected
        })
    }

    /** Şarj göstergesi ve kendi ayırıcısı birlikte görünür/gizlenir. */
    private fun bindCharging(charging: Boolean) {
        val v = if (charging) View.VISIBLE else View.GONE
        chargeBox.visibility = v
        chargeDivider.visibility = v
    }

    private var lastWeather: OpenMeteoWeatherSnapshot? = null

    /** Tema değişti: metin renkleri, hap, hava ikonu ve düğmeler yeni renkle; etkinlik yeniden kurulmaz. */
    override fun applyTheme() {
        time.setTextColor(LauncherTheme.TEXT)
        date.setTextColor(if (noticeText == null) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        weatherLabel.setTextColor(LauncherTheme.TEXT_SECONDARY)
        weatherTemp.setTextColor(LauncherTheme.TEXT)
        weatherIcon.imageTintList = android.content.res.ColorStateList.valueOf(LauncherTheme.TEXT)
        lastWeather?.let { bindWeather(it) }
        weatherBox.removeAllViews()
        weatherBox.setPadding(0, 0, 0, 0)
        fill()
        view.invalidate()
    }

    /**
     * Sürüş modu rozeti: küçük mod ikonu (vurgu renginde) ve ad; yalnızca mod Normal değilse ve seçenek açıksa.
     * Mod değişince etkinlik yeniden kurulduğu için kurulumda okunur.
     */
    private fun driveBadge(): View? {
        val mode = LauncherTheme.driveMode
        if (!DriveAccent.hasBadge(mode) || !DriveAccent.enabled(context)) return null
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(context, DriveAccent.icon(mode), LauncherTheme.ICON_S_DP, LauncherTheme.ACCENT))
            addView(gap(context, 8))
            addView(text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, medium = true).apply {
                setText(DriveAccent.label(mode))
            })
            addView(gap(context, 24))
            contentDescription = context.getString(DriveAccent.label(mode))
        }
    }

    /** Profil düğmesi: geçerli sürücü numarası yuvarlak simgede; dokununca profil seçici açılır. */
    private fun profileButton(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        background = cardSurface(context)
        val size = context.dp(maxOf(LauncherTheme.TOUCH_MIN_DP, 76))
        layoutParams = LinearLayout.LayoutParams(size, size)
        // Ad/renk kaydedilince avatar yerinde tazelenir (görünürken dinler).
        val refresh = {
            val id = Profiles.current(context)
            removeAllViews()
            addView(profileAvatar(context, id, 44, selected = true))
            contentDescription = context.getString(R.string.launcher_profile) + ": " + Profiles.displayName(context, id)
        }
        refresh()
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { Profiles.addAppearanceListener(refresh); refresh() }
            override fun onViewDetachedFromWindow(v: View) { Profiles.removeAppearanceListener(refresh) }
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { onProfile() }
        pressFeedback()
    }

    /** Yalnızca ikonlu kare düğme; ayarlar düğmesiyle aynı yükseklik, zemin ve basma geri bildirimi. */
    private fun squareButton(@androidx.annotation.DrawableRes res: Int, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = if (sport) SportKnobDrawable(context, knurled = res == R.drawable.ic_sahne_gear_fill)
                else if (LauncherTheme.sahne) surface(Sahne.SQUARE_RADIUS_DP) else cardSurface(context)
            val size = context.dp(LauncherTheme.TOUCH_MIN_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(if (sport) icon(context, res, 26, SportInk.ICON)
                else if (LauncherTheme.sahne) icon(context, res, 28, Sahne.HEADER_ICON) else icon(context, res, LauncherTheme.ICON_M_DP))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    /** Yalnızca dişli simgesi; diğer düğmelerle aynı kare boyut (en az 72 px dokunma alanı). */
    private fun settingsButton(): View =
        squareButton(if (LauncherTheme.sahne) R.drawable.ic_sahne_gear_fill else R.drawable.ic_mdi_cog, context.getString(R.string.launcher_settings), onSettings)

    override fun onVisible() {
        updateClock()
        if (!tickRegistered) {
            context.registerReceiver(tickReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            })
            tickRegistered = true
        }
        weatherClient.setListener(weatherListener)
        weatherStart?.cancel()
        weatherStart = LauncherStartup.later { weatherClient.start() }
        wifiStatus.start()
        btStatus.start()
        ExtraSource.add(context, extraListener)
        webHandler.removeCallbacks(webPoll)
        if (features.available) {
            webHandler.post(webPoll)
            webUnwatch?.invoke()
            webUnwatch = features.watchWebPanel { webHandler.removeCallbacks(webPoll); webHandler.post(webPoll) }
        }
    }

    override fun onHidden() {
        if (tickRegistered) {
            context.unregisterReceiver(tickReceiver)
            tickRegistered = false
        }
        weatherStart?.cancel()
        weatherClient.stop()
        weatherClient.setListener(null)
        wifiStatus.stop()
        btStatus.stop()
        ExtraSource.remove(extraListener)
        webHandler.removeCallbacks(webPoll)
        webUnwatch?.invoke()
        webUnwatch = null
    }

    override fun release() {
        webHandler.removeCallbacks(webPoll)
        webUnwatch?.invoke()
        webUnwatch = null
        wifiStatus.stop()
        btStatus.stop()
        ExtraSource.remove(extraListener)
        weatherClient.destroy()
    }

    private val noticeRestore = Runnable { crossFadeDate(null, 0L) }

    /**
     * Kısa bilgi (ör. otomatik düzen geçişi): tarih metni [fadeMs] sürede soluklaşıp [res] metnine döner, [holdMs] kadar
     * durur, sonra tarih geri gelir. Ayrı görünüm yok; başlıktaki yer değişmez, kartların üstüne bir şey binmez.
     */
    fun showNotice(@androidx.annotation.StringRes res: Int, holdMs: Long, fadeMs: Long) {
        date.removeCallbacks(noticeRestore)
        crossFadeDate(context.getString(res), fadeMs)
        date.postDelayed(noticeRestore, holdMs + 2 * fadeMs)
    }

    /** [notice] null ise tarihe döner. */
    private fun crossFadeDate(notice: String?, fadeMs: Long) {
        val ms = if (fadeMs > 0) fadeMs else NOTICE_RESTORE_FADE_MS
        date.animate().cancel()
        date.animate().alpha(0f).setDuration(ms).withEndAction {
            noticeText = notice
            date.setTextColor(if (sport) (if (notice == null) SportInk.INK_3 else SportInk.INK)
                else if (notice == null) (if (LauncherTheme.sahne) Sahne.HEADER_SUB else LauncherTheme.TEXT_SECONDARY) else LauncherTheme.TEXT)
            updateClock()
            date.animate().alpha(1f).setDuration(ms).start()
        }.start()
    }

    private var noticeText: String? = null

    private fun updateClock() {
        val now = Date()
        // Saat dilimi değişmiş olabilir (ACTION_TIMEZONE_CHANGED); biçimleyiciler her seferinde güncellenir.
        java.util.TimeZone.getDefault().let { timeFormat.timeZone = it; dateFormat.timeZone = it }
        time.text = timeFormat.format(now)
        date.text = noticeText ?: dateFormat.format(now).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun bindWeather(snapshot: OpenMeteoWeatherSnapshot) {
        lastWeather = snapshot
        weatherTemp.text = context.getString(R.string.launcher_degrees, snapshot.temperatureC.roundToInt())
        weatherLabel.text = OpenMeteoWeatherLabels.labelFor(context, snapshot.weatherCode, snapshot.day)
        // Yalnızca elimizde doğru ikonu olan durum için hava ikonu; diğerlerinde nötr termometre.
        weatherIcon.setImageResource(when {
            LauncherTheme.sahne && snapshot.weatherCode in 45..48 -> R.drawable.ic_mdi_weather_fog
            LauncherTheme.sahne && snapshot.weatherCode in 1..3 -> R.drawable.ic_sahne_partly_cloudy_fill
            snapshot.weatherCode in 1..3 -> R.drawable.ic_mdi_weather_partly_cloudy
            else -> R.drawable.ic_mdi_thermometer
        })
        // Eski veri ikincil tonda; veri hiç yoksa grup gizli kalır.
        val updatedAt = weatherClient.lastUpdatedMs
        val stale = updatedAt > 0L && System.currentTimeMillis() - updatedAt > WEATHER_STALE_MS
        weatherTemp.setTextColor(if (stale) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        weatherIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (LauncherTheme.sahne) Sahne.HEADER_ICON else if (stale) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        if (sport) {
            weatherTemp.setTextColor(if (stale) SportInk.INK_3 else SportInk.INK)
            weatherIcon.imageTintList = android.content.res.ColorStateList.valueOf(SportInk.ICON)
        }
        weatherBox.visibility = View.VISIBLE
    }
}

/**
 * Profil düğmesi başlıkta şimdilik gizli (başlık sadeleştirildi). Profil özelliği ([Profiles], [showProfileSheet])
 * yerinde duruyor; geri getirmek için true yapın.
 */
private const val SHOW_PROFILE_IN_HEADER = false

private const val NOTICE_RESTORE_FADE_MS = 250L
private const val CLUSTER_ICON_DP = 28
private const val CLUSTER_TEXT_SP = 24f
private const val WEB_POLL_MS = 5_000L
/** Spor plakasında yalnız tarih kalınca (analog saat kadranı seçili): tarih boyu ve plakanın yan dolgusu. */
private const val DATE_ONLY_SP = 18f
private const val DATE_ONLY_PAD_DP = 24
