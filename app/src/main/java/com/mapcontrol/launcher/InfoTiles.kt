package com.mapcontrol.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapcontrol.R
import com.mapcontrol.util.AppIconHelper
import com.mapcontrol.util.AppLaunchHelper
import com.mapcontrol.util.ProjectionTargetApps
import com.mapcontrol.weather.OpenMeteoWeatherClient
import com.mapcontrol.weather.OpenMeteoWeatherLabels
import com.mapcontrol.weather.OpenMeteoWeatherSnapshot
import kotlin.math.roundToInt

/** Bu süreden eski hava verisi ikincil tonda gösterilir (hata değil). */
internal const val WEATHER_STALE_MS = 3L * 60L * 60L * 1000L

/** "5 dk önce" / "2 sa önce" / "3 g önce". */
internal fun weatherAgeText(context: Context, ageMs: Long): String {
    val min = (ageMs / 60_000L).coerceAtLeast(1L).toInt()
    return when {
        min < 60 -> context.getString(R.string.launcher_weather_age_min, min)
        min < 60 * 24 -> context.getString(R.string.launcher_weather_age_hour, min / 60)
        else -> context.getString(R.string.launcher_weather_age_day, min / (60 * 24))
    }
}

/**
 * Hava durumu kutucuğu: hava ikonu + sıcaklık; durum metni 1x1'de altta, 2x1'de sağda (rüzgârla birlikte).
 * Başlık yok: üst şeritte de hava var, ikon kutucuğu zaten anlatır. 125dp'ye sığması için iç boşluk 20dp.
 * Veri [OpenMeteoWeatherClient] üzerinden gelir; ekran gizlenince dinleme durur.
 */
class WeatherTile(private val context: Context) : SizedWidget {

    private val client = OpenMeteoWeatherClient(context)
    private var snapshot: OpenMeteoWeatherSnapshot? = null
    private var span: Span = Span(1, 1)
    /** Çekim hiç başarısız olduysa ve veri yoksa "Bağlantı yok" gösterilir. */
    private var failed = false

    private val weatherIcon = icon(context, R.drawable.ic_mdi_weather_partly_cloudy, ICON_DP, LauncherTheme.TEXT_SECONDARY)
    private val tempText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, medium = true).apply {
        setText(R.string.launcher_unavailable)
    }
    private val conditionText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 2)
    private val windText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY).apply {
        visibility = View.GONE
    }
    private val ageText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY).apply {
        visibility = View.GONE
    }

    private val listener = object : OpenMeteoWeatherClient.Listener {
        override fun onWeatherUpdated(snapshot: OpenMeteoWeatherSnapshot) {
            this@WeatherTile.snapshot = snapshot
            failed = false
            render()
        }

        override fun onWeatherFailed() {
            // Son bilinen veri kalır; hiç yoksa sakin bir "Bağlantı yok" görünür.
            failed = true
            render()
        }
    }

    /** Sol sütun: ikon + sıcaklık satırı; dar boyutta altında durum metni. */
    private val leftColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(weatherIcon)
            addView(gap(context, 12))
            addView(tempText)
        })
    }

    /** Sağ sütun (yalnızca geniş boyutta): durum metni + rüzgâr, sağa yaslı, dikey ortalı. */
    private val rightColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(windText)
        addView(ageText)
    }

    override val view: View = card(context, LinearLayout.HORIZONTAL, paddingDp = PADDING_DP).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(leftColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0f).apply {
            marginStart = context.dp(16)
        })
    }

    init {
        leftColumn.addView(conditionText)
    }

    /** Boyut değişince durum metni sol/sağ sütun arasında taşınır. */
    override fun applySpan(span: Span) {
        this.span = span
        val wide = span.w >= 2
        (conditionText.parent as? android.view.ViewGroup)?.removeView(conditionText)
        if (wide) {
            rightColumn.addView(conditionText, 0)
            conditionText.gravity = Gravity.END
            conditionText.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        } else {
            leftColumn.addView(conditionText)
            conditionText.gravity = Gravity.START
            conditionText.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        rightColumn.visibility = if (wide) View.VISIBLE else View.GONE
        (leftColumn.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (wide) LinearLayout.LayoutParams.WRAP_CONTENT else 0
            weight = if (wide) 0f else 1f
        }
        (rightColumn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = if (wide) 1f else 0f }
        view.requestLayout()
        render()
    }

    override fun onVisible() {
        client.setListener(listener)
        client.start()
    }

    override fun onHidden() {
        client.stop()
        client.setListener(null)
    }

    override fun release() = client.destroy()

    private fun render() {
        val current = snapshot
        if (current == null) {
            tempText.setText(R.string.launcher_unavailable)
            styleTemp(LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
            conditionText.text = if (failed) context.getString(R.string.launcher_weather_offline) else ""
            windText.visibility = View.GONE
            ageText.visibility = View.GONE
            return
        }
        val wide = span.w >= 2
        val updatedAt = client.lastUpdatedMs
        val age = if (updatedAt > 0L) System.currentTimeMillis() - updatedAt else 0L
        val stale = age > WEATHER_STALE_MS
        tempText.text = context.getString(R.string.launcher_degrees, current.temperatureC.roundToInt())
        // 1x1'de sıcaklığın altına durum metni de sığmalı; 2x1'de metin sağda olduğu için büyüyebilir.
        styleTemp(if (wide) TEMP_WIDE_SP else TEMP_COMPACT_SP, if (stale) LauncherTheme.TEXT_SECONDARY else LauncherTheme.TEXT)
        conditionText.text = OpenMeteoWeatherLabels.labelFor(context, current.weatherCode, current.day)
        weatherIcon.setImageResource(iconFor(current.weatherCode, current.day))
        windText.text = context.getString(R.string.launcher_weather_wind, current.windSpeedKmh.roundToInt())
        windText.visibility = if (wide) View.VISIBLE else View.GONE
        // Eski veri: yalnızca geniş boyutta küçük yaş ipucu.
        ageText.visibility = if (wide && stale) View.VISIBLE else View.GONE
        if (stale) ageText.text = weatherAgeText(context, age)
        view.contentDescription = "${tempText.text}, ${conditionText.text}"
    }

    /** WMO hava kodu → ikon. */
    private fun iconFor(code: Int, day: Boolean): Int = when (code) {
        0 -> if (day) R.drawable.ic_mdi_weather_sunny else R.drawable.ic_mdi_weather_night
        1, 2 -> R.drawable.ic_mdi_weather_partly_cloudy
        3 -> R.drawable.ic_mdi_weather_cloudy
        45, 48 -> R.drawable.ic_mdi_weather_fog
        in 51..57, in 61..63, in 66..67, 80 -> R.drawable.ic_mdi_weather_rainy
        65, 81, 82 -> R.drawable.ic_mdi_weather_pouring
        in 71..77, 85, 86 -> R.drawable.ic_mdi_weather_snowy
        in 95..99 -> R.drawable.ic_mdi_weather_lightning
        else -> R.drawable.ic_mdi_weather_cloudy
    }

    private fun styleTemp(sizeSp: Float, color: Int) {
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tempText.setTextColor(color)
    }

    private companion object {
        const val TEMP_COMPACT_SP = 36f
        const val TEMP_WIDE_SP = 44f
        const val ICON_DP = 36
        const val PADDING_DP = 20
    }
}

/**
 * Tek uygulamalık kısayol: 64dp ikon ve altında etiket. Dokunmak uygulamayı açar.
 * Uygulama yoksa ya da kurulu değilse artı ikonlu boş kutu gösterilir; ona dokunmak seçiciyi açar.
 * Uzun basış her durumda seçiciyi açar. Seçim "launcher_tile_apps" tercihlerinde [tileId] anahtarıyla tutulur
 * ve verilen [packageName] değerini geçersiz kılar.
 */
class AppShortcutTile(
    private val activity: Activity,
    private val packageName: String?,
    private val tileId: String = "app",
) : SizedWidget {

    /** Ekranda şu an ne gösterildiği; tıklamanın ne yapacağını belirler. */
    private sealed class Shown {
        object Loading : Shown()
        object Empty : Shown()
        class App(val pkg: String) : Shown()
    }

    /** Uygulamanın etiketi ve ikonu. */
    private class AppEntry(val label: String, val icon: Drawable)

    private val context: Context = activity
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = LauncherIo.serial()
    private val iconRadius = context.dpF(ICON_RADIUS_DP)

    /** Her yenilemede artar; eski arka plan sonuçları bu değişmişse yok sayılır. */
    private var generation = 0
    private var shown: Shown = Shown.Loading

    private val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP))
        scaleType = ImageView.ScaleType.FIT_CENTER
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, iconRadius)
            }
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val labelView = text(context, LauncherTheme.LABEL_SP).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // 125dp kartta 64dp ikon + etiket sığsın diye 10dp kenar: etiketin alt çıkıntıları kesilmez.
    private val root: LinearLayout = card(context, paddingDp = 10).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        addView(iconView)
        addView(gap(context, 8, vertical = true))
        addView(labelView)
        setOnClickListener {
            when (val current = shown) {
                is Shown.App -> launch(current.pkg)
                Shown.Empty -> openPicker()
                Shown.Loading -> Unit
            }
        }
        setOnLongClickListener {
            openPicker()
            true
        }
        pressFeedback()
    }

    override val view: View = root

    /** Kutucuk ortada kalır; başka bir değişiklik gerekmez. */
    override fun applySpan(span: Span) {
        root.gravity = Gravity.CENTER
    }

    /** Ekran görünür olunca seçimi okur ve ikon/etiketi (gerekirse arka planda) yükler. */
    override fun onVisible() = refresh()

    override fun onHidden() = Unit

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz; ayrılacak bir kaynak yok. */
    override fun release() = Unit

    /** Kayıtlı seçim varsa o, yoksa kurulumdaki paket; paket yoksa ya da kurulu değilse boş kutu. */
    private fun refresh() {
        val token = ++generation
        val pkg = prefs.getString(tileId, null) ?: packageName
        if (pkg.isNullOrEmpty()) {
            bindEmpty()
            return
        }
        executor.runSafely {
            val entry = resolve(pkg)
            root.post {
                if (token != generation) return@post
                if (entry == null) bindEmpty() else bindApp(pkg, entry)
            }
        }
    }

    private fun bindApp(pkg: String, entry: AppEntry) {
        shown = Shown.App(pkg)
        iconView.background = null
        iconView.imageTintList = null
        iconView.setPadding(0, 0, 0, 0)
        iconView.setImageDrawable(entry.icon)
        labelView.text = entry.label
        labelView.setTextColor(LauncherTheme.TEXT)
        root.contentDescription = entry.label
    }

    /** Boş kutu: CONTROL renkli yuvarlak kare içinde artı ikonu. */
    private fun bindEmpty() {
        shown = Shown.Empty
        val pad = context.dp((ICON_DP - PLUS_DP) / 2)
        iconView.background = rounded(context, LauncherTheme.CONTROL, ICON_RADIUS_DP)
        iconView.imageTintList = ColorStateList.valueOf(LauncherTheme.TEXT_SECONDARY)
        iconView.setPadding(pad, pad, pad, pad)
        iconView.setImageResource(R.drawable.ic_mdi_plus)
        val emptyLabel = context.getString(R.string.launcher_app_empty)
        labelView.text = emptyLabel
        labelView.setTextColor(LauncherTheme.TEXT_SECONDARY)
        root.contentDescription = emptyLabel
    }

    /** Uygulamayı varsayılan ekranda açar. Java yardımcısı senkron olduğu için arka planda çalışır. */
    private fun launch(pkg: String) {
        executor.runSafely {
            AppLaunchHelper.launchAppOnDisplay(context, pkg, Display.DEFAULT_DISPLAY, 0)
        }
    }

    /** Başlatılabilir uygulamaları arka planda listeler; seçim tercihlere yazılır. */
    private fun openPicker() {
        executor.runSafely {
            val rows = ProjectionTargetApps.loadAllLaunchableRows(context)
            root.post {
                if (rows.isEmpty()) return@post
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.launcher_app_pick_title)
                    .setItems(rows.map { it.label }.toTypedArray()) { _, which ->
                        prefs.edit().putString(tileId, rows[which].packageName).apply()
                        refresh()
                    }
                    .show()
            }
        }
    }

    /** Paketin etiketi ve ikonu; kurulu değilse null. Arka planda çağrılır. */
    private fun resolve(pkg: String): AppEntry? = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val label = pm.getApplicationLabel(info).toString().trim().ifEmpty { pkg }
        AppEntry(label, AppIconHelper.load(context, pkg))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val PREFS_NAME = "launcher_tile_apps"
        const val ICON_DP = 64
        const val PLUS_DP = 28
        const val ICON_RADIUS_DP = 16f
    }
}
