package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState
import com.mapcontrol.util.AppIconHelper
import com.mapcontrol.util.AppLaunchHelper
import com.mapcontrol.util.ProjectionTargetApps

/** Klima okuması: sürücü ve yolcu sıcaklığı (°C) ile fan kademesi; okunamayan alan null. */
internal data class ClimateReadout(
    val connected: Boolean = false,
    val driverTemp: Double? = null,
    val passengerTemp: Double? = null,
    val fan: Int? = null,
)

/**
 * Launcher'ın kendi alt çubuğu ("Alt çubuk: Bizimki"): OEM gezinme çubuğu gizlenirken yerini alır.
 * Tek parça koyu "dock" şeridi, bölümler ince ayraçlarla: ana ekran · uygulamalar · araç (kare düğmeler), medya (kapak,
 * başlık, sanatçı, oynat/duraklat; oturum yokken "Müzik"), sürücü sıcaklığı · fan · yolcu sıcaklığı
 * ([ClimateControl]: sürükle/dokun/uzun bas), ayarlar · telefon (kare düğmeler). Klima yazması araçta doğrulanana
 * kadar kapalı ([ClimateWriter.CLIMATE_WRITE_VERIFIED]): değer kısa süre değişir, sonra araçtakine döner.
 * Okuma 3 sn'de bir, yalnızca ekran görünürken.

 */
class OurBottomBar(private val activity: Activity) : LauncherWidget {

    private val context: Context = activity
    private val io = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())
    private var startup: LauncherStartup.Deferred? = null
    private var readout = ClimateReadout()

    // --- Klima ---
    private val climateControl = ClimateControl(context, io, main)

    /**
     * Tek parça dock: koyu cam yüzeyli tek yuvarlak şerit (köşeler yalnızca iki uçta, kart yarıçapında), bölümler
     * arası boşluksuz; ayrım 1dp ince ayraçlarla (üstten/alttan içeride). Genişlik ağırlıkla dolar.
     */
    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = DockSurface(context)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, context.dpF(LauncherTheme.CARD_RADIUS_DP))
                outline.alpha = 1f
            }
        }
        elevation = context.dpF(LauncherTheme.DOCK_ELEVATION_DP)
        var first = true
        fun add(v: View, lp: LinearLayout.LayoutParams) {
            if (!first) addView(divider(), LinearLayout.LayoutParams(context.dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = context.dp(DIVIDER_INSET_DP)
                bottomMargin = context.dp(DIVIDER_INSET_DP)
            })
            first = false
            addView(v, lp)
        }
        fun square() = LinearLayout.LayoutParams(context.dp(HEIGHT_DP), LinearLayout.LayoutParams.MATCH_PARENT)
        // Ana ekran: sistemin ana ekranına çıkar (launcher varsayılan değilken OEM ana ekranı); varsayılan bizsek bir şey yapmaz.
        add(navButton(R.drawable.ic_mdi_home, R.string.launcher_bar_home, active = true) { leaveToSystemHome() }, square())
        add(navButton(R.drawable.ic_mdi_view_grid, R.string.launcher_bar_apps) { showApps() }, square())
        add(navButton(R.drawable.ic_mdi_car, R.string.launcher_vehicle_settings) { openPackage(VEHICLE_SETTINGS_PACKAGE) }, square())
        add(climateControl.driverView, weightLp(W_TEMP))
        add(climateControl.fanView, weightLp(W_FAN))
        add(climateControl.passengerView, weightLp(W_TEMP))
        add(navButton(R.drawable.ic_mdi_cog, R.string.launcher_bar_settings) { openAndroidSettings() }, square())
        add(navButton(R.drawable.ic_mdi_phone, R.string.launcher_app_phone) { openPackage(PHONE_PACKAGE) }, square())
    }

    override fun onVisible() {
        bindClimate(readout)
        startup?.cancel()
        startup = LauncherStartup.later { main.post(poll) }
    }

    override fun onHidden() {
        startup?.cancel()
        main.removeCallbacks(poll)
        climateControl.onHidden()
        appsDialog?.dismiss()
        appsDialog = null
    }

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz. */
    override fun release() = onHidden()

    private val poll = object : Runnable {
        override fun run() {
            io.runSafely {
                val read = readClimate()
                main.post {
                    if (read == readout) return@post
                    readout = read
                    bindClimate(read)
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    // --- Yerleşim ---

    private fun divider(): View = View(context).apply { setBackgroundColor(LauncherTheme.DOCK_DIVIDER) }

    /**
     * Kare düğme (çubuk yüksekliği). Zemini yok, dock'un parçası; basınca beyaz örtü. Etkin olan, bölümün
     * içinde vurgu renginde yuvarlak bir hap ve beyaz ikon.
     */
    private fun navButton(iconRes: Int, labelRes: Int, active: Boolean = false, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            foreground = dockPressOverlay(context)
            contentDescription = context.getString(labelRes)
            isClickable = true
            isFocusable = true
            if (active) {
                val inset = context.dp(PILL_INSET_DP)
                addView(View(context).apply {
                    background = rounded(context, DriveAccent.buttonFill(LauncherTheme.ACCENT), PILL_RADIUS_DP)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    setMargins(inset, inset, inset, inset)
                })
            }
            addView(
                icon(context, iconRes, NAV_ICON_DP, if (active) LauncherTheme.ON_ACCENT else LauncherTheme.DOCK_TEXT),
                FrameLayout.LayoutParams(context.dp(NAV_ICON_DP), context.dp(NAV_ICON_DP), Gravity.CENTER),
            )
            setOnClickListener { onClick() }
        }

    // --- Bağlama ---

    private fun bindClimate(r: ClimateReadout) = climateControl.bind(r)

    // --- Okuma ---

    /** Araçtan salt-okur: sürücü/yolcu sıcaklığı (HVAC 10/13) ve fan kademesi (HVAC 12). Yazma yok. */
    private fun readClimate(): ClimateReadout {
        if (LauncherDemo.enabled) return ClimateReadout(true, LauncherDemo.DRIVER_TEMP, LauncherDemo.PASSENGER_TEMP, LauncherDemo.FAN_LEVEL)
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return ClimateReadout()
        return try {
            fun first(id: Int): Int? = proxy.getItemValues(VDEventCarInfo.MODULE_HVAC, id)?.firstOrNull()
            ClimateReadout(
                connected = true,
                driverTemp = first(HvacID.ID_AC_TEMPERATURE_DRIVER)?.let(::decodeTemp),
                passengerTemp = first(HvacID.ID_AC_TEMPERATURE_COPILOT)?.let(::decodeTemp),
                fan = first(HvacID.ID_AC_BLOW_SPEED_LEVEL)?.let(::decodeFan),
            )
        } catch (e: RuntimeException) {
            ClimateReadout()
        }
    }

    // --- Eylemler ---

    /** Uygulamayı varsayılan ekranda açar; kurulu değilse sessizce bir şey yapmaz (emülatör). */
    private fun leaveToSystemHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (target == null) { activity.moveTaskToBack(true); return }
        if (target.packageName == context.packageName) return
        try {
            context.startActivity(home.setClassName(target.packageName, target.name))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    private fun openPackage(packageName: String) {
        io.runSafely { AppLaunchHelper.launchAppOnDisplay(context, packageName, Display.DEFAULT_DISPLAY, 0) }
    }

    private fun openAndroidSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Ayarlar uygulaması yok: yapılacak bir şey yok.
        }
    }

    // --- Tüm uygulamalar ---

    private var appsDialog: Dialog? = null

    /** Başlatılabilir tüm uygulamalar bir panelde; ikonlar arka planda çözülür, dokununca uygulama açılır. */
    private fun showApps() {
        if (appsDialog?.isShowing == true) return
        io.runSafely {
            val rows = ProjectionTargetApps.loadAllLaunchableRows(context)
            val entries = rows.map { r ->
                AppCell(AppIdentity.label(context, r.packageName, r.label), r.packageName, loadIcon(r.packageName))
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed || entries.isEmpty()) return@post
                appsDialog = showLauncherSheet(activity, R.string.launcher_bar_apps, APPS_SHEET_WIDTH_DP) { d ->
                    addView(appsGrid(entries, d), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, context.dp(APPS_HEIGHT_DP)))
                }.apply { setOnDismissListener { appsDialog = null } }
            }
        }
    }

    private class AppCell(val label: String, val packageName: String, val icon: Drawable?)

    private fun loadIcon(packageName: String): Drawable? = try {
        val plate = if (LauncherTheme.night) LauncherTheme.CONTROL else 0
        AppIdentity.overrideIcon(context, packageName, context.dp(APP_ICON_DP))
            ?: AppIconHelper.loadSized(context, packageName, context.dp(APP_ICON_DP), plate)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun appsGrid(entries: List<AppCell>, d: Dialog): View = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            entries.chunked(APPS_COLUMNS).forEach { chunk ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    chunk.forEach { app -> addView(appCell(app, d), LinearLayout.LayoutParams(0, context.dp(APP_CELL_DP), 1f)) }
                    // Son satırı doldur ki hücre genişlikleri eşit kalsın.
                    repeat(APPS_COLUMNS - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                })
            }
        })
    }

    private fun appCell(app: AppCell, d: Dialog): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = app.label
        addView(ImageView(context).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(APP_ICON_DP), context.dp(APP_ICON_DP)))
        addView(gap(context, 6, vertical = true))
        addView(text(context, LauncherTheme.LABEL_SP).apply {
            text = app.label
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
        })
        setOnClickListener {
            d.dismiss()
            openPackage(app.packageName)
        }
        pressFeedback()
    }

    companion object {
        const val HEIGHT_DP = 104
        /** Çubuğun alt kenarında bırakılan boşluk: alttan kaydırma hareketi (OEM çubuğunu çağırır) çubuğa takılmasın. */
        const val BOTTOM_GAP_DP = 12

        private const val VEHICLE_SETTINGS_PACKAGE = "com.desaysv.vehiclesetting"
        private const val PHONE_PACKAGE = "com.desaysv.bluetooth.phone"
        private const val POLL_MS = 3_000L
        private const val DASH = "—"

        private const val NAV_ICON_DP = 36
        /** Ayraçların alt/üstten içeri payı; Home hapının içeri payı ve yarıçapı. */
        private const val DIVIDER_INSET_DP = 22
        private const val PILL_INSET_DP = 12
        private const val PILL_RADIUS_DP = 18f

        /** Bölüm ağırlıkları (kare düğmeler sabit): sıcaklıklar geniş, fan biraz dar. */
        private const val W_TEMP = 1.4f
        private const val W_FAN = 1.2f

        private const val APPS_SHEET_WIDTH_DP = 1280
        private const val APPS_HEIGHT_DP = 440
        private const val APPS_COLUMNS = 7
        private const val APP_CELL_DP = 140
        private const val APP_ICON_DP = 64

        /** Sıcaklık: °C = 15,5 + ham/2 (araçta ekranla karşılaştırıldı: 21 → 26,0); makul aralık dışı "—". */
        fun decodeTemp(raw: Int): Double? = (15.5 + raw / 2.0).takeIf { raw >= 0 && it <= 32.0 }

        /** Fan kademesi doğrudan okunur; makul aralık (0..9) dışı "—". */
        fun decodeFan(raw: Int): Int? = raw.takeIf { it in 0..9 }
    }
}

/**
 * Dock'un basış örtüsü: basılıyken %12 beyaz, bırakınca kısa solar. Bölümün üstünde (foreground); dış şekle
 * kırpma dock'un `clipToOutline`ından gelir.
 */
internal fun dockPressOverlay(context: Context): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(LauncherTheme.DOCK_PRESS))
    addState(intArrayOf(), ColorDrawable(0))
    setEnterFadeDuration(0)
    setExitFadeDuration(150)
}

/**
 * Dock yüzeyi: üstü hafif açık düşey gradyan, kart yarıçapında köşeler. Gündüz 1dp koyu ince kenar çizgisi; gecede üst kenarda 1dp parıltı çizgisi
 * (köşelerden aşağı doğru solar). Gölgeler bir kez [onBoundsChange]'de kurulur; çizimde ayırma yok.
 */
private class DockSurface(private val context: Context) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(1f)
    }
    private val rect = RectF()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(1f)
        color = LauncherTheme.DOCK_BORDER
    }
    private val radius = context.dpF(LauncherTheme.CARD_RADIUS_DP)

    override fun onBoundsChange(b: android.graphics.Rect) {
        fill.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), LauncherTheme.DOCK_TOP, LauncherTheme.DOCK_BOTTOM, Shader.TileMode.CLAMP)
        val hl = LauncherTheme.DOCK_HIGHLIGHT
        edge.shader = if (hl == 0) null else
            LinearGradient(0f, b.top.toFloat(), 0f, b.top + radius * 1.2f, hl, hl and 0x00FFFFFF, Shader.TileMode.CLAMP)
        val h = edge.strokeWidth / 2
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius, radius, fill)
        if (edge.shader != null) canvas.drawRoundRect(rect, radius, radius, edge)
        if (border.color != 0) canvas.drawRoundRect(rect, radius, radius, border)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
