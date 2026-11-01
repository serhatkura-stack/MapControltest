package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
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
 * Launcher'ın kendi alt çubuğu ("Alt çubuk: Bizimki"): OEM gezinme çubuğu gizlenirken yerini alır. Aracın çubuğuyla
 * aynı boy ve silüet ([DockShape]: 110 px bant, ~78 px gövde, 45° eğik uçlar, ortada sıcaklık·fan yamuğu), içerik
 * yalnızca temel öğeler: ana ekran · uygulamalar · araç | sürücü sıcaklığı · fan · yolcu sıcaklığı | ayarlar · telefon.
 * Yazı öncelikli ve sakin: ince çizgi ikonlar, ince sabit genişlikli rakamlar, tek renk yalnızca fan kademesinde.
 * Klima [ClimateControl] üzerinden (yazma araçta doğrulanana kadar kapalı, [ClimateWriter.CLIMATE_WRITE_VERIFIED]).
 * Okuma 3 sn'de bir, yalnızca ekran görünürken.
 */
class OurBottomBar(private val activity: Activity) : LauncherWidget, Themeable {

    private val context: Context = activity
    private val io = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())
    private var startup: LauncherStartup.Deferred? = null
    private var readout = ClimateReadout()

    /** Tema bağlı atamalar (kurucuda bir kez, [applyTheme]'de yeniden); bkz. [ThemeBindings]. */
    private val theme = ThemeBindings()
    private val surface = DockSurface(context)
    private val climateControl = ClimateControl(context, io, main)

    /**
     * Öğeler 1920x110 başvuru bandında OEM x konumlarında ([DockLayout]): ana ekran 212 · uygulamalar 338 · araç 464 |
     * sıcaklık 750 · fan 960 · sıcaklık 1170 | ayarlar 1582 · telefon 1708. Sağda iki öğe var: uygulamalar/ana ekranın
     * aynası olan iki yere oturur (aracın direksiyon/ses ve telefon yeri); ortadaki boşluk yamuğun yanında kalır.
     */
    override val view: View = DockLayout(context).apply {
        background = surface
        fun nav(v: View, cx: Float) = addCentered(v, cx, NAV_BOX_W)
        // Ana ekran: sistemin ana ekranına çıkar (launcher varsayılan değilken OEM ana ekranı); varsayılan bizsek bir şey yapmaz.
        nav(navButton(R.drawable.ic_bar_home, R.string.launcher_bar_home) { leaveToSystemHome() }, X_HOME)
        nav(navButton(R.drawable.ic_bar_apps, R.string.launcher_bar_apps) { showApps() }, X_APPS)
        nav(navButton(R.drawable.ic_bar_car, R.string.launcher_vehicle_settings) { openPackage(VEHICLE_SETTINGS_PACKAGE) }, X_CAR)
        addAt(climateControl.driverView, 630f, DockShape.BODY_TOP, 870f, DockShape.BODY_BOTTOM)
        addAt(climateControl.fanView, 870f, DockShape.BODY_TOP, 1050f, DockShape.BODY_BOTTOM)
        addAt(climateControl.passengerView, 1050f, DockShape.BODY_TOP, 1290f, DockShape.BODY_BOTTOM)
        nav(navButton(R.drawable.ic_bar_settings, R.string.launcher_bar_settings) { openAndroidSettings() }, X_SETTINGS)
        nav(navButton(R.drawable.ic_bar_phone, R.string.launcher_app_phone) { openPackage(PHONE_PACKAGE) }, X_PHONE)
    }

    /** Tema değişti: yüzey, düğmeler ve klima bölümleri yeni renklerle yenilenir; görünümler korunur. */
    override fun applyTheme() {
        appsDialog?.dismiss()
        appsDialog = null
        theme.reapply()
        surface.applyTheme()
        climateControl.applyTheme()
        view.invalidate()
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

    /** Düğme (gövde yüksekliğinde, 112 başvuru genişliğinde): zemini yok; basınca yuvarlak hafif örtü. İnce çizgi ikon. */
    private fun navButton(iconRes: Int, labelRes: Int, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            theme.bind { foreground = dockPress(context) }
            contentDescription = context.getString(labelRes)
            isClickable = true
            isFocusable = true
            addView(
                icon(context, iconRes, NAV_ICON_DP).also { ic -> theme.bind { ic.setTint(LauncherTheme.DOCK_TEXT) } },
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
        /** Çubuk penceresi (bant) yüksekliği px: OEM gezinme penceresi 110; gövde içinde y 21–98, altta ~12 px boş pay (alttan kaydırma). */
        fun bandPx(context: Context): Int = DockShape.bandPx(context)

        private const val VEHICLE_SETTINGS_PACKAGE = "com.desaysv.vehiclesetting"
        private const val PHONE_PACKAGE = "com.desaysv.bluetooth.phone"
        private const val POLL_MS = 3_000L

        private const val NAV_ICON_DP = 34
        /** Düğme dokunma kutusu genişliği (başvuru birimi; komşu aralığı 126). */
        private const val NAV_BOX_W = 112f
        /** OEM ikon merkezleri (başvuru x). */
        private const val X_HOME = 212f
        private const val X_APPS = 338f
        private const val X_CAR = 464f
        private const val X_SETTINGS = 1582f
        private const val X_PHONE = 1708f

        private const val APPS_SHEET_WIDTH_DP = 1280
        private const val APPS_HEIGHT_DP = 440
        private const val APPS_COLUMNS = 7
        private const val APP_CELL_DP = 140
        private const val APP_ICON_DP = 64

        /** Sıcaklık: °C = 15,5 + ham/2 (araçta ekranla karşılaştırıldı: 21 → 26,0); ham 0 = LO (15,5), 30 = HI (30,5); aralık dışı "—". */
        fun decodeTemp(raw: Int): Double? = (ClimateMath.LO_TEMP + raw / 2.0).takeIf { raw in ClimateMath.RAW_LO..ClimateMath.RAW_HI }

        /** Fan kademesi doğrudan okunur; makul aralık (0..[ClimateMath.FAN_MAX]) dışı "—". Araçta en çok 10 okundu. */
        fun decodeFan(raw: Int): Int? = raw.takeIf { it in 0..ClimateMath.FAN_MAX }
    }
}

/**
 * Çubuk düğmelerinin basış örtüsü: basılıyken kutunun içinde yuvarlak hafif ton, bırakınca kısa solar.
 * Bölümün üstünde (foreground) çizilir.
 */
internal fun dockPress(context: Context): Drawable = StateListDrawable().apply {
    val inset = context.dp(6)
    addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(rounded(context, LauncherTheme.DOCK_PRESS, 14f), inset))
    addState(intArrayOf(), ColorDrawable(0))
    setEnterFadeDuration(0)
    setExitFadeDuration(150)
}
