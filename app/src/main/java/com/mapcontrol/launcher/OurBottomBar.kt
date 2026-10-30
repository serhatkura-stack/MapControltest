package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
 * Solda ana ekran · uygulamalar · araç ve (varsa) çalan parça, ortada sürücü sıcaklığı · fan · yolcu sıcaklığı
 * kapsülü ([ClimateControl]: sürükle/dokun/uzun bas), sağda ayarlar · telefon. Klima yazması araçta doğrulanana
 * kadar kapalı ([ClimateWriter.CLIMATE_WRITE_VERIFIED]): değer kısa süre değişir, sonra araçtakine döner.
 * Okuma 3 sn'de bir, yalnızca ekran görünürken.
 *
 * Ekstra: çalan parçanın küçük kontrolü (başlık + oynat/duraklat). OEM çubuğunda yok, ana ekrandaki medya
 * kartı kaldırıldıysa da müziği tek dokunuşla yönetmeyi sağlar; oturum yokken hiç yer kaplamaz.
 */
class OurBottomBar(private val activity: Activity) : LauncherWidget {

    private val context: Context = activity
    private val io = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())
    private var startup: LauncherStartup.Deferred? = null
    private var readout = ClimateReadout()

    // --- Medya (ekstra) ---
    private val media = LauncherMediaController(context)
    private var listening = false
    private val mediaListener = LauncherMediaController.Listener { state -> if (!LauncherDemo.enabled) bindMedia(state) }
    private val playIcon = icon(context, R.drawable.ic_mdi_play, MEDIA_ICON_DP)
    private val mediaTitle = text(context, LauncherTheme.BODY_SP, medium = true)
    private val mediaMini: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val playButton = FrameLayout(context).apply {
            background = oval(LauncherTheme.CONTROL)
            contentDescription = context.getString(R.string.launcher_media_play)
            isClickable = true
            isFocusable = true
            addView(playIcon, FrameLayout.LayoutParams(context.dp(MEDIA_ICON_DP), context.dp(MEDIA_ICON_DP), Gravity.CENTER))
            setOnClickListener { media.playPause() }
            pressFeedback()
        }
        addView(playButton, LinearLayout.LayoutParams(context.dp(BUTTON_DP), context.dp(BUTTON_DP)))
        addView(gap(context, LauncherTheme.GAP_SM_DP))
        addView(mediaTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    // --- Klima ---
    private val climateControl = ClimateControl(context, io, main)
    private val climate: View = climateControl.view

    override val view: View = card(context, LinearLayout.HORIZONTAL, paddingDp = LauncherTheme.CARD_PAD_COMPACT_DP).apply {
        // Kart köşe yarıçapı ve belirteçler diğer kartlarla aynı; içerik üç bölgeye ayrılır.
        gravity = Gravity.CENTER_VERTICAL
        val frame = FrameLayout(context)
        frame.addView(buildLeft(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        // Kapsül tam ortada: sol ve sağ grupların genişliği farklı olsa da ortalı kalır.
        frame.addView(climate, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.addView(buildRight(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onVisible() {
        bindClimate(readout)
        if (!listening) {
            media.addListener(mediaListener)
            listening = true
        }
        media.start()
        bindMedia(if (LauncherDemo.enabled) LauncherDemo.media() else media.currentState())
        startup?.cancel()
        startup = LauncherStartup.later { main.post(poll) }
    }

    override fun onHidden() {
        startup?.cancel()
        main.removeCallbacks(poll)
        if (listening) {
            media.removeListener(mediaListener)
            listening = false
        }
        media.stop()
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

    private fun buildLeft(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Ana ekran: zaten ana ekrandayız; dokunuş bir şey yapmaz, vurgu "buradasın" der.
        addView(navButton(R.drawable.ic_mdi_home, R.string.launcher_bar_home, active = true) { })
        addView(gap(context, LauncherTheme.GAP_S_DP))
        addView(navButton(R.drawable.ic_mdi_view_grid, R.string.launcher_bar_apps) { showApps() })
        addView(gap(context, LauncherTheme.GAP_S_DP))
        addView(navButton(R.drawable.ic_mdi_car, R.string.launcher_vehicle_settings) { openPackage(VEHICLE_SETTINGS_PACKAGE) })
        addView(gap(context, LauncherTheme.GAP_L_DP))
        addView(mediaMini, LinearLayout.LayoutParams(context.dp(MEDIA_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun buildRight(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(navButton(R.drawable.ic_mdi_cog, R.string.launcher_bar_settings) { openAndroidSettings() })
        addView(gap(context, LauncherTheme.GAP_S_DP))
        addView(navButton(R.drawable.ic_mdi_phone, R.string.launcher_app_phone) { openPackage(PHONE_PACKAGE) })
    }

    /** Şeffaf kare dokunma hedefi (72dp); etkin olan vurgu renginde dolu. */
    private fun navButton(iconRes: Int, labelRes: Int, active: Boolean = false, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            background = if (active) rounded(context, LauncherTheme.ACCENT, NAV_RADIUS_DP) else rounded(context, LauncherTheme.CONTROL, NAV_RADIUS_DP)
            contentDescription = context.getString(labelRes)
            isClickable = true
            isFocusable = true
            addView(
                icon(context, iconRes, LauncherTheme.ICON_M_DP, if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT),
                FrameLayout.LayoutParams(context.dp(LauncherTheme.ICON_M_DP), context.dp(LauncherTheme.ICON_M_DP), Gravity.CENTER),
            )
            layoutParams = LinearLayout.LayoutParams(context.dp(NAV_DP), context.dp(NAV_DP))
            setOnClickListener { onClick() }
            pressFeedback()
        }

    // --- Bağlama ---

    private fun bindClimate(r: ClimateReadout) = climateControl.bind(r)

    private fun bindMedia(state: MediaState) {
        val show = state.sessionAvailable && !state.title.isNullOrBlank()
        mediaMini.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        mediaTitle.text = state.title
        playIcon.setImageResource(if (state.playing) R.drawable.ic_mdi_pause else R.drawable.ic_mdi_play)
    }

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

        private const val NAV_DP = 72
        private const val NAV_RADIUS_DP = 18f
        private const val BUTTON_DP = 56
        private const val MEDIA_ICON_DP = 28
        private const val MEDIA_WIDTH_DP = 340

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
