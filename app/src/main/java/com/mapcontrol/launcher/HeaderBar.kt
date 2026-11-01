package com.mapcontrol.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Gravity
import android.view.View
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
 * Üst şerit: solda saat ve tarih; sağda Park/Sürüş hapı, hava durumu, Wi-Fi, düzenle ve ayarlar (dişli).
 * Saat sistemin dakika yayınıyla (ACTION_TIME_TICK) güncellenir; kendi zamanlayıcısı yok.
 */
class HeaderBar(
    private val context: Context,
    private val onSettings: () -> Unit,
    private val onEditHome: () -> Unit,
    private val onProfile: () -> Unit = {},
    private val onLayoutPill: () -> Unit = {},
    private val onWifi: (View) -> Unit = {},
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

    /** Wi-Fi durumu (bağlı + seviye, bağlı değil, hotspot); dokununca Wi-Fi sekmesi. Olaylarla güncellenir, polling yok. */
    private val wifiGlyph = WifiGlyph(context)
    private val wifiName = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY, medium = true)
    private var wifiUi = WifiUi(WifiUi.Kind.OFF)
    private val wifiStatus = WifiStatus(context) { bindWifi(it) }
    private val wifiButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onWifi(this) }
        pressFeedback()
    }

    /** Park / Sürüş göstergesi: sakin bir hap; dokununca Sürüş düzeni düzenlenir. */
    private val layoutPill = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY, medium = true).apply {
        setText(R.string.launcher_layout_park)
        gravity = Gravity.CENTER
        background = pillBackground()
        setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
        isClickable = true
        setOnClickListener { onLayoutPill() }
        pressFeedback()
    }

    /** Kümede hap kendi zeminini taşımaz (kapsül tek yüzey); ayrı görünümde eski sakin hap. */
    private fun pillBackground() =
        if (HEADER_STATUS_CLUSTER) null else rounded(context, LauncherTheme.CONTROL, 999f)

    /** Etkin düzene göre hap yazısı; yeniden kurulmadan güncellenir. */
    fun setLayoutMode(mode: LayoutMode) {
        lastMode = mode
        val label = if (mode == LayoutMode.DRIVE) R.string.launcher_layout_drive else R.string.launcher_layout_park
        layoutPill.setText(label)
        layoutPill.contentDescription = context.getString(R.string.launcher_layout_drive_edit)
        layoutPill.setTextColor(if (mode == LayoutMode.DRIVE) LauncherTheme.ACCENT else LauncherTheme.TEXT_SECONDARY)
    }

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        fill()
    }

    /** Başlığın çocuklarını kurar; saat/hava metin görünümleri korunur, düğmeler ve rozet o anki temayla yeniden kurulur. */
    private fun LinearLayout.fill() {
        addView(time)
        addView(gap(context, 24))
        addView(date)
        addView(View(context), weightLp(1f))

        driveBadge()?.let { addView(it) }
        if (HEADER_STATUS_CLUSTER) addView(statusCluster()) else addSeparateStatus()
        addView(gap(context, 16))
        if (SHOW_PROFILE_IN_HEADER) {
            addView(profileButton())
            addView(gap(context, 16))
        }
        addView(squareButton(R.drawable.ic_mdi_pencil, context.getString(R.string.launcher_edit_title), onEditHome))
        addView(gap(context, 16))
        addView(settingsButton())
    }

    /** Eski görünüm: hap, hava ve Wi-Fi ayrı ayrı. */
    private fun LinearLayout.addSeparateStatus() {
        addView(layoutPill)
        addView(gap(context, 24))
        weatherBox.addView(weatherIcon)
        weatherBox.addView(gap(context, 12))
        weatherBox.addView(weatherTemp)
        weatherBox.addView(gap(context, 10))
        weatherBox.addView(weatherLabel)
        addView(weatherBox)
        addView(gap(context, 24))
        buildWifiButton()
        addView(wifiButton)
    }

    /**
     * Durum kümesi (macOS menü çubuğu gibi): Park/Sürüş, hava ve Wi-Fi tek sakin kapsülde; öğeler arası ince çizgi.
     * Her öğe kendi dokunuşunu korur; Wi-Fi paneli Wi-Fi öğesinin altında açılır.
     */
    private fun statusCluster(): View {
        val h = context.dp(LauncherTheme.TOUCH_MIN_DP)
        val icon = context.dp(CLUSTER_ICON_DP)
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, LauncherTheme.CARD, 999f, LauncherTheme.CARD_BORDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
            setPadding(context.dp(8), 0, context.dp(8), 0)
        }
        fun divider() = View(context).apply {
            setBackgroundColor(LauncherTheme.TEXT_SECONDARY)
            alpha = 0.3f
            layoutParams = LinearLayout.LayoutParams(context.dp(1), context.dp(28))
        }
        layoutPill.textSize = CLUSTER_TEXT_SP
        (layoutPill.parent as? android.view.ViewGroup)?.removeView(layoutPill)
        capsule.addView(layoutPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        capsule.addView(divider())

        weatherTemp.textSize = CLUSTER_TEXT_SP
        weatherLabel.textSize = CLUSTER_TEXT_SP
        weatherBox.setPadding(context.dp(16), 0, context.dp(16), 0)
        weatherBox.addView(weatherIcon, LinearLayout.LayoutParams(icon, icon))
        weatherBox.addView(gap(context, 8))
        weatherBox.addView(weatherTemp)
        weatherBox.addView(gap(context, 8))
        weatherBox.addView(weatherLabel)
        capsule.addView(weatherBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        capsule.addView(divider())

        buildWifiButton()
        capsule.addView(wifiButton)
        return capsule
    }

    /** Wi-Fi düğmesi kare ya da (ağ adı sığıyorsa) yatay hap; en az 72 px dokunma alanı. */
    private fun buildWifiButton() {
        (wifiGlyph.parent as? android.view.ViewGroup)?.removeView(wifiGlyph)
        (wifiName.parent as? android.view.ViewGroup)?.removeView(wifiName)
        wifiButton.removeAllViews()
        val size = context.dp(LauncherTheme.TOUCH_MIN_DP)
        wifiButton.minimumWidth = size
        val glyph: Int
        if (HEADER_STATUS_CLUSTER) {
            // Kapsülün içinde: kendi zemini yok, kapsül kadar yüksek.
            wifiButton.background = null
            wifiButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            glyph = context.dp(CLUSTER_ICON_DP)
        } else {
            wifiButton.background = cardSurface(context)
            wifiButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, size)
            glyph = context.dp(LauncherTheme.ICON_M_DP + 4)
        }
        wifiButton.addView(wifiGlyph, LinearLayout.LayoutParams(glyph, glyph))
        wifiButton.addView(wifiName)
        bindWifi(wifiUi)
    }

    private fun bindWifi(ui: WifiUi) {
        wifiUi = ui
        wifiGlyph.bind(ui)
        // Ağ adı başlıkta gösterilmez (yalnızca simge durumu); ad Wi-Fi panelindeki bağlı ağ satırındadır.
        val name: String? = null
        wifiName.text = name
        wifiName.setTextColor(LauncherTheme.TEXT_SECONDARY)
        wifiName.visibility = if (name == null) View.GONE else View.VISIBLE
        (wifiName.layoutParams as? LinearLayout.LayoutParams)?.marginStart = context.dp(8)
        val h = if (name == null) 0 else context.dp(LauncherTheme.GAP_L_DP)
        wifiButton.setPadding(if (name == null) 0 else context.dp(LauncherTheme.GAP_SM_DP + 4), 0, h, 0)
        wifiButton.alpha = if (ui.kind == WifiUi.Kind.OFF) 0.85f else 1f
        wifiButton.contentDescription = context.getString(when (ui.kind) {
            WifiUi.Kind.OFF -> R.string.launcher_wifi_off
            WifiUi.Kind.HOTSPOT -> R.string.launcher_wifi_hotspot
            WifiUi.Kind.CONNECTED -> R.string.launcher_wifi_connected
        }) + (name?.let { ": $it" } ?: "")
    }

    private var lastMode = LayoutMode.PARK
    private var lastWeather: OpenMeteoWeatherSnapshot? = null

    /** Tema değişti: metin renkleri, hap, hava ikonu ve düğmeler yeni renkle; etkinlik yeniden kurulmaz. */
    override fun applyTheme() {
        time.setTextColor(LauncherTheme.TEXT)
        date.setTextColor(LauncherTheme.TEXT_SECONDARY)
        weatherLabel.setTextColor(LauncherTheme.TEXT_SECONDARY)
        weatherTemp.setTextColor(LauncherTheme.TEXT)
        weatherIcon.imageTintList = android.content.res.ColorStateList.valueOf(LauncherTheme.TEXT)
        layoutPill.background = pillBackground()
        setLayoutMode(lastMode)
        lastWeather?.let { bindWeather(it) }
        val row = view as LinearLayout
        weatherBox.removeAllViews()
        weatherBox.setPadding(0, 0, 0, 0)
        row.removeAllViews()
        row.fill()
        row.invalidate()
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
            addView(gap(context, 32))
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
            background = cardSurface(context)
            val size = context.dp(LauncherTheme.TOUCH_MIN_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(icon(context, res, LauncherTheme.ICON_M_DP))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    /** Yalnızca dişli simgesi; diğer düğmelerle aynı kare boyut (en az 72 px dokunma alanı). */
    private fun settingsButton(): View =
        squareButton(R.drawable.ic_mdi_cog, context.getString(R.string.launcher_settings), onSettings)

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
    }

    override fun release() {
        wifiStatus.stop()
        weatherClient.destroy()
    }

    private fun updateClock() {
        val now = Date()
        // Saat dilimi değişmiş olabilir (ACTION_TIMEZONE_CHANGED); biçimleyiciler her seferinde güncellenir.
        java.util.TimeZone.getDefault().let { timeFormat.timeZone = it; dateFormat.timeZone = it }
        time.text = timeFormat.format(now)
        date.text = dateFormat.format(now).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun bindWeather(snapshot: OpenMeteoWeatherSnapshot) {
        lastWeather = snapshot
        weatherTemp.text = context.getString(R.string.launcher_degrees, snapshot.temperatureC.roundToInt())
        weatherLabel.text = OpenMeteoWeatherLabels.labelFor(context, snapshot.weatherCode, snapshot.day)
        // Yalnızca elimizde doğru ikonu olan durum için hava ikonu; diğerlerinde nötr termometre.
        weatherIcon.setImageResource(
            if (snapshot.weatherCode in 1..3) R.drawable.ic_mdi_weather_partly_cloudy else R.drawable.ic_mdi_thermometer)
        // Eski veri ikincil tonda; veri hiç yoksa grup gizli kalır.
        val updatedAt = weatherClient.lastUpdatedMs
        val stale = updatedAt > 0L && System.currentTimeMillis() - updatedAt > WEATHER_STALE_MS
        weatherTemp.setTextColor(if (stale) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        weatherIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (stale) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        weatherBox.visibility = View.VISIBLE
    }
}

/**
 * Profil düğmesi başlıkta şimdilik gizli (başlık sadeleştirildi). Profil özelliği ([Profiles], [showProfileSheet])
 * yerinde duruyor; geri getirmek için true yapın.
 */
private const val SHOW_PROFILE_IN_HEADER = false

/**
 * Park/Sürüş hapı, hava ve Wi-Fi tek kapsülde (macOS menü çubuğu gibi). false: eski ayrı görünüm; karşılaştırma için.
 */
private const val HEADER_STATUS_CLUSTER = true
private const val CLUSTER_ICON_DP = 28
private const val CLUSTER_TEXT_SP = 24f
