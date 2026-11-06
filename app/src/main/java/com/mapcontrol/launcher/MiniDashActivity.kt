package com.mapcontrol.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot
import java.util.Date

/**
 * Mini gösterge: bölünmüş ekranın bir yarısında (960 × ~610) açılan, boyutu değişebilen ayrı etkinlik (karar §5). Ana ekran
 * (launcher) kendisi bölünemediği için bu etkinlik FrostNav'ın serbest pencere bölmesinde ya da uygulama listesinden açılır.
 *
 * Yukarıdan aşağı: saat + menzil + yakıt ("Tam ekran" düğmesiyle), kısa medya (başlık/sanatçı, önceki/oynat/sonraki),
 * navigasyon satırı (Yandex rotası varken, [NavigationMiniSource]), uyarı şeridi (yalnızca başka yerde görünmeyenler,
 * [MiniAlerts]; Sahne ile aynı [StageStrip]) ve iki büyük hızlı tuş: sürücü koltuk ısıtma, yolcu koltuk soğutma (Sahne koltuk
 * görselleriyle). Klima yok (fabrika çubuğunda).
 *
 * Veri kaynakları ana ekranınkilerle aynıdır ve paylaşılır ([VehicleMetricsRepository], [TripSource], [ExtraSource],
 * [LauncherMediaController]); koltuk tuşları [SeatHeatModel] üzerinden yazar, yani yazma kapıları (LauncherEdition, test
 * anahtarı, demo) aynen geçerlidir; yeni bir araç yazma yolu yoktur. Görünmezken (onStop) tüm dinleyiciler ve yoklamalar durur.
 */
class MiniDashActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val metrics by lazy { VehicleMetricsRepository.getInstance(applicationContext) }
    private lateinit var media: LauncherMediaController
    private lateinit var seatHeat: SeatHeatModel
    private lateinit var seatVent: SeatHeatModel
    private var nav: MiniNavSource = MiniNavSource.NONE
    private var started = false

    // --- Durum (yalnızca değişince yeniden bağlanır) ---
    private var snapshot: VehicleMetricsSnapshot? = null
    private var trip = TripData()
    private var extra = ExtraData()
    private var navHint: NavHint? = null
    private var lastTop: Triple<Int?, Int?, Boolean>? = null
    private var lastAlerts: List<MiniAlerts.Item>? = null
    private var lastMedia: MediaUi? = null
    private var layoutNow: MiniLayout? = null
    private var detail: CarDetailSheet? = null

    private val metricsListener = VehicleMetricsRepository.Listener { s -> main.post { if (started && !LauncherDemo.enabled) { snapshot = s; bindTop() } } }
    private val tripListener = TripSource.Listener { if (it != trip) { trip = it; bindTop(); bindAlerts() } }
    private val extraListener = ExtraSource.Listener { if (it != extra) { extra = it; bindAlerts() } }
    private val mediaListener = LauncherMediaController.Listener { s -> if (!LauncherDemo.enabled) main.post { if (started) bindMedia(s) } }
    private val navListener = MiniNavSource.Listener { h -> navHint = h; bindNav() }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = bindClock()
    }

    // --- Görünümler ---
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var infoBlock: LinearLayout
    private lateinit var valuesRow: LinearLayout
    private lateinit var midBlock: LinearLayout
    private var arrangedWide: Boolean? = null
    private lateinit var clock: TextView
    private lateinit var rangeValue: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var fuelValue: TextView
    private lateinit var fuelLabel: TextView
    private lateinit var fullButton: LinearLayout
    private lateinit var mediaPanel: LinearLayout
    private lateinit var artwork: ImageView
    private lateinit var artworkBox: FrameLayout
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var prev: FrameLayout
    private lateinit var play: FrameLayout
    private lateinit var playIcon: ImageView
    private lateinit var next: FrameLayout
    private lateinit var navRow: LinearLayout
    private lateinit var navManeuver: TextView
    private lateinit var navDistance: TextView
    private lateinit var navEta: TextView
    private lateinit var alertRow: StageStrip
    private lateinit var toggles: LinearLayout
    private lateinit var heatTile: SeatToggle
    private lateinit var ventTile: SeatToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherEdition.init(applicationContext)
        // Demo bayrakları LauncherDemo'nun kendi okuyucularıyla (yalnızca debug derlemede açılır).
        LauncherDemo.applyFrom(this, intent)
        LauncherDemo.applyPreview(intent)
        nav = navSourceFactory(this, intent)
        media = LauncherMediaController(this)
        val io = LauncherIo.serial()
        seatHeat = SeatHeatModel(this, vent = false, io = io) { heatTile.bind(it.driver, it.connected) }
        seatVent = SeatHeatModel(this, vent = true, io = io) { ventTile.bind(it.passenger, it.connected) }
        window.setBackgroundDrawable(ColorDrawable(Sahne.GROUND))
        setContentView(build())
        LauncherStartup.attach(window.decorView)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        LauncherDemo.applyFrom(this, intent)
        LauncherDemo.applyPreview(intent)
        val nextNav = navSourceFactory(this, intent)
        if (nextNav.javaClass != nav.javaClass) {
            if (started) nav.stop()
            nav = nextNav
            if (started) nav.start(navListener)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        bindClock()
        snapshot = if (LauncherDemo.enabled) LauncherDemo.vehicle() else metrics.currentSnapshot()
        bindTop()
        if (!LauncherDemo.enabled) metrics.addListener(metricsListener)
        TripSource.add(this, tripListener)
        ExtraSource.add(this, extraListener)
        media.addListener(mediaListener)
        media.start()
        bindMedia(if (LauncherDemo.enabled) LauncherDemo.media() else media.currentState())
        seatHeat.onVisible()
        seatVent.onVisible()
        nav.start(navListener)
    }

    override fun onResume() {
        super.onResume()
        LauncherEdition.setHomeForeground(true)
    }

    override fun onPause() {
        LauncherEdition.setHomeForeground(false)
        super.onPause()
    }

    override fun onStop() {
        started = false
        try { unregisterReceiver(timeReceiver) } catch (e: IllegalArgumentException) { }
        metrics.removeListener(metricsListener)
        TripSource.remove(tripListener)
        ExtraSource.remove(extraListener)
        media.removeListener(mediaListener)
        media.stop()
        seatHeat.onHidden()
        seatVent.onHidden()
        nav.stop()
        main.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        detail?.dialog?.dismiss()
        detail = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Pencere boyutu değişti (serbest pencere): ölçüler yerleşim dinleyicisinde yeniden seçilir.
        root.requestLayout()
    }

    /**
     * "Tam ekran": bizim ana ekranımızı tam ekran (pencere modu 1) öne getirir ve bu görevi kapatır. Ana ekran her iki
     * sürümde de açıkça adlandırılır (HOME rolü kapalıyken de bizim launcher'ımıza döner).
     */
    private fun goFullScreen() {
        try {
            val intent = Intent(this, HomeLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val opts = ActivityOptions.makeBasic().toBundle() ?: Bundle()
            // ActivityOptions.setLaunchWindowingMode (gizli API) ile aynı anahtarlar; 1 = WINDOWING_MODE_FULLSCREEN.
            opts.putInt("android.activity.windowingMode", WINDOWING_MODE_FULLSCREEN)
            opts.putInt("android:windowingMode", WINDOWING_MODE_FULLSCREEN)
            startActivity(intent, opts)
        } catch (e: RuntimeException) {
            Log.w(TAG, "tam ekran açılamadı", e)
        }
        finishAndRemoveTask()
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Bağlama
    // ------------------------------------------------------------------------------------------------------------------

    private fun bindClock() {
        clock.text = DateFormat.getTimeFormat(this).format(Date())
    }

    private fun bindTop() {
        val s = snapshot
        val range = s?.rangeKm?.takeIf { s.connected && VehicleMetricsSnapshot.isValid(it) && it >= 0 }
        val fuel = s?.fuelPercentOrNull()
        val low = trip.lowFuel == true
        val key = Triple(range, fuel, low)
        if (key == lastTop) return
        lastTop = key
        val none = getString(R.string.launcher_unavailable)
        rangeValue.text = range?.let { getString(R.string.launcher_range_value, it) } ?: none
        fuelValue.text = fuel?.let { getString(R.string.launcher_percent, it) } ?: none
        fuelValue.setTextColor(if (low) Sahne.AMBER else Sahne.NUMERAL)
        rangeValue.setTextColor(if (low) Sahne.AMBER else Sahne.NUMERAL)
    }

    /** Uyarı şeridi (Sahne ile aynı görünüş ve kurallar, [StageStrip]); hiç uyarı yoksa gizli. */
    private fun bindAlerts() {
        alertRow.setDriving(if (LauncherDemo.enabled) LauncherDemo.moving == true else trip.moving == true)
        val all = MiniAlerts.build(
            extra.tires, trip.lowFuel, trip.serviceKm, trip.serviceDays, trip.pm25In,
            batteryV = trip.batteryV, coolantC = trip.coolantC, fuelCapOpen = extra.parts[BodyPart.FUEL_CAP] == true,
        )
        if (all == lastAlerts) return
        lastAlerts = all
        alertRow.bind(all) { stripText(this, it) }
        updateMid()
    }

    /** Şerit dokunuşu: yakıt azda en yakın istasyon; diğerlerinde araç detayı (lastik basınçları, bakım, hava). */
    private fun onAlertTap(item: MiniAlerts.Item?) {
        if (item?.kind == MiniAlerts.Kind.FUEL_LOW) {
            openNearestStation(this)
            return
        }
        if (detail != null || isFinishing || isDestroyed) return
        val sheet = CarDetailSheet(this)
        detail = sheet
        sheet.bind(CarDetail.model(null, if (LauncherDemo.enabled) ReadOnlyDemo.extra() else extra, trip, trip.lowFuel), emptySet())
        sheet.show { detail = null }
    }

    private fun bindNav() {
        val h = navHint
        if (!MiniNav.shown(h) || h == null) {
            navRow.visibility = View.GONE
            updateMid()
            return
        }
        navManeuver.text = h.maneuver.orEmpty()
        val d = MiniNav.distance(h.distanceM)
        navDistance.text = d?.let { (v, km) -> getString(if (km) R.string.launcher_mini_nav_km else R.string.launcher_mini_nav_m, v) }.orEmpty()
        navDistance.visibility = if (d == null) View.GONE else View.VISIBLE
        val eta = MiniNav.arrival(h.arrivalMinutesOfDay, DateFormat.is24HourFormat(this))
        navEta.text = eta?.let { getString(R.string.launcher_mini_nav_eta, it) }.orEmpty()
        navEta.visibility = if (eta == null) View.GONE else View.VISIBLE
        navRow.visibility = View.VISIBLE
        updateMid()
    }

    private data class MediaUi(val mode: Int, val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    private fun bindMedia(state: MediaState) {
        val mode = when {
            !state.notificationAccessGranted -> MODE_NO_ACCESS
            !state.sessionAvailable -> MODE_IDLE
            else -> MODE_SESSION
        }
        val resume = if (mode == MODE_IDLE) media.lastSourcePackage() else null
        val ui = when (mode) {
            MODE_SESSION -> MediaUi(mode, state.title.orEmpty().ifBlank { getString(R.string.launcher_media_title_neutral) },
                state.artist.orEmpty(), state.playing, state.artwork)
            MODE_IDLE -> MediaUi(mode, getString(R.string.launcher_media_idle_title),
                getString(if (resume != null) R.string.launcher_media_idle_resume else R.string.launcher_media_idle_hint), false, null)
            else -> MediaUi(mode, getString(R.string.launcher_media_title_neutral), getString(R.string.launcher_media_access_hint), false, null)
        }
        if (ui == lastMedia) return
        lastMedia = ui
        title.text = ui.title
        artist.text = ui.artist
        artist.visibility = if (ui.artist.isEmpty()) View.GONE else View.VISIBLE
        val session = mode == MODE_SESSION
        prev.visibility = if (session) View.VISIBLE else View.INVISIBLE
        next.visibility = prev.visibility
        play.visibility = if (session || resume != null) View.VISIBLE else View.INVISIBLE
        playIcon.setImageResource(if (ui.playing) R.drawable.ic_sahne_pause else R.drawable.ic_sahne_play)
        play.contentDescription = getString(if (ui.playing) R.string.launcher_media_pause else R.string.launcher_media_play)
        if (ui.art != null) {
            artwork.setImageBitmap(ui.art)
            artwork.visibility = View.VISIBLE
        } else {
            artwork.setImageDrawable(null)
            artwork.visibility = View.GONE
        }
        mediaPanel.contentDescription = "${ui.title}, ${ui.artist}"
    }

    private fun onPlay() {
        when (lastMedia?.mode) {
            MODE_SESSION -> media.playPause()
            MODE_IDLE -> media.launchLastSource()
            else -> media.openNotificationAccessSettings()
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Kurulum
    // ------------------------------------------------------------------------------------------------------------------

    private fun build(): View {
        root = FrameLayout(this).apply { background = ColorDrawable(Sahne.GROUND) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // Üst satır: saat | menzil, yakıt | tam ekran.
        clock = sahneText(this, 60f, Sahne.NUMERAL, 300, -0.02f)
        rangeValue = sahneText(this, 34f, Sahne.NUMERAL, 400)
        rangeLabel = sahneText(this, 18f, Sahne.INK_3, 500).apply { setText(R.string.launcher_mini_range) }
        fuelValue = sahneText(this, 34f, Sahne.NUMERAL, 400)
        fuelLabel = sahneText(this, 18f, Sahne.INK_3, 500).apply { setText(R.string.launcher_mini_fuel) }
        fullButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GlassDrawable(this@MiniDashActivity, Sahne.PILL_RADIUS_DP, shadow = 0f, control = true)
            setPadding(dp(20), 0, dp(24), 0)
            minimumWidth = dp(MiniLayout.MIN_TOUCH_DP)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_sahne_home)
                setTint(Sahne.CONTROL_ICON)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            addView(sahneText(context, 20f, Sahne.INK, 500).apply { setText(R.string.launcher_mini_fullscreen) })
            contentDescription = getString(R.string.launcher_mini_fullscreen)
            isClickable = true
            isFocusable = true
            setOnClickListener { goFullScreen() }
            pressFeedback()
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        valuesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        midBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }

        // Medya paneli.
        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        artworkBox = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3A1D4F.toInt(), 0xFF7A2F57.toInt(), 0xFFD0654A.toInt())).apply { cornerRadius = dpF(16f) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, dpF(16f))
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_sahne_note)
                setTint(0xD1FFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            addView(artwork, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        title = sahneText(this, 26f, Sahne.INK, 600).apply { ellipsize = TextUtils.TruncateAt.END }
        artist = sahneText(this, 20f, Sahne.INK_2, 400).apply { ellipsize = TextUtils.TruncateAt.END }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(artist, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            isClickable = true
            setOnClickListener { if (lastMedia?.mode == MODE_NO_ACCESS) media.openNotificationAccessSettings() else media.launchLastSource() }
        }
        prev = roundButton(R.drawable.ic_sahne_prev, R.string.launcher_media_prev, primary = false) { media.skipToPrevious() }
        play = roundButton(R.drawable.ic_sahne_play, R.string.launcher_media_play, primary = true) { onPlay() }
        playIcon = play.getChildAt(0) as ImageView
        next = roundButton(R.drawable.ic_sahne_next, R.string.launcher_media_next, primary = false) { media.skipToNext() }
        mediaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(this@MiniDashActivity, Sahne.PANEL_RADIUS_DP, shadow = 0f)
            addView(artworkBox, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(20) })
            addView(meta, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(prev)
            addView(play, (play.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(next)
        }

        // Navigasyon satırı (kaynak yokken gizli).
        navManeuver = sahneText(this, 24f, Sahne.INK, 500).apply { ellipsize = TextUtils.TruncateAt.END }
        navDistance = sahneText(this, 28f, Sahne.NUMERAL, 500)
        navEta = sahneText(this, 20f, Sahne.INK_2, 500)
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(this@MiniDashActivity, Sahne.CONTROL_RADIUS_DP, shadow = 0f)
            setPadding(dp(20), 0, dp(20), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_sahne_route)
                setTint(Sahne.ICE)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
            addView(navDistance, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(14) })
            addView(navManeuver, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(navEta, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
            visibility = View.GONE
        }

        // Uyarı şeridi (yalnızca bir şey varken; Sahne'deki şeritle aynı).
        alertRow = StageStrip(this) { onAlertTap(it) }

        // Hızlı tuşlar.
        heatTile = SeatToggle(this, getString(R.string.launcher_mini_seat_heat_driver), Sahne.AMBER, vent = false) { seatHeat.cycle(driver = true) }
        ventTile = SeatToggle(this, getString(R.string.launcher_mini_seat_cool_passenger), Sahne.COOL, vent = true) { seatVent.cycle(driver = false) }
        toggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(heatTile, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(ventTile, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(16) })
        }
        arrange(MiniLayout.forSize(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp).wide)

        root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val d = resources.displayMetrics.density
            val next = MiniLayout.forSize(((r - l) / d).toInt(), ((b - t) / d).toInt())
            if (next != layoutNow) {
                layoutNow = next
                v.post { applyLayout(next) }
            }
        }
        applyLayout(MiniLayout.forSize(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp).also { layoutNow = it })
        return root
    }

    /**
     * Çocukları dikey sütun (960 genişlikli yarı) ya da tek satır (geniş ve kısa pencere, [MiniLayout.wide]) olarak dizer.
     * Görünümler aynıdır; yalnızca ebeveyn ve yerleşim parametreleri değişir.
     */
    private fun arrange(wide: Boolean) {
        if (arrangedWide == wide) return
        arrangedWide = wide
        for (g in listOf(column, top, infoBlock, valuesRow, midBlock, toggles)) g.removeAllViews()
        val rangeBlock = valueBlock(rangeValue, rangeLabel)
        val fuelBlock = valueBlock(fuelValue, fuelLabel)
        if (!wide) {
            column.orientation = LinearLayout.VERTICAL
            top.addView(clock, LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(rangeBlock)
            top.addView(View(this), LinearLayout.LayoutParams(dp(28), 1))
            top.addView(fuelBlock)
            top.addView(View(this), LinearLayout.LayoutParams(dp(24), 1))
            top.addView(fullButton, LinearLayout.LayoutParams(WRAP, dp(MiniLayout.MIN_TOUCH_DP)))
            column.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
            column.addView(mediaPanel, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
            column.addView(navRow, LinearLayout.LayoutParams(MATCH, dp(68)).apply { topMargin = dp(12) })
            column.addView(alertRow, LinearLayout.LayoutParams(WRAP, dp(StageStrip.HEIGHT_DP)).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            toggles.orientation = LinearLayout.HORIZONTAL
            toggles.addView(heatTile, LinearLayout.LayoutParams(0, MATCH, 1f))
            toggles.addView(ventTile, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(16) })
            column.addView(toggles, LinearLayout.LayoutParams(MATCH, dp(120)).apply { topMargin = dp(12) })
        } else {
            // Tek satır: saat + menzil/yakıt + tam ekran | medya | rota + uyarı | koltuk tuşları (üst üste).
            column.orientation = LinearLayout.HORIZONTAL
            valuesRow.addView(rangeBlock)
            valuesRow.addView(View(this), LinearLayout.LayoutParams(dp(28), 1))
            valuesRow.addView(fuelBlock)
            infoBlock.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
            infoBlock.addView(valuesRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            infoBlock.addView(fullButton, LinearLayout.LayoutParams(WRAP, dp(MiniLayout.MIN_TOUCH_DP)).apply { topMargin = dp(20) })
            column.addView(infoBlock, LinearLayout.LayoutParams(dp(WIDE_INFO_DP), MATCH))
            column.addView(mediaPanel, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(16) })
            midBlock.addView(navRow, LinearLayout.LayoutParams(MATCH, dp(68)))
            midBlock.addView(alertRow, LinearLayout.LayoutParams(WRAP, dp(StageStrip.HEIGHT_DP)).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            column.addView(midBlock, LinearLayout.LayoutParams(dp(WIDE_MID_DP), MATCH).apply { marginStart = dp(16) })
            toggles.orientation = LinearLayout.VERTICAL
            toggles.addView(heatTile, LinearLayout.LayoutParams(MATCH, 0, 1f))
            toggles.addView(ventTile, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(16) })
            column.addView(toggles, LinearLayout.LayoutParams(dp(WIDE_TOGGLES_DP), MATCH).apply { marginStart = dp(16) })
        }
        updateMid()
    }

    /** Geniş yerleşimde rota ve uyarı sütunu yalnızca gösterecek bir şey varken yer tutar. */
    private fun updateMid() {
        if (arrangedWide != true) return
        midBlock.post {
            val any = navRow.visibility == View.VISIBLE || alertRow.visibility == View.VISIBLE
            val v = if (any) View.VISIBLE else View.GONE
            if (midBlock.visibility != v) midBlock.visibility = v
        }
    }

    private fun applyLayout(m: MiniLayout) {
        arrange(m.wide)
        val p = dp(m.paddingDp)
        column.setPadding(p, p, p, p)
        clock.textSize = m.clockSp
        rangeValue.textSize = m.valueSp
        fuelValue.textSize = m.valueSp
        rangeLabel.visibility = if (m.showLabels) View.VISIBLE else View.GONE
        fuelLabel.visibility = rangeLabel.visibility
        mediaPanel.setPadding(dp(if (m.showArtwork) 20 else 18), dp(12), dp(18), dp(12))
        artworkBox.visibility = if (m.showArtwork) View.VISIBLE else View.GONE
        resize(prev, m.skipDp)
        resize(next, m.skipDp)
        resize(play, m.playDp)
        if (!m.wide) (toggles.layoutParams as LinearLayout.LayoutParams).height = dp(m.toggleHeightDp)
        toggles.requestLayout()
    }

    private fun valueBlock(value: TextView, label: TextView): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        addView(value)
        addView(label)
    }

    private fun roundButton(icon: Int, label: Int, primary: Boolean, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Sahne.CTA_TOP, Sahne.CTA_BOTTOM)).apply {
            shape = GradientDrawable.OVAL
            if (Sahne.CTA_RING != 0) setStroke(dp(1), Sahne.CTA_RING)
        } else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Sahne.SOFT_FILL)
            setStroke(dp(1), Sahne.HAIR)
        }
        contentDescription = getString(label)
        isClickable = true
        isFocusable = true
        val iconDp = if (primary) 34 else 30
        addView(ImageView(context).apply {
            setImageResource(icon)
            setTint(if (primary) Sahne.CTA_INK else Sahne.INK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 88 else 72), dp(if (primary) 88 else 72))
        setOnClickListener { onClick() }
        pressFeedback()
    }

    private fun resize(v: View, sizeDp: Int) {
        val lp = v.layoutParams ?: return
        val px = dp(sizeDp)
        if (lp.width == px && lp.height == px) return
        lp.width = px
        lp.height = px
        v.layoutParams = lp
    }

    private fun dp(v: Int) = (this as Context).dp(v)
    private fun dpF(v: Float) = (this as Context).dpF(v)

    /**
     * Büyük koltuk tuşu: Sahne şeridindeki koltuk görseli (`launcher_art_seat_*`, [SahneArt]; ısıtmada turuncu, soğutmada buz
     * tonlu, kapalıda sönük koltuk; yolcu koltuğu yatay çevrili), başlık, seviye metni ve üç seviye çizgisi. Dokunuş [onTap]'e
     * gider (seviye döngüsü [SeatHeatModel.cycle]'da; yazma kapıları orada). Okunamıyorsa seviye "—" ve tuş sönük görünür.
     */
    private class SeatToggle(context: Context, label: String, private val activeColor: Int, private val vent: Boolean, onTap: () -> Unit) : LinearLayout(context) {
        private val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // Görsel sürücü koltuğuna bakar; yolcu (soğutma) tuşunda çevrilir.
            if (vent) scaleX = -1f
            background = SahneArt.spotlight()
        }
        private var artKey: String? = null
        private val title = sahneText(context, 20f, Sahne.INK_2, 500).apply {
            text = label
            ellipsize = TextUtils.TruncateAt.END
        }
        private val value = sahneText(context, 28f, Sahne.INK, 500)
        private val pips = Array(3) { View(context) }
        private var shown: Pair<Int?, Boolean>? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(context, Sahne.PANEL_RADIUS_DP, shadow = 0f, control = true)
            setPadding(context.dp(22), 0, context.dp(22), 0)
            addView(icon, LayoutParams(context.dp(72), context.dp(72)).apply { marginEnd = context.dp(14) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                // Üstteki çizgi en yüksek seviye (3), alttaki 1.
                for (i in 2 downTo 0) addView(pips[i], LayoutParams(context.dp(28), context.dp(6)).apply { topMargin = context.dp(5) })
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12) })
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            pressFeedback()
            bind(null, false)
        }

        fun bind(level: Int?, connected: Boolean) {
            val key = level to connected
            if (key == shown) return
            shown = key
            val lv = if (connected) level else null
            val on = (lv ?: 0) > 0
            value.text = if (lv == null) context.getString(R.string.launcher_unavailable) else context.getString(SeatHeatModel.levelLabel(lv))
            value.setTextColor(if (on) activeColor else if (lv == null) Sahne.INK_3 else Sahne.INK)
            val tone = if (!on) StripTone.NORMAL else if (vent) StripTone.COOL else StripTone.HEAT
            val art0 = SahneArt.seatKey(tone, lv ?: 0)
            if (art0 != artKey) {
                artKey = art0
                val art = SahneArt.drawable(context, art0)
                if (art != null) {
                    icon.setImageDrawable(art)
                    icon.imageTintList = null
                } else {
                    icon.setImageResource(R.drawable.ic_sahne_seat)
                    icon.setTint(if (on) activeColor else Sahne.INK_3)
                }
            }
            icon.colorFilter = SahneArt.filter(lv == null)
            pips.forEachIndexed { i, p ->
                p.background = GradientDrawable().apply {
                    cornerRadius = context.dpF(3f)
                    setColor(if (lv != null && i < lv) activeColor else Sahne.TRACK)
                }
            }
            contentDescription = "${title.text}: ${value.text}"
        }
    }

    companion object {
        private const val TAG = "MiniDash"
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val MODE_NO_ACCESS = 0
        private const val MODE_IDLE = 1
        private const val MODE_SESSION = 2
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Geniş yerleşimde sütun genişlikleri (dp): saat + menzil, rota/uyarı, koltuk tuşları. */
        private const val WIDE_INFO_DP = 340
        private const val WIDE_MID_DP = 400
        private const val WIDE_TOGGLES_DP = 360

        /** Demo navigasyon satırı: `--ez launcher_mini_demo_nav true` (yalnızca demo açıkken). */
        const val EXTRA_DEMO_NAV = "launcher_mini_demo_nav"

        /**
         * Navigasyon kaynağı: Yandex rehberlik bildirimi ([NavigationMiniSource] → [NavigationSource]; rota yokken satır gizli).
         * Demo: `--ez launcher_mini_demo_nav true` sabit ipucu, `--ez launcher_demo_nav true` ana ekrandaki demo rotası.
         */
        internal var navSourceFactory: (Context, Intent?) -> MiniNavSource = { context, intent ->
            if (LauncherDemo.enabled && intent?.getBooleanExtra(EXTRA_DEMO_NAV, false) == true) MiniNavSource.DEMO else NavigationMiniSource(context)
        }
    }
}
