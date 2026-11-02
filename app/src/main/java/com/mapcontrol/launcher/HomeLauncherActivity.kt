package com.mapcontrol.launcher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.mapcontrol.R
import com.mapcontrol.vehicle.VehicleMetricsRepository
import com.mapcontrol.vehicle.VehicleMetricsSnapshot

/**
 * Yeni ana ekran (HOME). MapControl'ün geri kalanına "Ayarlar" düğmesinden girilir.
 *
 * Yerleşim (yukarıdan aşağı): üst şerit 72dp (düzenleme modunda düzenleme çubuğu) · düzenlenebilir
 * widget ızgarası · klima ve uygulama kartı 120dp (OEM alt çubuğu olan araçta kart yok, ızgara büyür). Tüm widget'lar yalnızca ekran görünürken çalışır.
 */
class HomeLauncherActivity : Activity(), LauncherGrid.Listener {

    private val parkStore by lazy { LayoutStore(this, LayoutMode.PARK) }
    private val driveStore by lazy { LayoutStore(this, LayoutMode.DRIVE) }
    /** Etkin düzenin deposu; düzenleme ve sıfırlama etkin düzene işler. */
    private val store get() = if (activeMode == LayoutMode.DRIVE) driveStore else parkStore
    private val metrics by lazy { VehicleMetricsRepository.getInstance(applicationContext) }
    private val settings by lazy { LauncherSettings(this, onEditHome = ::enterEditMode) }
    private val metricsListener = VehicleMetricsRepository.Listener { snap ->
        // Sürüş başlarsa (>5 km/s) düzenleme modu kaydedilip kapanır.
        if (snap.connected && snap.hasDriveMode()) observedDriveMode = snap.driveMode
        // Hareket: gösterge hızı > 0 (okunamıyorsa bilinmiyor; sayaçlar sıfırlanır).
        observedMoving = if (snap.connected && (VehicleMetricsSnapshot.isValid(snap.displaySpeed) || VehicleMetricsSnapshot.isValid(snap.actualSpeed))) snap.preferredSpeed() > 0 else null
        window.decorView.post {
            if (editing && LauncherSafeArea.isMoving(snap, EDIT_EXIT_KMH)) exitEditMode()
        }
    }

    /** Aracın bildirdiği son sürüş modu (veri yoksa null); tema vurgusu bundan [DRIVE_STABLE_MS] sonra uyarlanır. */
    @Volatile private var observedDriveMode: Int? = null
    @Volatile private var observedMoving: Boolean? = null

    /** Park/Sürüş düzeni: etkin düzen, geçiş kararı ve başlıktaki düzenleme başlığı. */
    private var activeMode = LayoutMode.PARK
    private var wifiPanel: WifiPanel? = null
    private val layoutPolicy = DriveLayoutPolicy()
    /** Tercihler her turda SharedPreferences'tan okunmasın diye alanlarda tutulur; onCreate/onStart/ayar değişiminde tazelenir. */
    private var driveAccentOn = true
    private var autoLayoutOn = true
    private lateinit var editHelper: android.widget.TextView
    private val driveModePolicy = DriveModePolicy(DRIVE_STABLE_MS)
    private val driveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val driveTick = object : Runnable {
        override fun run() {
            if (started) { driveModeTick(); layoutTick() }
            driveHandler.postDelayed(this, DRIVE_TICK_MS)
        }
    }

    private lateinit var header: HeaderBar
    private lateinit var editBar: View
    private lateinit var grid: LauncherGrid
    /** Sahne düzeni ([HomeStyle.SAHNE]) seçiliyse orta alan; null ise Serbest ızgara ([grid]) kullanılır. */
    private var sahne: SahneScreen? = null
    /** Orta alanın görünümü (geçiş solmaları için): Sahne ya da ızgara. */
    private val middle: View get() = sahne ?: grid
    /** Düzenleme modu açık mı (Sahne ya da ızgara). */
    private val editing: Boolean get() = sahne?.editMode ?: (::grid.isInitialized && grid.editMode)
    /** Alt çubuk (klima + dock) yalnızca OEM çubuğu olmayan cihazda; OEM'li araçta yok. */
    private var dock: AppDock? = null
    private val oemBar by lazy { LauncherSafeArea.hasOemBar(this) }
    private lateinit var fixedWidgets: List<LauncherWidget>
    /** Tema değişiminde yerinde yenilenen sabit parçalar (bkz. [applyThemeInPlace]). */
    private var climateBar: ClimateBar? = null
    private var ourBar: OurBottomBar? = null
    private var rootLayout: View? = null
    private var mockBar: View? = null

    private var layout: List<PlacedWidget> = emptyList()
    private val gridWidgets = LinkedHashMap<String, SizedWidget>()
    private var started = false
    private var resumed = false
    /** Kurulumda/tema uygulanırken geçerli olan tercih özeti ([HomeScreenPrefs.signature]). */
    private var appliedSignature = ""
    /** Öne gelirken tercih değiştiği için yeniden kurulma bekleniyor; bu kopya düzenleme moduna girmez. */
    private var recreating = false
    private var sheet: android.app.Dialog? = null
    /** `--ez launcher_data_check true` ile açılan Veri kontrolü paneli (yalnızca debug). */
    private var dataCheckExtra: DataCheckSheet? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        // Farklı ekranlarda (ör. Tiggo 8) yerleşim 1920x720 tasarımına yakın bir dp genişliğine getirilir.
        super.attachBaseContext(LauncherScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Çökme döngüsü bekçisi: 60 sn içinde iki çökmede uygulama HOME'u aracın launcher'ına geri verir.
        LauncherCrashGuard.onLauncherStart(this)
        if (LauncherCrashGuard.isDebuggable(this) && intent?.getBooleanExtra(LauncherCrashGuard.EXTRA_DEBUG_CRASH, false) == true) {
            throw RuntimeException("launcher_debug_crash (yalnızca debug)")
        }
        window.decorView.postDelayed({ LauncherCrashGuard.onHealthy(this) }, LauncherCrashGuard.HEALTHY_MS)
        LauncherEdition.init(applicationContext)
        LauncherInfo.logOnce(this)
        LauncherDemo.applyFrom(this, intent)
        // Izgara kurulmadan önce: satır sayısı ve düzen deposu etkin araç profiline göre.
        VehicleProfile.resolve(this)
        LauncherDemo.applyPreview(intent)
        LauncherDemo.applyMoving(intent)
        LauncherProbe.applyFrom(this, intent)
        dataCheckExtra = DataCheckSheet.applyFrom(this, intent)
        com.mapcontrol.launcher.cluster.ClusterCardDebug.applyFrom(this, intent)
        LauncherSafeArea.applyFrom(this, intent)
        LauncherBottomBar.applyFrom(this, intent)
        LauncherTheme.sahne = HomeStylePrefs.get(this) == HomeStyle.SAHNE
        LauncherTheme.applyFrom(resources.configuration, ThemeMode.read(this))
        // Sürüş modu vurgusu: seçenek kapalıysa varsayılan vurgu; demo isteği ilk kurulumda hemen uygulanır.
        refreshDrivePrefs()
        if (!driveAccentOn) LauncherTheme.driveMode = DriveAccent.NORMAL
        else LauncherDemo.applyDriveMode(intent)?.let { LauncherTheme.driveMode = it }
        driveModePolicy.reset(LauncherTheme.driveMode)
        // Tema geçişiyle yeniden kurulunca etkin düzen korunur (statik); otomatik seçenek kapalıysa Park.
        activeMode = if (autoLayoutOn) rememberedMode else LayoutMode.PARK
        LauncherMotion.driveLayout = activeMode == LayoutMode.DRIVE
        layoutPolicy.reset(activeMode)
        LauncherInfo.mode = activeMode
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { consumeAppearanceExtra(intent); consumeEditExtra(intent) }
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        hideSystemBars()

        header = HeaderBar(this, onSettings = ::openSettings, onEditHome = ::enterEditMode, onProfile = ::openProfiles, onWifi = ::toggleWifiPanel)
        // OEM alt çubuğu varsa (Desay) eski klima+dock kartı kaldırılır: ikisi üst üste ekranın üçte birini yer, hücreler sıkışır.
        // "Alt çubuk: Bizimki" seçiliyse OEM çubuğu gizlenir ve yerine OurBottomBar gelir (eski kart de çizilmez).
        ourBar = if (LauncherBottomBar.showsOurBar(this)) OurBottomBar(this) else null
        val ourBar = this.ourBar
        val bottomBar: Pair<AppDock, ClimateBar>? =
            if (oemBar || ourBar != null) null else AppDock(this).let { it to ClimateBar(this, it.view) }
        dock = bottomBar?.first
        climateBar = bottomBar?.second
        fixedWidgets = listOfNotNull(header, bottomBar?.first, bottomBar?.second, ourBar)
        editBar = buildEditBar()
        grid = LauncherGrid(this, this)
        sahne = if (LauncherTheme.sahne) newSahneScreen() else null
        val sahneOn = sahne != null

        val root = LinearLayout(this).apply {
            rootLayout = this
            orientation = LinearLayout.VERTICAL
            // Sahne: taslak ölçüleri (başlık 12dp'den, orta alan 108dp'den başlar); cam gölgeleri taşabilsin diye kırpma yok.
            if (sahneOn) {
                background = SahneGround()
                clipChildren = false
                clipToPadding = false
            } else setBackgroundColor(LauncherTheme.GROUND)
            setPadding(dp(24), dp(if (sahneOn) 12 else 20), dp(24), dp(20))

            addView(FrameLayout(context).apply {
                clipChildren = !sahneOn
                addView(header.view)
                addView(editBar)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.HEADER_HEIGHT_DP)))
            addView(gap(context, if (sahneOn) 24 else 16, vertical = true))
            addView(middle, weightLp(1f, vertical = true))
            if (bottomBar != null) {
                addView(gap(context, 16, vertical = true))
                addView(bottomBar.second.view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.CLIMATE_HEIGHT_DP)))
            }
            if (ourBar != null) {
                addView(gap(context, if (sahneOn) 12 else 16, vertical = true))
                addView(ourBar.view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(OurBottomBar.heightDp(this@HomeLauncherActivity))))
            }

            // OEM alt çubuğu üstüne çizilir; alt çubuk ve ızgara onun üstünde kalsın. Bizimki'nde en altta
            // dar bir boşluk kalır: alttan kaydırma hareketi (OEM çubuğunu geçici çağırır) çubuğumuza takılmaz.
            val baseBottom = dp(if (ourBar != null) OurBottomBar.BOTTOM_GAP_DP else 20)
            setPadding(paddingLeft, paddingTop, paddingRight, maxOf(baseBottom, LauncherSafeArea.oemInsetPx(context)))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = maxOf(baseBottom, LauncherSafeArea.bottomInsetPx(context, insets))
                if (v.paddingBottom != bottom) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
        }
        if (LauncherSafeArea.showsMockBar(this)) {
            // Hata ayıklama: OEM çubuğunun taklidi ayrılan alt bandın içinde, uygulamaların üstünde durur.
            val band = LauncherSafeArea.oemInsetPx(this)
            setContentView(FrameLayout(this).apply {
                addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(OemBarMock.build(this@HomeLauncherActivity).also { mockBar = it }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, band - dp(12), Gravity.BOTTOM
                ).apply { setMargins(dp(24), 0, dp(24), dp(6)) })
            })
        } else {
            setContentView(root)
        }
        loadForMode()
        LauncherStartup.begin(window.decorView)
        ThemeTransition.playIn(this)
        LauncherBottomBar.logResult(this)
        appliedSignature = HomeScreenPrefs.signature(this)
        if (pendingEdit) openPendingEdit()
    }

    /** Çalışırken gelen yeni istek (singleTask): demo sürüş modu değiştirilebilir (`--ei launcher_demo_drive_mode N`). */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (LauncherCrashGuard.isDebuggable(this) && intent?.getBooleanExtra(LauncherCrashGuard.EXTRA_DEBUG_CRASH, false) == true) {
            throw RuntimeException("launcher_debug_crash (yalnızca debug)")
        }
        consumeAppearanceExtra(intent)
        consumeEditExtra(intent)
        LauncherDemo.applyDriveMode(intent)
        LauncherDemo.applyMoving(intent)
        LauncherDemo.applyPreview(intent)
        // Demo: çalışırken araç profili değişirse (satır sayısı değişir) yeniden kurulur.
        if (LauncherDemo.enabled && intent?.hasExtra(VehicleProfile.EXTRA_PROFILE) == true) {
            VehicleProfile.demoOverride = VehicleProfile.parse(intent.getStringExtra(VehicleProfile.EXTRA_PROFILE))
            if (VehicleProfile.compute(this) != VehicleProfile.active) ThemeTransition.recreate(this)
        }
    }

    /** FrostNav Ayarlar'daki "Ana ekran görünümü" bağlantısı: [EXTRA_OPEN_APPEARANCE] ile gelince Görünüm panelini açar. */
    private fun consumeAppearanceExtra(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_APPEARANCE, false) != true) return
        intent.removeExtra(EXTRA_OPEN_APPEARANCE)
        window.decorView.post { if (!isFinishing && !isDestroyed) settings.show(appearanceOnly = true) }
    }

    /**
     * FrostNav "Ana ekran" sekmesindeki "Ana ekranı düzenle": [HomeScreenPrefs.EXTRA_OPEN_EDIT] ile gelince düzenleme moduna girer.
     * İstek [pendingEdit] ile tutulur: öne gelirken tercih değişikliği yüzünden etkinlik yeniden kurulursa yeni kopya girer.
     */
    private fun consumeEditExtra(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(HomeScreenPrefs.EXTRA_OPEN_EDIT, false) != true) return
        intent.removeExtra(HomeScreenPrefs.EXTRA_OPEN_EDIT)
        pendingEdit = true
        openPendingEdit()
    }

    private fun openPendingEdit() {
        window.decorView.post {
            if (isFinishing || isDestroyed || !::grid.isInitialized || recreating || !pendingEdit) return@post
            pendingEdit = false
            enterEditMode()
        }
    }

    /**
     * Tema değişimini etkinliği yeniden yaratmadan uygular ([ThemeTransition.retheme] üstteki anlık görüntü örtüsünün
     * altında çağırır). Jetonlar güncellenir; [Themeable] olanlar yerinde yenilenir, olmayan widget'lar tek tek yeniden
     * kurulur (çalışan işleri durdurulup yenisi başlatılır). Açık sayfa kapanır, düzenleme modundan çıkılır.
     */
    internal fun applyThemeInPlace() {
        if (isFinishing || isDestroyed || !::grid.isInitialized) return
        sheet?.dismiss()
        sheet = null
        wifiPanel?.dismiss()
        exitEditMode()
        refreshDrivePrefs()
        if (!driveAccentOn) LauncherTheme.driveMode = DriveAccent.NORMAL
        LauncherTheme.applyFrom(resources.configuration, ThemeMode.read(this))
        syncAutoNight()
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        rootLayout?.setBackgroundColor(LauncherTheme.GROUND)

        // Sabit parçalar: başlık yerinde, diğerleri tek tek yeniden kurulur.
        header.applyTheme()
        val newEditBar = buildEditBar().also { it.visibility = editBar.visibility }
        swapView(editBar, newEditBar)
        editBar = newEditBar
        refreshEditLayoutUi()
        val oldFixed = ArrayList<LauncherWidget>()
        val newFixed = ArrayList<LauncherWidget>()
        dock?.let { oldDock ->
            val oldClimate = climateBar
            val newDock = AppDock(this)
            val newClimate = ClimateBar(this, newDock.view)
            swapView(oldDock.view, newDock.view)
            oldClimate?.let { swapView(it.view, newClimate.view) }
            oldFixed += oldDock; oldClimate?.let { oldFixed += it }
            newFixed += newDock; newFixed += newClimate
            dock = newDock; climateBar = newClimate
        }
        // Bizim alt çubuk (ClimateControl ve DockSurface dahil) yerinde yenilenir; yeniden kurulmaz. Sahne çubuğu
        // cam ve düğme renklerini kurulumda aldığı için yenisiyle değiştirilir.
        if (sahne != null) ourBar?.let { old ->
            val fresh = OurBottomBar(this)
            swapView(old.view, fresh.view)
            if (started) old.onHidden()
            old.release()
            ourBar = fresh
            if (started) showWidget(fresh)
        } else ourBar?.applyTheme()
        mockBar?.let { old -> OemBarMock.build(this).also { swapView(old, it); mockBar = it } }
        fixedWidgets = listOfNotNull(header, dock, climateBar, ourBar)
        oldFixed.forEach { if (started) it.onHidden(); it.release() }
        newFixed.forEach { showWidget(it) }

        // Sahne: orta alan yeni jetonlarla baştan kurulur (örtünün altında); düzen ve mod aynı kalır.
        sahne?.let { old ->
            rootLayout?.background = SahneGround()
            val fresh = newSahneScreen()
            if (started) old.onHidden()
            old.release()
            swapView(old, fresh)
            sahne = fresh
            fresh.setMode(activeMode)
            if (started) fresh.onVisible()
            window.decorView.invalidate()
            appliedSignature = HomeScreenPrefs.signature(this)
            return
        }

        // Izgara: Themeable widget yerinde, diğerleri yeniden kurulur; düzen aynı kalır.
        val fresh = LinkedHashMap<String, View>()
        var themed = 0
        layout.forEach { placed ->
            val old = gridWidgets[placed.id] ?: return@forEach
            if (old is Themeable) { old.applyTheme(); themed++; return@forEach }
            if (started) old.onHidden()
            old.release()
            val created = WidgetFactory.create(this, placed)
            created.applySpan(placed.span)
            gridWidgets[placed.id] = created
            showWidget(created)
            fresh[placed.id] = created.view
        }
        grid.swapContents(fresh)
        grid.applyTheme()
        android.util.Log.i("ThemeTiming", "yerinde: ızgara $themed widget yerinde, ${fresh.size} yeniden kuruldu")
        window.decorView.invalidate()
        appliedSignature = HomeScreenPrefs.signature(this)
    }

    /** Tema geçişi örtüsü açıkken yeni kurulan widget'ların onVisible'ı bekletilir (yoklama/metrik patlamaları ilk karede çıkmasın). */
    private var themeDefer = false
    private val deferredVisible = ArrayList<LauncherWidget>()

    internal fun beginThemeDefer() { themeDefer = true }

    /** Örtü söndükten sonra: bekleyen ve hâlâ yaşayan widget'ların onVisible'ı çalışır ([ThemeTransition.retheme]). */
    internal fun endThemeDefer() {
        themeDefer = false
        if (deferredVisible.isEmpty()) return
        val pending = ArrayList(deferredVisible)
        deferredVisible.clear()
        if (!started || isFinishing || isDestroyed) return
        pending.forEach { w -> if (w in fixedWidgets || gridWidgets.values.any { it === w }) w.onVisible() }
    }

    private fun showWidget(w: LauncherWidget) {
        if (!started) return
        if (themeDefer) deferredVisible += w else w.onVisible()
    }

    /** Sahne orta alanı; seçici açılınca [sheet] olur, düzenleme yalnızca araç dururken. */
    private fun newSahneScreen(): SahneScreen = SahneScreen(this, onSheet = { sheet = it }, canEdit = { !editLocked() })

    /** [old]'u ana kadar bulunduğu yerde (sıra ve ölçüleriyle) [new] ile değiştirir. */
    private fun swapView(old: View, new: View) {
        val parent = old.parent as? ViewGroup ?: return
        val i = parent.indexOfChild(old)
        val lp = old.layoutParams
        parent.removeViewAt(i)
        parent.addView(new, i, lp)
    }

    /**
     * Sürüş modu değişimini uygular: gözlenen mod ~2 sn kararlı kalınca vurgu güncellenir ve etkinlik
     * ThemeTransition ile yeniden kurulur (renkler kurulumda okunur). Düzenleme/sayfa açıkken ertelenir.
     */
    private fun driveModeTick() {
        if (!driveAccentOn) return
        val observed = if (LauncherDemo.enabled) LauncherDemo.driveMode else observedDriveMode
        val to = driveModePolicy.update(System.currentTimeMillis(), observed, !editing && sheet == null)
        if (to != null && to != LauncherTheme.driveMode) {
            LauncherTheme.driveMode = to
            ThemeTransition.retheme(this)
        }
    }

    /**
     * Park/Sürüş geçişi: hız ve vites sinyallerinden [DriveLayoutPolicy] karar verir; düzenleme ya da sayfa açıkken ertelenir.
     * Seçenek kapalıysa sürüş düzeni gösteriliyorsa Park'a dönülür. Demo `launcher_demo_moving false` beklemeden Park'a döner.
     */
    private fun layoutTick() {
        val idle = !editing && sheet == null
        if (!autoLayoutOn) {
            if (activeMode == LayoutMode.DRIVE && idle) { layoutPolicy.reset(LayoutMode.PARK); switchMode(LayoutMode.PARK, true, automatic = true) }
            return
        }
        val moving = if (LauncherDemo.enabled) LauncherDemo.moving else observedMoving
        // Vites/el freni yalnızca Sürüş'te durulduğunda okunur; Park'ta ve giderken IPC yapılmaz.
        val parked = if (layoutPolicy.wantsParkSignal(moving)) DriveSignals.parked() else { DriveSignals.invalidate(); false }
        val to = layoutPolicy.update(
            System.currentTimeMillis(), moving, parked, idle,
            immediatePark = LauncherDemo.enabled && moving == false,
        )
        if (to != null) switchMode(to, true, automatic = true)
    }

    /** Düzeni değiştirir: ızgara kısa süre kararır, yeni düzen kurulur, geri açılır (etkinlik yeniden yaratılmaz). */
    private fun switchMode(to: LayoutMode, animate: Boolean, automatic: Boolean = false) {
        if (to == activeMode) return
        val notice = layoutNoticeFor(activeMode, to, automatic, started)
        activeMode = to
        LauncherMotion.driveLayout = to == LayoutMode.DRIVE
        rememberedMode = to
        LauncherInfo.mode = to
        notice?.let { showLayoutNotice(it) }
        val apply = { loadForMode() }
        if (!animate || !started) { apply(); return }
        val m = middle
        m.animate().cancel()
        m.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            apply()
            m.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }.start()
    }

    /** Otomatik düzen geçişinde kısa, sessiz bilgi: başlıkta tarihin yerinde çapraz geçişle gösterilir. */
    private fun showLayoutNotice(mode: LayoutMode) {
        header.showNotice(if (mode == LayoutMode.DRIVE) R.string.launcher_layout_drive_edit else R.string.launcher_layout_park_edit,
            NOTICE_SHOW_MS, NOTICE_FADE_MS)
    }

    /** Düzenleme çubuğundaki Park | Sürüş seçici: düzenlenen/etkin düzeni elle değiştirir. */
    private fun selectEditLayout(to: LayoutMode) {
        if (!editing || to == activeMode) return
        val m = middle
        m.animate().cancel()
        m.animate().alpha(0f).setDuration(EDIT_SWAP_OUT_MS).withEndAction {
            switchMode(to, animate = false)
            refreshEditLayoutUi()
            m.animate().alpha(1f).setDuration(EDIT_SWAP_IN_MS).start()
        }.start()
        // Seçici hemen yanıt verir; ızgara kısa geçişle ardından değişir.
    }

    private fun refreshEditLayoutUi() {
        editHelper.setText(when {
            sahne != null -> if (activeMode == LayoutMode.DRIVE) R.string.launcher_sahne_helper_drive else R.string.launcher_sahne_helper_park
            activeMode == LayoutMode.DRIVE -> R.string.launcher_edit_helper_drive
            else -> R.string.launcher_edit_helper_park
        })
        editSegments.forEach { (mode, seg) ->
            val on = mode == activeMode
            seg.view.background = if (on) rounded(this, DriveAccent.buttonFill(LauncherTheme.ACCENT), 999f) else null
            val c = if (on) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT_SECONDARY
            seg.label.setTextColor(c)
            seg.icon.imageTintList = android.content.res.ColorStateList.valueOf(c)
            seg.view.isSelected = on
        }
    }

    private class EditSegment(val view: View, val label: android.widget.TextView, val icon: android.widget.ImageView)

    private val editSegments = LinkedHashMap<LayoutMode, EditSegment>()

    private fun layoutSegmented(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(context, LauncherTheme.CARD, 999f, LauncherTheme.CARD_BORDER)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(LauncherTheme.TOUCH_MIN_DP))
        editSegments.clear()
        listOf(
            Triple(LayoutMode.PARK, R.string.launcher_layout_park, R.drawable.ic_mdi_car),
            Triple(LayoutMode.DRIVE, R.string.launcher_layout_drive, R.drawable.ic_mdi_steering),
        ).forEach { (mode, label, iconRes) ->
            val ic = icon(context, iconRes, 32, LauncherTheme.TEXT_SECONDARY)
            val tv = text(context, LauncherTheme.BODY_SP + 4f, LauncherTheme.TEXT_SECONDARY, medium = true).apply { setText(label) }
            val seg = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(26), 0, dp(30), 0)
                contentDescription = getString(if (mode == LayoutMode.DRIVE) R.string.launcher_layout_drive_edit else R.string.launcher_layout_park_edit)
                isClickable = true
                setOnClickListener { selectEditLayout(mode) }
                pressFeedback()
                addView(ic)
                addView(gap(context, 10))
                addView(tv)
            }
            editSegments[mode] = EditSegment(seg, tv, ic)
            addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** Sürüş vurgusu ve otomatik düzen seçeneklerini bir kez okur (ayarlar bunları değiştirince çağrılır). */
    internal fun refreshDrivePrefs() {
        driveAccentOn = DriveAccent.enabled(this)
        autoLayoutOn = DriveLayoutPrefs.get(this)
    }

    override fun onStart() {
        super.onStart()
        started = true
        deferredVisible.clear() // aşağıda zaten hepsine onVisible gider
        refreshDrivePrefs()
        driveHandler.removeCallbacks(driveTick)
        driveHandler.postDelayed(driveTick, DRIVE_TICK_MS)
        metrics.addListener(metricsListener)
        fixedWidgets.forEach { it.onVisible() }
        gridWidgets.values.forEach { it.onVisible() }
        sahne?.onVisible()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        resumed = true
        syncAutoNight()
        // FrostNav "Ana ekran" sekmesinde tema/alt çubuk/düzen tercihleri değiştiyse (renkler ve çubuklar kurulumda okunur) yeniden kur.
        val sig = HomeScreenPrefs.signature(this)
        if (appliedSignature.isNotEmpty() && sig != appliedSignature) {
            appliedSignature = sig
            recreating = true
            if (editing) pendingEdit = true
            ThemeTransition.recreate(this)
        }
    }

    /**
     * Otomatik temada araç lambası/ışık sensörü izlenir; düzenlerken ya da bir sayfa açıkken geçiş beklenir.
     * Yalnızca AUTO iken dinleyici ve döngü kurulur; tema modu değişince ([applyThemeInPlace]) yeniden eşitlenir.
     */
    private fun syncAutoNight() {
        if (!resumed) return
        LauncherTheme.startAutoNight(this, { !editing && sheet == null }) { ThemeTransition.retheme(this) }
    }

    override fun onPause() {
        resumed = false
        LauncherTheme.stopAutoNight()
        super.onPause()
    }

    override fun onStop() {
        started = false
        themeDefer = false
        deferredVisible.clear()
        driveHandler.removeCallbacks(driveTick)
        sheet?.dismiss()
        sheet = null
        wifiPanel?.dismiss()
        exitEditMode()
        metrics.removeListener(metricsListener)
        fixedWidgets.forEach { it.onHidden() }
        gridWidgets.values.forEach { it.onHidden() }
        sahne?.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        if (::fixedWidgets.isInitialized) fixedWidgets.forEach { it.release() }
        gridWidgets.values.forEach { it.release() }
        sahne?.release()
        // Ortak örnek: release() çağrılmaz (güneş çatısı vb. dinleyicileri sönerdi), yalnızca kendi dinleyicimiz çıkar.
        metrics.removeListener(metricsListener)
        settings.release()
        dataCheckExtra?.release()
        dataCheckExtra = null
        super.onDestroy()
    }

    /** İzin sonuçları: depolama verilince açık fotoğraf seçici yenilenir. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return
        when (requestCode) {
            WifiPanel.REQ_LOCATION -> wifiPanel?.refresh()
            PhotoTile.REQ_STORAGE -> PhotoTile.pickerRefresh?.invoke()
        }
    }

    /** Ana ekrandayken geri tuşu yalnızca düzenleme modundan çıkar. */
    @Deprecated("Activity.onBackPressed, API 33 öncesi araçta geçerli yol")
    override fun onBackPressed() {
        if (editing) exitEditMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- Izgara ---

    override fun onLayoutChanged(layout: List<PlacedWidget>) = applyLayout(layout)

    override fun onLongPressEmpty() = enterEditMode()

    /**
     * Yeni düzeni uygular: kaldırılan widget'lar durdurulup bırakılır, yenileri üretilir,
     * hepsine yeni boyutu bildirilir. Mevcut widget'lar yeniden yaratılmaz.
     */
    private fun loadForMode() {
        sahne?.let { it.setMode(activeMode); return }
        applyLayout(store.load(), persist = false)
    }

    private fun applyLayout(newLayout: List<PlacedWidget>, persist: Boolean = true) {
        val ids = newLayout.map { it.id }.toSet()
        gridWidgets.keys.filter { it !in ids }.forEach { id ->
            gridWidgets.remove(id)?.let { w ->
                if (started) w.onHidden()
                w.release()
            }
        }
        newLayout.forEach { placed ->
            val widget = gridWidgets[placed.id] ?: WidgetFactory.create(this, placed).also { created ->
                gridWidgets[placed.id] = created
                created.applySpan(placed.span)
                if (started) created.onVisible()
            }
            widget.applySpan(placed.span)
        }
        layout = newLayout
        grid.setLayout(newLayout, gridWidgets.mapValues { it.value.view })
        if (persist) store.save(newLayout)
    }

    // --- Düzenleme modu ---

    /** Düzenleme yalnızca araç dururken açılır; sürüş sırasında ekranla uğraşılmasın. */
    private fun enterEditMode() {
        if (editing) return
        if (editLocked()) return
        sahne?.let { it.editMode = true } ?: run { grid.editMode = true }
        refreshEditLayoutUi()
        dock?.setEditMode(true)
        header.view.visibility = View.INVISIBLE
        editBar.visibility = View.VISIBLE
    }

    /** Araç hareket halindeyse (veya hız henüz okunmadıysa) kısa bir mesaj gösterip true döner. */
    private fun editLocked(): Boolean {
        if (!LauncherSafeArea.isMoving(metrics.currentSnapshot())) return false
        Toast.makeText(this, R.string.launcher_edit_locked_driving, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun exitEditMode() {
        if (!editing) return
        sheet?.dismiss()
        sheet = null
        sahne?.let { it.editMode = false } ?: run { grid.editMode = false }
        dock?.setEditMode(false)
        editBar.visibility = View.GONE
        header.view.visibility = View.VISIBLE
        // Önizleme/düzenleme bitince otomatik düzene dönülür (Sürüş'ü Park'tayken düzenlediyse).
        val auto = if (autoLayoutOn) layoutPolicy.mode else LayoutMode.PARK
        if (activeMode != auto) switchMode(auto, animate = false)
    }

    private fun buildEditBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE

        addView(layoutSegmented())
        addView(gap(context, 20))
        addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 2).apply {
            editHelper = this
            gravity = Gravity.CENTER_VERTICAL
            setText(R.string.launcher_edit_helper_park)
        }, weightLp(1f))
        addView(gap(context, 16))
        // Sahne'de yuvalar sabit: ekleme yuvanın kendi seçicisinden yapılır.
        if (!LauncherTheme.sahne) {
            addView(barButton(R.drawable.ic_mdi_plus, R.string.launcher_edit_add, LauncherTheme.CARD) { showAddDialog() })
            addView(gap(context, 16))
        }
        addView(barButton(R.drawable.ic_mdi_theme_light_dark, R.string.launcher_appearance, LauncherTheme.CARD) { settings.show(appearanceOnly = true) })
        addView(gap(context, 16))
        addView(barButton(R.drawable.ic_mdi_restart, R.string.launcher_edit_reset, LauncherTheme.CARD) { confirmReset() })
        addView(gap(context, 16))
        addView(barButton(R.drawable.ic_mdi_check, R.string.launcher_edit_done, DriveAccent.buttonFill(LauncherTheme.ACCENT), onFill = true) { exitEditMode() })
    }

    private fun barButton(iconRes: Int, labelRes: Int, background: Int, onFill: Boolean = false, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        this.background = rounded(context, background)
        setPadding(dp(24), 0, dp(24), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(LauncherTheme.TOUCH_MIN_DP))
        // Birincil düğme: renkli dolgu üzerinde beyaz ikon ve yazı (karşıtlık >= 4,5:1, bkz. DriveAccent.buttonFill).
        addView(if (onFill) icon(context, iconRes, 28, LauncherTheme.ON_ACCENT) else icon(context, iconRes, 28))
        addView(gap(context, 12))
        addView(text(context, LauncherTheme.BODY_SP, if (onFill) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply { setText(labelRes) })
        contentDescription = getString(labelRes)
        isClickable = true
        setOnClickListener { onClick() }
        pressFeedback()
    }

    /**
     * "Widget ekle" paneli: beş sütunlu kutucuklar, her birinde ikon, ad ve boyutlar.
     * Ekranda zaten olan türler (APP hariç) soluk ve "Ekranda" der; yer kalmayanlar "Yer yok" der; dokunmak hiçbir şey eklemez.
     */
    private fun showAddDialog() {
        if (editLocked() || isFinishing || isDestroyed) return
        sheet?.dismiss()
        sheet = showLauncherSheet(this, R.string.launcher_edit_add, ADD_SHEET_WIDTH_DP) { d ->
            // Türler tek ekrana sığmayabilir: ızgara sabit yükseklikte kaydırılır, alt satır hafifçe görünür.
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addableWidgetTypes().chunked(ADD_COLUMNS).forEachIndexed { r, rowTypes ->
                if (r > 0) grid.addView(gap(context, 12, vertical = true))
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (c in 0 until ADD_COLUMNS) {
                        if (c > 0) addView(gap(context, 12))
                        val type = rowTypes.getOrNull(c)
                        val lp = LinearLayout.LayoutParams(0, dp(ADD_TILE_HEIGHT_DP), 1f)
                        if (type == null) addView(View(context), lp) else addView(addTile(type) { d.dismiss() }, lp)
                    }
                }, sheetFullWidth())
            }
            addView(android.widget.ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(grid, sheetFullWidth())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ADD_LIST_HEIGHT_DP)))
        }
    }

    private fun addTile(type: WidgetType, dismiss: () -> Unit): View {
        val onScreen = !isAddable(type, layout)
        val spot = if (onScreen) null else spotFor(type)
        val fits = spot != null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, LauncherTheme.CONTROL, 16f)
            val p = dp(16)
            setPadding(dp(20), p, dp(20), p)
            addView(icon(context, type.icon, 32, if (fits) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY))
            addView(gap(context, 8, vertical = true))
            addView(text(context, LauncherTheme.BODY_SP, if (fits) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY, medium = true).apply {
                // Uzun adlar ("Soğutma · Sürücü") kesilmesin: tek satırda küçülür.
                setAutoSizeTextTypeUniformWithConfiguration(CATALOG_MIN_SP, LauncherTheme.BODY_SP.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setText(type.label)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(gap(context, 4, vertical = true))
            addView(text(context, LauncherTheme.LABEL_SP, LauncherTheme.TEXT_SECONDARY).apply {
                text = when {
                    fits -> type.sizes.joinToString("  ·  ")
                    onScreen -> getString(R.string.launcher_edit_on_screen)
                    else -> getString(R.string.launcher_edit_no_room)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            alpha = if (fits) 1f else NO_ROOM_ALPHA
            contentDescription = getString(type.label)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (spot != null) {
                    dismiss()
                    applyLayout(layout + spot)
                    grid.select(spot.id)
                }
            }
            pressFeedback()
        }
    }

    /** Türün ızgarada sığdığı ilk yer; önce varsayılan (ilk) boyut, sığmazsa sıradakiler. */
    private fun spotFor(type: WidgetType): PlacedWidget? {
        val base = PlacedWidget("${type.key}_${System.currentTimeMillis()}", type, 0, 0, type.sizes.first())
        return type.sizes.asSequence().mapNotNull { LayoutStore.findSpot(layout, base.copy(span = it)) }.firstOrNull()
    }

    private fun confirmReset() {
        if (isFinishing || isDestroyed) return
        sheet?.dismiss()
        sheet = showLauncherSheet(this, R.string.launcher_edit_reset, RESET_SHEET_WIDTH_DP) { d ->
            addView(text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY, maxLines = 3).apply {
                setText(if (activeMode == LayoutMode.DRIVE) R.string.launcher_edit_reset_confirm_drive else R.string.launcher_edit_reset_confirm_park)
            }, sheetFullWidth())
            addView(gap(context, 32, vertical = true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(sheetButton(R.string.launcher_nav_dialog_cancel, primary = false) { d.dismiss() }, weightLp(1f))
                addView(gap(context, 12))
                addView(sheetButton(R.string.launcher_edit_reset, primary = true) {
                    d.dismiss()
                    sahne?.let { it.resetToDefaults(); return@sheetButton }
                    store.reset()
                    applyLayout(store.defaults())
                }, weightLp(1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.TOUCH_MIN_DP)))
        }
    }

    private fun sheetButton(labelRes: Int, primary: Boolean, onClick: () -> Unit): View =
        text(this, LauncherTheme.BODY_SP, if (primary) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT, medium = true).apply {
            setText(labelRes)
            gravity = Gravity.CENTER
            background = rounded(context, if (primary) LauncherTheme.ACCENT else LauncherTheme.CONTROL, 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
        }

    // --- Diğer ---

    /** Wi-Fi simgesine dokunma: simgenin altında açılır panel (ikinci dokunuş kapatır). */
    private fun toggleWifiPanel(anchor: View) {
        if (editing) return
        val panel = wifiPanel ?: WifiPanel(
            this, LauncherEdition.wifiBackend(this),
            driving = { activeMode == LayoutMode.DRIVE || LauncherSafeArea.isMoving(metrics.currentSnapshot()) },
            onSettings = { LauncherEdition.openWifi(this) },
            features = LauncherEdition.frostNav(this),
            onWebState = { header.setWebRunning(it) },
        ).also { wifiPanel = it }
        panel.show(anchor)
    }

    private fun openProfiles() {
        // Düzenleme sırasında profil değişmez (başlık zaten gizli); sürüşte serbest, tek dokunuş.
        if (editing) return
        showProfileSheet(this, isMoving = { LauncherSafeArea.isMoving(metrics.currentSnapshot()) }) { ThemeTransition.recreate(this) }
    }

    private fun openSettings() {
        // Tam sürümde doğrudan MapControl ayarları; salt-okur test sürümünde launcher paneli.
        if (LauncherEdition.HAS_SETTINGS) LauncherEdition.openSettings(this) else settings.show()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars())
            if (LauncherBottomBar.hidesOemBar(this)) it.hide(WindowInsets.Type.navigationBars())
            // Gündüz görünümünde gezinme çubuğu simgeleri açık zeminde görünür kalsın.
            it.setSystemBarsAppearance(
                if (LauncherTheme.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } ?: run {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                if (LauncherTheme.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private companion object {
        const val EDIT_EXIT_KMH = 5
        /** FrostNav Ayarlar'dan gelir (bkz. SettingsTabBuilder): açılışta Görünüm panelini gösterir. */
        const val EXTRA_OPEN_APPEARANCE = "launcher_open_appearance"
        const val FADE_OUT_MS = 120L
        const val FADE_IN_MS = 200L
        /** Tema geçişiyle yeniden kurulmada etkin düzeni korur. */
        @Volatile var rememberedMode = LayoutMode.PARK
        private const val NOTICE_SHOW_MS = 1800L
        private const val NOTICE_FADE_MS = 250L
        private const val EDIT_SWAP_OUT_MS = 80L
        private const val EDIT_SWAP_IN_MS = 120L
        /** FrostNav'dan gelen "düzenleme modunda aç" isteği; yeniden kurulma arasında kaybolmasın diye statik. */
        @Volatile var pendingEdit = false
        const val DRIVE_STABLE_MS = 2_000L
        const val DRIVE_TICK_MS = 500L
        const val ADD_COLUMNS = 5
        const val CATALOG_MIN_SP = 16
        const val ADD_SHEET_WIDTH_DP = 1280
        const val ADD_TILE_HEIGHT_DP = 136
        const val ADD_LIST_HEIGHT_DP = 400
        const val RESET_SHEET_WIDTH_DP = 640
        const val NO_ROOM_ALPHA = 0.45f
    }
}
