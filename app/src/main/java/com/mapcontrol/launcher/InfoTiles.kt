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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Hava durumu kutucuğu: ikon ve başlık üstte, sıcaklık ve durum altta.
 * Geniş boyutta (genişlik >= 2) sıcaklık büyür ve rüzgar satırı görünür.
 * Veri [OpenMeteoWeatherClient] üzerinden gelir; ekran gizlenince dinleme durur.
 */
class WeatherTile(private val context: Context) : SizedWidget {

    private val client = OpenMeteoWeatherClient(context)
    private var snapshot: OpenMeteoWeatherSnapshot? = null
    private var span: Span = Span(1, 1)

    private val weatherIcon = icon(context, R.drawable.ic_mdi_thermometer, 28)
    private val title = text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
        setText(R.string.launcher_widget_weather)
    }
    private val tempText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, medium = true).apply {
        setText(R.string.launcher_unavailable)
    }
    private val conditionText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)
    private val windText = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY).apply {
        visibility = View.GONE
    }

    private val listener = object : OpenMeteoWeatherClient.Listener {
        override fun onWeatherUpdated(snapshot: OpenMeteoWeatherSnapshot) {
            this@WeatherTile.snapshot = snapshot
            render()
        }

        override fun onWeatherFailed() {
            // Son bilinen hava durumu kalır; hiç yoksa "Kullanılamıyor" görünür.
        }
    }

    override val view: View = card(context, paddingDp = 24).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(weatherIcon)
            addView(gap(context, 12))
            addView(title)
        })
        addView(View(context), weightLp(1f, vertical = true))
        addView(tempText)
        addView(gap(context, 8, vertical = true))
        addView(conditionText)
        addView(windText)
    }

    /** Boyut değişince sıcaklık ve rüzgar satırı yeniden çizilir. */
    override fun applySpan(span: Span) {
        this.span = span
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
            conditionText.text = ""
            windText.visibility = View.GONE
            return
        }
        val wide = span.w >= 2
        tempText.text = context.getString(R.string.launcher_degrees, current.temperatureC.roundToInt())
        styleTemp(if (wide) LauncherTheme.TEMP_SP else TEMP_COMPACT_SP, LauncherTheme.TEXT)
        conditionText.text = OpenMeteoWeatherLabels.labelFor(context, current.weatherCode, current.day)
        // Yalnızca elimizde doğru ikonu olan durum için hava ikonu; diğerlerinde nötr termometre.
        weatherIcon.setImageResource(
            if (current.weatherCode in 1..3) R.drawable.ic_mdi_weather_partly_cloudy else R.drawable.ic_mdi_thermometer)
        windText.text = context.getString(R.string.launcher_weather_wind, current.windSpeedKmh.roundToInt())
        windText.visibility = if (wide) View.VISIBLE else View.GONE
    }

    private fun styleTemp(sizeSp: Float, color: Int) {
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tempText.setTextColor(color)
    }

    private companion object {
        const val TEMP_COMPACT_SP = 32f
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
    private val executor = Executors.newSingleThreadExecutor()
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

    private val root: LinearLayout = card(context, paddingDp = 16).apply {
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

    /** Arka plan iş parçacığını kapatır. */
    override fun release() = executor.shutdown()

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
