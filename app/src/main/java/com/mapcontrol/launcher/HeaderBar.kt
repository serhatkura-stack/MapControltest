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
 * Üst şerit: solda saat ve tarih, sağda hava durumu ve "Ayarlar".
 * Saat sistemin dakika yayınıyla (ACTION_TIME_TICK) güncellenir; kendi zamanlayıcısı yok.
 */
class HeaderBar(
    private val context: Context,
    private val onSettings: () -> Unit,
    private val onThemeChanged: () -> Unit,
    private val onEditHome: () -> Unit,
) : LauncherWidget {

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

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(time)
        addView(gap(context, 24))
        addView(date)
        addView(View(context), weightLp(1f))

        weatherBox.addView(weatherIcon)
        weatherBox.addView(gap(context, 12))
        weatherBox.addView(weatherTemp)
        weatherBox.addView(gap(context, 10))
        weatherBox.addView(weatherLabel)
        addView(weatherBox)
        addView(gap(context, 32))
        addView(themeButton())
        addView(gap(context, 16))
        addView(squareButton(R.drawable.ic_mdi_pencil, context.getString(R.string.launcher_edit_title), onEditHome))
        addView(gap(context, 16))
        addView(settingsButton())
    }

    /** Tema düğmesi: Otomatik → Gündüz → Gece → Otomatik; ikon geçerli modu gösterir. */
    private fun themeButton(): View {
        val mode = ThemeMode.read(context)
        val res = when (mode) {
            ThemeMode.AUTO -> R.drawable.ic_mdi_theme_light_dark
            ThemeMode.DAY -> R.drawable.ic_mdi_weather_sunny
            ThemeMode.NIGHT -> R.drawable.ic_mdi_weather_night
        }
        val label = context.getString(R.string.launcher_theme) + ": " + context.getString(mode.label)
        return squareButton(res, label) {
            val next = ThemeMode.values()[(mode.ordinal + 1) % ThemeMode.values().size]
            ThemeMode.write(context, next)
            onThemeChanged()
        }
    }

    /** Yalnızca ikonlu kare düğme; ayarlar düğmesiyle aynı yükseklik, zemin ve basma geri bildirimi. */
    private fun squareButton(@androidx.annotation.DrawableRes res: Int, label: String, onClick: () -> Unit): View =
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

    private fun settingsButton(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = cardSurface(context)
        val h = context.dp(LauncherTheme.GAP_L_DP)
        setPadding(h, 0, h, 0)
        minimumWidth = context.dp(LauncherTheme.TOUCH_MIN_DP)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(LauncherTheme.TOUCH_MIN_DP))
        addView(icon(context, R.drawable.ic_mdi_cog, LauncherTheme.ICON_M_DP))
        addView(gap(context, LauncherTheme.GAP_SM_DP))
        addView(text(context, LauncherTheme.BODY_SP, medium = true).apply { setText(R.string.launcher_settings) })
        contentDescription = context.getString(R.string.launcher_settings)
        isClickable = true
        setOnClickListener { onSettings() }
        pressFeedback()
    }

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
    }

    override fun onHidden() {
        if (tickRegistered) {
            context.unregisterReceiver(tickReceiver)
            tickRegistered = false
        }
        weatherStart?.cancel()
        weatherClient.stop()
        weatherClient.setListener(null)
    }

    override fun release() = weatherClient.destroy()

    private fun updateClock() {
        val now = Date()
        // Saat dilimi değişmiş olabilir (ACTION_TIMEZONE_CHANGED); biçimleyiciler her seferinde güncellenir.
        java.util.TimeZone.getDefault().let { timeFormat.timeZone = it; dateFormat.timeZone = it }
        time.text = timeFormat.format(now)
        date.text = dateFormat.format(now).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun bindWeather(snapshot: OpenMeteoWeatherSnapshot) {
        weatherTemp.text = context.getString(R.string.launcher_degrees, snapshot.temperatureC.roundToInt())
        weatherLabel.text = OpenMeteoWeatherLabels.labelFor(context, snapshot.weatherCode, snapshot.day)
        // Yalnızca elimizde doğru ikonu olan durum için hava ikonu; diğerlerinde nötr termometre.
        weatherIcon.setImageResource(
            if (snapshot.weatherCode in 1..3) R.drawable.ic_mdi_weather_partly_cloudy else R.drawable.ic_mdi_thermometer)
        weatherBox.visibility = View.VISIBLE
    }
}
