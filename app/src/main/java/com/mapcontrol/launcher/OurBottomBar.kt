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
import android.widget.Toast
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.media.LauncherMediaController
import com.mapcontrol.media.LauncherMediaController.MediaState
import com.mapcontrol.util.AppIconHelper
import com.mapcontrol.util.AppLaunchHelper
import com.mapcontrol.util.ProjectionTargetApps
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

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
 *
 * İki stil, aynı silüet ([DockShape]): **A "Klima odaklı"** — orta yamuk zengin klima ([RichClimate]: renk alanlı sıcaklık,
 * koltuk ışığı, dönen fan, A/C penceresi); **B "Akıllı şerit"** — beş yuva uzun basışla seçilir ([BarSlots]), orta
 * klasik klima, direksiyon simgesinin yerinde mini oynatıcı ([BarMiniPlayer]) ve kapı/bagaj açıkken uyarı çipi ([BarOpenAlert]).
 */
class OurBottomBar(private val activity: Activity, private val style: BarStyle = BarStyle.A) : LauncherWidget, Themeable {

    private val context: Context = activity
    private val io = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())
    private var startup: LauncherStartup.Deferred? = null
    private var readout = ClimateReadout()

    /** Tema bağlı atamalar (kurucuda bir kez, [applyTheme]'de yeniden); bkz. [ThemeBindings]. */
    private val theme = ThemeBindings()
    private val surface = DockSurface(context)

    // --- Klima ---
    private val climateControl = ClimateControl(context, io, main)
    private val rich: RichClimate? = if (style == BarStyle.A) RichClimate(activity, io, main, climateControl) { l, r -> surface.setTint(l, r) } else null

    // --- Stil B: yuvalar, mini oynatıcı, uyarı ---
    private val slotIds: MutableList<String> = if (style == BarStyle.B) BarSlots.load(context).toMutableList() else BarSlots.DEFAULTS.toMutableList()
    private val slotViews = arrayOfNulls<FrameLayout>(BarSlots.COUNT)
    private val slotIcons = arrayOfNulls<ImageView>(BarSlots.COUNT)
    private val slotTiles = arrayOfNulls<FrameLayout>(BarSlots.COUNT)
    private val appIcons = HashMap<String, Drawable?>()
    private val miniPlayer: BarMiniPlayer? = if (style == BarStyle.B) BarMiniPlayer(context) else null
    private val openAlert: BarOpenAlert? = if (style == BarStyle.B) BarOpenAlert(context) else null
    private val metrics = VehicleMetricsRepository.getInstance(context.applicationContext)
    private var snapshot: VehicleMetricsSnapshot? = null
    private var rearReading: SoundFieldReading? = null
    private var rearPending: Boolean? = null
    private val rearStore by lazy { RearSpeakerStore(context) }
    private var pickerDialog: Dialog? = null
    private val metricsListener = VehicleMetricsRepository.Listener { s -> if (!LauncherDemo.enabled) main.post { onVehicle(s) } }
    private val demoBody = Runnable { onVehicle(LauncherDemo.vehicle()) }

    /**
     * Araç çubuğunun silueti ([DockShape]): 1920x110 başvuru bandında öğeler OEM x konumlarında ([DockLayout]);
     * ana ekran 212 · uygulamalar 338 · araç 464 | sıcaklık 750 · fan 960 · sıcaklık 1170 | ayarlar 1456 · telefon 1708.
     */
    override val view: View = DockLayout(context).apply {
        background = surface
        fun nav(v: View, cx: Float, w: Float = NAV_BOX_W) = addCentered(v, cx, w)
        if (style == BarStyle.A) {
            // Ana ekran: sistemin ana ekranına çıkar (launcher varsayılan değilken OEM ana ekranı); varsayılan bizsek bir şey yapmaz.
            nav(navButton(R.drawable.ic_mdi_home, R.string.launcher_bar_home) { leaveToSystemHome() }, X_HOME)
            nav(navButton(R.drawable.ic_mdi_view_grid, R.string.launcher_bar_apps) { showApps() }, X_APPS)
            nav(navButton(R.drawable.ic_mdi_car, R.string.launcher_vehicle_settings) { openPackage(VEHICLE_SETTINGS_PACKAGE) }, X_CAR)
            val r = rich!!
            addAt(r.driverView, 624f, DockShape.BODY_TOP, 872f, DockShape.BODY_BOTTOM)
            addAt(r.fanView, 872f, DockShape.BODY_TOP, 1048f, DockShape.BODY_BOTTOM)
            addAt(r.passengerView, 1048f, DockShape.BODY_TOP, 1296f, DockShape.BODY_BOTTOM)
            nav(navButton(R.drawable.ic_mdi_cog, R.string.launcher_bar_settings) { openAndroidSettings() }, X_SETTINGS)
            nav(navButton(R.drawable.ic_mdi_phone, R.string.launcher_app_phone) { openPackage(PHONE_PACKAGE) }, X_PHONE)
        } else {
            val xs = floatArrayOf(X_HOME, X_APPS, X_CAR, X_SETTINGS, X_PHONE)
            for (i in 0 until BarSlots.COUNT) nav(slotButton(i), xs[i], B_SLOT_W)
            addAt(climateControl.driverView, 630f, DockShape.BODY_TOP, 870f, DockShape.BODY_BOTTOM)
            addAt(climateControl.fanView, 870f, DockShape.BODY_TOP, 1050f, DockShape.BODY_BOTTOM)
            addAt(climateControl.passengerView, 1050f, DockShape.BODY_TOP, 1290f, DockShape.BODY_BOTTOM)
            // Direksiyon simgesinin yeri (OEM x 1582): mini oynatıcı; kapı/bagaj açıkken aynı yerde uyarı çipi.
            addAt(miniPlayer!!.view, B_ZONE_L, DockShape.BODY_TOP, B_ZONE_R, DockShape.BODY_BOTTOM)
            addAt(openAlert!!.view, B_ZONE_L, DockShape.BODY_TOP, B_ZONE_R, DockShape.BODY_BOTTOM)
        }
    }

    /** Tema değişti: yüzey, ayraçlar, düğmeler ve klima bölümleri yeni renklerle yenilenir; görünümler korunur. */
    override fun applyTheme() {
        appsDialog?.dismiss()
        appsDialog = null
        pickerDialog?.dismiss()
        pickerDialog = null
        theme.reapply()
        surface.applyTheme()
        climateControl.applyTheme()
        rich?.applyTheme()
        miniPlayer?.applyTheme()
        openAlert?.applyTheme()
        refreshSlots()
        view.invalidate()
    }

    override fun onVisible() {
        bindClimate(readout)
        rich?.onVisible()
        if (style == BarStyle.B) {
            miniPlayer?.onVisible()
            openAlert?.onVisible()
            onVehicle(if (LauncherDemo.enabled) LauncherDemo.vehicle() else metrics.currentSnapshot())
            metrics.addListener(metricsListener)
            LauncherDemo.onBodyChangedBar = demoBody
            refreshSlots()
            if (BarSlots.consumeHint(context)) Toast.makeText(context, R.string.launcher_bar_slot_hint, Toast.LENGTH_LONG).show()
        }
        startup?.cancel()
        startup = LauncherStartup.later { main.post(poll) }
    }

    override fun onHidden() {
        startup?.cancel()
        main.removeCallbacks(poll)
        main.removeCallbacks(rearRevert)
        climateControl.onHidden()
        rich?.onHidden()
        if (style == BarStyle.B) {
            miniPlayer?.onHidden()
            openAlert?.onHidden()
            metrics.removeListener(metricsListener)
            if (LauncherDemo.onBodyChangedBar === demoBody) LauncherDemo.onBodyChangedBar = null
        }
        appsDialog?.dismiss()
        appsDialog = null
        pickerDialog?.dismiss()
        pickerDialog = null
    }

    /** Paylaşılan [LauncherIo] havuzu kapatılmaz. */
    override fun release() = onHidden()

    private val poll = object : Runnable {
        override fun run() {
            io.runSafely {
                val read = readClimate()
                val rear = if (style == BarStyle.B && "rear" in slotIds) ComfortReader.readSound().sound else null
                main.post {
                    if (style == BarStyle.B && "rear" in slotIds && rear != rearReading) {
                        rearReading = rear
                        refreshSlots()
                    }
                    if (read == readout) return@post
                    readout = read
                    bindClimate(read)
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    // --- Yerleşim ---

    /**
     * Düğme (gövde yüksekliğinde): zemini yok, çubuğun parçası; basınca örtü. İkon OEM çubuğundaki gibi sade.
     */
    private fun navButton(iconRes: Int, labelRes: Int, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            theme.bind { foreground = dockPressOverlay(context) }
            contentDescription = context.getString(labelRes)
            isClickable = true
            isFocusable = true
            addView(
                icon(context, iconRes, NAV_ICON_DP).also { ic -> theme.bind { ic.setTint(LauncherTheme.DOCK_TEXT) } },
                FrameLayout.LayoutParams(context.dp(NAV_ICON_DP), context.dp(NAV_ICON_DP), Gravity.CENTER),
            )
            setOnClickListener { onClick() }
        }

    // --- Stil B: yuvalar ---

    /** Yuva düğmesi: dokun = eylem, uzun bas = seçici. Simge, yumuşak bir karo içinde; bağlı eyleme göre [bindSlot]'ta kurulur. */
    private fun slotButton(i: Int): View = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        val ic = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val tile = FrameLayout(context).apply { addView(ic, FrameLayout.LayoutParams(context.dp(SLOT_ICON_DP), context.dp(SLOT_ICON_DP), Gravity.CENTER)) }
        addView(tile, FrameLayout.LayoutParams(context.dp(SLOT_TILE_W_DP), context.dp(SLOT_TILE_H_DP), Gravity.CENTER))
        slotViews[i] = this
        slotIcons[i] = ic
        slotTiles[i] = tile
        setOnClickListener { runSlot(slotIds[i]) }
        setOnLongClickListener { pickSlot(i); true }
        // Dokunma geri bildirimi karonun kendi basılı durumundan gelir (tüm kutu değil).
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> tile.isPressed = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> tile.isPressed = false
            }
            false
        }
        bindSlot(i)
    }

    /** Karonun zemini: sakin ton; [accent] verilirse (durum taşıyan yuva) o renkte, basılıyken biraz daha dolu. */
    private fun tileBackground(accent: Int?): Drawable {
        val base = accent ?: LauncherTheme.DOCK_TEXT
        val rest = SeatGlow.withAlpha(base, if (accent != null) 0.26f else 0.09f)
        val down = SeatGlow.withAlpha(base, if (accent != null) 0.40f else 0.20f)
        fun shape(c: Int) = rounded(context, c, SLOT_RADIUS_DP, if (accent != null) SeatGlow.withAlpha(accent, 0.55f) else 0)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(down))
            addState(intArrayOf(), shape(rest))
        }
    }

    private fun slotLabel(id: String): String = when {
        id.startsWith(BarSlots.APP_PREFIX) -> AppIdentity.label(context, id.removePrefix(BarSlots.APP_PREFIX), id.removePrefix(BarSlots.APP_PREFIX))
        else -> SlotKind.byId(id)?.let { context.getString(it.label) } ?: id
    }

    /** İkonu, rengi ve erişilebilirlik adını yuvanın eylemine göre kurar; uygulama ikonları arka planda yüklenir. */
    private fun bindSlot(i: Int) {
        val frame = slotViews[i] ?: return
        val ic = slotIcons[i] ?: return
        val id = slotIds[i]
        frame.contentDescription = slotLabel(id)
        if (id.startsWith(BarSlots.APP_PREFIX)) {
            val pkg = id.removePrefix(BarSlots.APP_PREFIX)
            slotTiles[i]?.background = tileBackground(null)
            ic.imageTintList = null
            if (appIcons.containsKey(pkg)) { ic.setImageDrawable(appIcons[pkg]); return }
            ic.setImageResource(R.drawable.ic_mdi_package_variant)
            ic.setTint(LauncherTheme.DOCK_TEXT)
            io.runSafely {
                val d = loadIcon(pkg)
                main.post { appIcons[pkg] = d; if (slotIds[i] == id) bindSlot(i) }
            }
            return
        }
        val kind = SlotKind.byId(id) ?: SlotKind.HOME
        ic.setImageResource(kind.icon)
        val accent = slotAccent(kind)
        ic.setTint(accent ?: LauncherTheme.DOCK_TEXT)
        slotTiles[i]?.background = tileBackground(accent)
    }

    /** Durum taşıyan yuvalar (arka hoparlör kapalı, bagaj açık) renk alır; diğerleri sade (null). */
    private fun slotAccent(kind: SlotKind): Int? = when (kind) {
        SlotKind.REAR -> if (rearMuted() == true) LauncherTheme.ACCENT else null
        SlotKind.TRUNK -> if (snapshot?.isTrunkOpen == true) LauncherTheme.ALERT else null
        else -> null
    }

    private fun refreshSlots() {
        if (style != BarStyle.B) return
        for (i in 0 until BarSlots.COUNT) bindSlot(i)
    }

    private fun pickSlot(i: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        pickerDialog?.dismiss()
        pickerDialog = showSlotPicker(
            activity, slotIds[i], BarSlots.DEFAULTS[i],
            onPick = { id -> setSlot(i, id) },
            onPickApp = { showApps { pkg -> setSlot(i, BarSlots.APP_PREFIX + pkg) } },
        ).apply { setOnDismissListener { if (pickerDialog === this) pickerDialog = null } }
    }

    private fun setSlot(i: Int, id: String) {
        if (!BarSlots.valid(id)) return
        slotIds[i] = id
        BarSlots.save(context, slotIds)
        bindSlot(i)
    }

    /** Yuvanın eylemi. Araca yazan ikisi (arka hoparlör, bagaj) kutucuklarla aynı kapılardan geçer. */
    private fun runSlot(id: String) {
        if (id.startsWith(BarSlots.APP_PREFIX)) { openPackage(id.removePrefix(BarSlots.APP_PREFIX)); return }
        when (SlotKind.byId(id)) {
            SlotKind.HOME -> leaveToSystemHome()
            SlotKind.APPS -> showApps()
            SlotKind.CAR -> openPackage(VEHICLE_SETTINGS_PACKAGE)
            SlotKind.SETTINGS -> openAndroidSettings()
            SlotKind.PHONE -> openPackage(PHONE_PACKAGE)
            SlotKind.REAR -> toggleRear()
            SlotKind.TRUNK -> tapTrunk()
            SlotKind.FROSTNAV -> LauncherEdition.openMain(activity, camera360 = false)
            SlotKind.CAM360 -> LauncherEdition.openMain(activity, camera360 = true)
            SlotKind.HOME_SETTINGS -> LauncherEdition.openSettings(activity)
            null -> Unit
        }
    }

    // --- Arka hoparlör (RearSpeakerTile ile aynı mantık ve kapı) ---

    private fun rearMuted(): Boolean? =
        rearPending ?: SoundFieldMath.rearMuted(rearReading?.fade)

    private val rearRevert = Runnable { rearPending = null; refreshSlots() }

    private fun toggleRear() {
        val fade = SoundFieldMath.valid(rearReading?.fade)
        val target = SoundFieldMath.targetFade(fade, rearStore.restoreFade)
        // Okunamıyorsa (test sürümü dışında) tahmin etme.
        if (target == null && !LauncherEdition.READ_ONLY) return
        val enabled = ComfortWriter.rearSpeakerEnabled
        if (enabled && target != null) {
            val nowMuted = rearMuted() == true
            if (!nowMuted && fade != null) rearStore.restoreFade = fade
            rearPending = !nowMuted
            val balance = SoundFieldMath.valid(rearReading?.balance) ?: SoundFieldMath.CENTER
            io.runSafely { ComfortWriter.setFader(balance, target) }
        } else {
            Toast.makeText(context, if (LauncherEdition.READ_ONLY) R.string.launcher_read_only_comfort else R.string.launcher_comfort_pending, Toast.LENGTH_SHORT).show()
        }
        refreshSlots()
        main.removeCallbacks(rearRevert)
        main.postDelayed(rearRevert, if (enabled) 4_000L else 1_500L)
    }

    // --- Bagaj (TrunkTile ile aynı kapı: ComfortWriter.setTrunk park denetimi yapar) ---

    private fun tapTrunk() {
        if (!ComfortWriter.trunkEnabled) {
            Toast.makeText(context, if (LauncherEdition.READ_ONLY) R.string.launcher_read_only_comfort else R.string.launcher_trunk_unverified, Toast.LENGTH_SHORT).show()
            return
        }
        if (snapshot?.isTrunkOpen != true) { writeTrunk(open = true); return }
        // Kapatma, arkada biri varken tehlikeli olabilir: her seferinde onay istenir.
        if (activity.isFinishing || activity.isDestroyed) return
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.launcher_trunk_close_title)
            .setMessage(R.string.launcher_trunk_close_message)
            .setPositiveButton(R.string.launcher_trunk_close_confirm) { _, _ -> writeTrunk(open = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.hideOemBarIfNeeded(context); it.show() }
    }

    private fun writeTrunk(open: Boolean) {
        io.runSafely {
            val result = ComfortWriter.setTrunk(open)
            main.post {
                val msg = when (result) {
                    TrunkWriteResult.NOT_PARKED -> R.string.launcher_trunk_not_parked
                    TrunkWriteResult.DISABLED -> R.string.launcher_trunk_unverified
                    TrunkWriteResult.STALE -> R.string.launcher_trunk_stale
                    else -> 0
                }
                if (msg != 0) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Araç gövdesi güncellendi: kapı/bagaj açıksa uyarı çipi mini oynatıcının yerini alır; bagaj yuvası rengini günceller. */
    private fun onVehicle(s: VehicleMetricsSnapshot) {
        val wasTrunkOpen = snapshot?.isTrunkOpen
        snapshot = s
        val kind = when {
            s.isAnyDoorOpen -> BarOpenAlert.Kind.DOOR
            s.isTrunkOpen -> BarOpenAlert.Kind.TRUNK
            else -> null
        }
        openAlert?.setKind(kind)
        miniPlayer?.view?.visibility = if (kind == null) View.VISIBLE else View.GONE
        if (wasTrunkOpen != s.isTrunkOpen && "trunk" in slotIds) refreshSlots()
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
    private fun showApps(onPick: ((String) -> Unit)? = null) {
        if (appsDialog?.isShowing == true) return
        io.runSafely {
            val rows = ProjectionTargetApps.loadAllLaunchableRows(context)
            val entries = rows.map { r ->
                AppCell(AppIdentity.label(context, r.packageName, r.label), r.packageName, loadIcon(r.packageName))
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed || entries.isEmpty()) return@post
                appsDialog = showLauncherSheet(activity, R.string.launcher_bar_apps, APPS_SHEET_WIDTH_DP) { d ->
                    addView(appsGrid(entries, d, onPick), LinearLayout.LayoutParams(
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

    private fun appsGrid(entries: List<AppCell>, d: Dialog, onPick: ((String) -> Unit)?): View = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            entries.chunked(APPS_COLUMNS).forEach { chunk ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    chunk.forEach { app -> addView(appCell(app, d, onPick), LinearLayout.LayoutParams(0, context.dp(APP_CELL_DP), 1f)) }
                    // Son satırı doldur ki hücre genişlikleri eşit kalsın.
                    repeat(APPS_COLUMNS - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                })
            }
        })
    }

    private fun appCell(app: AppCell, d: Dialog, onPick: ((String) -> Unit)?): View = LinearLayout(context).apply {
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
            if (onPick != null) onPick(app.packageName) else openPackage(app.packageName)
        }
        pressFeedback()
    }

    companion object {
        /** Çubuk penceresi (bant) yüksekliği px: OEM gezinme penceresi 110; gövde içinde y 21–98, altta ~12 px boş pay (alttan kaydırma). */
        fun bandPx(context: Context): Int = DockShape.bandPx(context)

        private const val VEHICLE_SETTINGS_PACKAGE = "com.desaysv.vehiclesetting"
        private const val PHONE_PACKAGE = "com.desaysv.bluetooth.phone"
        private const val POLL_MS = 3_000L
        private const val DASH = "—"

        private const val NAV_ICON_DP = 38
        /** Düğme dokunma kutusu genişliği (başvuru birimi; komşu aralığı 126). */
        private const val NAV_BOX_W = 112f
        /** Stil B yuva kutusu (mini oynatıcıya yer açmak için daha dar) ve oynatıcı/uyarı bölgesi (OEM direksiyon simgesinin yeri). */
        private const val B_SLOT_W = 100f
        private const val SLOT_TILE_W_DP = 72
        private const val SLOT_TILE_H_DP = 58
        private const val SLOT_ICON_DP = 36
        private const val SLOT_RADIUS_DP = 18f
        private const val B_ZONE_L = 1508f
        private const val B_ZONE_R = 1658f
        /** OEM ikon merkezleri (başvuru x). */
        private const val X_HOME = 212f
        private const val X_APPS = 338f
        private const val X_CAR = 464f
        private const val X_SETTINGS = 1456f
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
 * Dock'un basış örtüsü: basılıyken %12 beyaz, bırakınca kısa solar. Bölümün üstünde (foreground); dış şekle
 * kırpma dock'un `clipToOutline`ından gelir.
 */
internal fun dockPressOverlay(context: Context): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(LauncherTheme.DOCK_PRESS))
    addState(intArrayOf(), ColorDrawable(0))
    setEnterFadeDuration(0)
    setExitFadeDuration(150)
}
