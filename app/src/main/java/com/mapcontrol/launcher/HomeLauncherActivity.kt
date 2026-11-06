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
 * Yerleşim (yukarıdan aşağı): üst şerit 72dp (düzenleme modunda düzenleme çubuğu) · Sahne orta alanı ([SahneScreen]:
 * iki panel, ortada araç, altta şerit) · launcher'ın kendi alt çubuğu ([OurBottomBar]; OEM çubuğu olan araçta "Orijinal"
 * seçiliyken yok). Tüm widget'lar yalnızca ekran görünürken çalışır.
 */
class HomeLauncherActivity : Activity() {

    private val metrics by lazy { VehicleMetricsRepository.getInstance(applicationContext) }
    private val settings by lazy { LauncherSettings(this, onEditHome = ::enterEditMode) }
    private val metricsListener = VehicleMetricsRepository.Listener { snap ->
        // Sürüş başlarsa (>5 km/s) düzenleme modu kaydedilip kapanır.
        if (snap.connected && snap.hasDriveMode()) observedDriveMode = snap.driveMode
        // Hareket: gösterge hızı > 0 (okunamıyorsa bilinmiyor; sayaçlar sıfırlanır).
        observedPowerOn = WelcomeGate.powerOn(snap)
        observedMoving = if (snap.connected && (VehicleMetricsSnapshot.isValid(snap.displaySpeed) || VehicleMetricsSnapshot.isValid(snap.actualSpeed))) snap.preferredSpeed() > 0 else null
        // Vites P mi (ReadOnly 26: P=1); okunamıyorsa bilinmiyor.
        observedInPark = if (snap.connected && VehicleMetricsSnapshot.isValid(snap.gearSignal)) snap.gearSignal == 1 else null
        window.decorView.post {
            if (editing && LauncherSafeArea.isMoving(snap, EDIT_EXIT_KMH)) exitEditMode()
        }
    }

    /** Aracın bildirdiği son sürüş modu (veri yoksa null); tema vurgusu bundan [DRIVE_STABLE_MS] sonra uyarlanır. */
    @Volatile private var observedDriveMode: Int? = null
    @Volatile private var observedMoving: Boolean? = null
    @Volatile private var observedInPark: Boolean? = null
    /** Güç modu açık mı (kontak; bilinmiyorsa null): karşılama (§7.4) bundan karar verir. */
    @Volatile private var observedPowerOn: Boolean? = null
    /** Sahne karşılaması; etkinlik başına bir kez kurulur. */
    private var welcome: SahneWelcome? = null
    /** Soğuk açılışta karşılama için ekran siyah tutuldu; bu ana kadar güç sinyali gelmezse kısa solmayla açılır. */
    private var welcomeArmUntil = 0L

    /** Park/Sürüş düzeni: etkin düzen, geçiş kararı ve başlıktaki düzenleme başlığı. */
    private var activeMode = LayoutMode.PARK
    private var wifiPanel: WifiPanel? = null
    private var bluetoothPanel: BluetoothPanel? = null
    private val layoutPolicy = DriveLayoutPolicy()
    /** Tercihler her turda SharedPreferences'tan okunmasın diye alanlarda tutulur; onCreate/onStart/ayar değişiminde tazelenir. */
    private var driveAccentOn = true
    /** "Spor modu (deneysel)" seçeneği; kapalıyken Spor orijinal temada kalır. */
    private var sportModeOn = false
    private var autoLayoutOn = true
    private lateinit var editHelper: android.widget.TextView
    private val driveModePolicy = DriveModePolicy(DRIVE_STABLE_MS)
    /** Spor modu: sürüş modu Spor ~1 sn kararlı kalınca orta alan ve çubuklar kronograf diline geçer. */
    private val sportGate = SportGate()
    private var sport: SportController? = null
    private val driveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val driveTick = object : Runnable {
        override fun run() {
            if (started) { driveModeTick(); sportTick(); layoutTick(); welcomeTick() }
            driveHandler.postDelayed(this, DRIVE_TICK_MS)
        }
    }

    private lateinit var header: HeaderBar
    private lateinit var editBar: View
    /** Orta alan: Sahne (iki panel, ortada araç, altta şerit). */
    private lateinit var sahne: SahneScreen
    /** Düzenleme modu açık mı. */
    private val editing: Boolean get() = ::sahne.isInitialized && sahne.editMode
    private val oemBar by lazy { LauncherSafeArea.hasOemBar(this) }
    private lateinit var fixedWidgets: List<LauncherWidget>
    /** Tema değişiminde yeniden kurulan sabit parçalar (bkz. [applyThemeInPlace]). */
    private var ourBar: OurBottomBar? = null
    /** Gelen/aktif arama şeridi (üst orta, tüm içeriğin üstünde); sabit widget'larla aynı ömür. */
    private lateinit var callBanner: CallBanner
    private var rootLayout: View? = null
    private var mockBar: View? = null

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
        RpmRateLog.applyFrom(this, intent)
        // Orta alan kurulmadan önce: düzen deposu ve alt çubuk biçimi etkin araç profiline göre.
        VehicleProfile.resolve(this)
        LauncherDemo.applyPreview(intent)
        LauncherDemo.applyMoving(intent)
        LauncherProbe.applyFrom(this, intent)
        PhoneDataProbe.applyFrom(this, intent)
        dataCheckExtra = DataCheckSheet.applyFrom(this, intent)
        com.mapcontrol.launcher.cluster.ClusterCardDebug.applyFrom(this, intent)
        LauncherSafeArea.applyFrom(this, intent)
        LauncherBottomBar.applyFrom(this, intent)
        LauncherTheme.applyFrom(resources.configuration, ThemeMode.read(this))
        // Gösterge haritası (deneysel): debug ekstraları; açıksa Otomatik çerçeve yeni gündüz/gece durumuna geçer.
        com.mapcontrol.launcher.cluster.ClusterMap.applyFrom(this, intent)
        com.mapcontrol.launcher.cluster.ClusterMap.refreshStyle(this)
        // Sürüş modu vurgusu: seçenek kapalıysa varsayılan vurgu; demo isteği ilk kurulumda hemen uygulanır.
        refreshDrivePrefs()
        if (!driveAccentOn) LauncherTheme.driveMode = DriveAccent.NORMAL
        else LauncherDemo.applyDriveMode(intent)?.let { LauncherTheme.driveMode = it }
        driveModePolicy.reset(LauncherTheme.driveMode)
        // Spor ambiyansı: seçenek açıksa ya da geri yazma bekliyorsa sürüş modunu izler (açılışta bekleyen renk geri yazılır).
        SportAmbient.sync(this)
        // Tema geçişiyle yeniden kurulunca etkin düzen korunur (statik); otomatik seçenek kapalıysa Park.
        activeMode = if (autoLayoutOn) rememberedMode else LayoutMode.PARK
        LauncherMotion.driveLayout = activeMode == LayoutMode.DRIVE
        layoutPolicy.reset(activeMode)
        LauncherInfo.mode = activeMode
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { consumeAppearanceExtra(intent); consumeEditExtra(intent) }
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        hideSystemBars()

        header = HeaderBar(this, onSettings = ::openSettings, onEditHome = ::enterEditMode, onProfile = ::openProfiles, onWifi = ::toggleWifiPanel, onBluetooth = ::toggleBluetoothPanel)
        // "Alt çubuk: Bizimki" seçiliyse OEM çubuğu gizlenir ve yerine OurBottomBar gelir; OEM çubuğu yokken "Orijinal" de
        // bizim çubuğa düşer. "Gizli"de çubuk yok.
        ourBar = if (BottomBarMode.drawsOurBar(LauncherBottomBar.mode(this), oemBar)) OurBottomBar(this, onPhoneTap = ::onPhoneButton) else null
        val ourBar = this.ourBar
        callBanner = CallBanner(this, onIncoming = ::onIncomingCall)
        fixedWidgets = listOfNotNull(header, ourBar, callBanner)
        editBar = buildEditBar()
        sahne = newSahneScreen()

        val root = LinearLayout(this).apply {
            rootLayout = this
            orientation = LinearLayout.VERTICAL
            // Taslak ölçüleri (başlık 12dp'den, orta alan 108dp'den başlar); cam gölgeleri taşabilsin diye kırpma yok.
            background = SahneGround()
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(12), dp(24), dp(20))

            addView(FrameLayout(context).apply {
                clipChildren = false
                addView(header.view)
                addView(editBar)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(LauncherTheme.HEADER_HEIGHT_DP)))
            // Sahne ritmi: başlık / orta alan / şerit / alt çubuk arası hep 16dp.
            addView(gap(context, 16, vertical = true))
            addView(sahne, weightLp(1f, vertical = true))
            if (ourBar != null) {
                addView(gap(context, 16, vertical = true))
                addView(ourBar.view, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(OurBottomBar.heightDp(this@HomeLauncherActivity))))
            }

            // OEM alt çubuğu üstüne çizilir; alt çubuk ve orta alan onun üstünde kalsın. Bizimki'nde en altta
            // dar bir boşluk kalır: alttan kaydırma hareketi (OEM çubuğunu geçici çağırır) çubuğumuza takılmaz.
            val baseBottom = dp(if (ourBar != null) OurBottomBar.BOTTOM_GAP_DP else 20)
            setPadding(paddingLeft, paddingTop, paddingRight, maxOf(baseBottom, LauncherSafeArea.oemInsetPx(context)))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = maxOf(baseBottom, LauncherSafeArea.bottomInsetPx(context, insets))
                if (v.paddingBottom != bottom) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
                insets
            }
        }
        // İçerik her zaman bir FrameLayout: kök düzen, (hata ayıklamada) OEM çubuğu taklidi ve en üstte arama şeridi.
        // Kök düzene bağlı her şey (rootLayout, tema geçişi, karşılama, iç boşluk dinleyicisi) `root`'u kullanır, içerik görünümünü değil.
        setContentView(FrameLayout(this).apply {
            clipChildren = false
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (LauncherSafeArea.showsMockBar(this@HomeLauncherActivity)) {
                // Hata ayıklama: OEM çubuğunun taklidi ayrılan alt bandın içinde, uygulamaların üstünde durur.
                val band = LauncherSafeArea.oemInsetPx(this@HomeLauncherActivity)
                addView(OemBarMock.build(this@HomeLauncherActivity).also { mockBar = it }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, minOf(band, dp(OemBarMock.BODY_DP)), Gravity.BOTTOM
                ).apply { setMargins(dp(OemBarMock.SIDE_DP), 0, dp(OemBarMock.SIDE_DP), dp(OemBarMock.BOTTOM_GAP_DP)) })
            }
            addView(callBanner.view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(CALL_BANNER_TOP_DP) })
        })
        loadForMode()
        setupSport()
        setupWelcome(root)
        LauncherStartup.begin(window.decorView)
        ThemeTransition.playIn(this)
        LauncherBottomBar.logResult(this)
        appliedSignature = HomeScreenPrefs.signature(this)
        if (pendingEdit) openPendingEdit()
        // Favori tuşu: etkinlik ömrü boyunca dinlenir (HOME etkinliği başka uygulama öndeyken de yaşar, yalnızca durur).
        FavoriteKey.sync(this)
        // Ses (VR) tuşu uzun basış → bağlı telefonun asistanı: ayara göre, aynı etkinlik ömrüyle.
        PhoneSource.syncVoiceKey(this)
        DrawTrace.applyFrom(this, intent)
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
        // Debug: launcher açıkken gelen --ei launcher_nav_area (ekstra işlenince silinir, tekrarlanmaz).
        LauncherProbe.applyFrom(this, intent)
        PhoneDataProbe.applyFrom(this, intent)
        com.mapcontrol.launcher.cluster.ClusterMap.applyFrom(this, intent)
        LauncherDemo.applyDriveMode(intent)
        LauncherDemo.applyMoving(intent)
        LauncherDemo.applyPreview(intent)
        RpmRateLog.applyFrom(this, intent)
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
            if (isFinishing || isDestroyed || !::sahne.isInitialized || recreating || !pendingEdit) return@post
            pendingEdit = false
            enterEditMode()
        }
    }

    /** Bir sonraki yerinde tema yenilemesi düzenleme modunu korusun mu ([rethemeKeepingEdit]). */
    private var keepEditOnRetheme = false

    /** Görünüm panelinden tema seçimi: düzenleme açıksa açık kalır ve panel yeni temayla yeniden gösterilir. */
    internal fun rethemeKeepingEdit() {
        keepEditOnRetheme = editing
        ThemeTransition.retheme(this)
    }

    /**
     * Tema değişimini etkinliği yeniden yaratmadan uygular ([ThemeTransition.retheme] üstteki anlık görüntü örtüsünün
     * altında çağırır). Jetonlar güncellenir; başlık yerinde yenilenir, alt çubuk ve Sahne orta alanı yeniden kurulur
     * (çalışan işleri durdurulup yenisi başlatılır). Açık sayfa kapanır, düzenleme modundan çıkılır.
     */
    internal fun applyThemeInPlace() {
        if (isFinishing || isDestroyed || !::sahne.isInitialized) return
        // Görünüm panelinden tema değişimi düzenleme modunu bozmaz (gece/gündüz karşılaştırılabilsin); panel yeniden açılır.
        val keepEdit = keepEditOnRetheme && editing
        keepEditOnRetheme = false
        sheet?.dismiss()
        sheet = null
        wifiPanel?.dismiss()
        bluetoothPanel?.dismiss()
        if (!keepEdit) exitEditMode()
        // Karşılama eski görünümlere bağlı: önce son haline çekilir (kök zemin geri verilir), sahne yenilenince yeni
        // parçalarla yeniden kurulur (siyah tutma yeniden kurulmaz).
        welcome?.finish()
        welcomeArmUntil = 0L
        refreshDrivePrefs()
        if (!driveAccentOn) LauncherTheme.driveMode = DriveAccent.NORMAL
        LauncherTheme.applyFrom(resources.configuration, ThemeMode.read(this))
        syncAutoNight()
        // Gösterge haritası Otomatik stil: gündüz/gece değişince çerçeve bir kez yeniden çizilir (kapalıysa bir şey yapmaz).
        com.mapcontrol.launcher.cluster.ClusterMap.refreshStyle(this)
        window.decorView.setBackgroundColor(LauncherTheme.GROUND)
        rootLayout?.setBackgroundColor(LauncherTheme.GROUND)

        // Sabit parçalar: başlık yerinde yenilenir.
        header.applyTheme()
        val newEditBar = buildEditBar().also { it.visibility = editBar.visibility }
        swapView(editBar, newEditBar)
        editBar = newEditBar
        refreshEditLayoutUi()
        // Alt çubuk cam ve düğme renklerini kurulumda aldığı için yenisiyle değiştirilir.
        ourBar?.let { old ->
            val fresh = OurBottomBar(this, onPhoneTap = ::onPhoneButton)
            swapView(old.view, fresh.view)
            if (started) old.onHidden()
            old.release()
            ourBar = fresh
            if (started) showWidget(fresh)
        }
        mockBar?.let { old -> OemBarMock.build(this).also { swapView(old, it); mockBar = it } }
        callBanner.applyTheme()
        fixedWidgets = listOfNotNull(header, ourBar, callBanner)

        // Orta alan yeni jetonlarla baştan kurulur (örtünün altında); düzen ve mod aynı kalır.
        rootLayout?.background = SahneGround()
        val old = sahne
        val fresh = newSahneScreen()
        if (started) old.onHidden()
        old.release()
        swapView(old, fresh)
        sahne = fresh
        fresh.setMode(activeMode)
        rootLayout?.let { welcome = buildWelcome(it) }
        sport?.onRebuilt()
        if (keepEdit) {
            fresh.editMode = true
            window.decorView.post { if (!isFinishing && !isDestroyed && editing) settings.show(appearanceOnly = true) }
        }
        if (started) fresh.onVisible()
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
        pending.forEach { w -> if (w in fixedWidgets) w.onVisible() }
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

    /** Spor denetleyicisi; etkinlik yeniden kurulduysa ve spor açıktıysa geçişsiz geri gelir. */
    private fun setupSport() {
        sport?.release()
        // En yüksek hız ve 0–100 süreç düzeyinde beslenir (ana ekran görünmezken de; yalnız okuma).
        SportSessionStore.start(this)
        sport = SportController(this, sahne = { sahne }, header = { header }, bar = { ourBar }, root = { rootLayout },
            moving = { if (LauncherDemo.enabled) LauncherDemo.moving else observedMoving },
            inPark = { if (LauncherDemo.enabled) null else observedInPark })
        val keep = SportController.rememberedSport && sportAllowed() && observedSportMode() == DriveAccent.SPORT
        sportGate.reset(keep)
        if (keep) sport?.applyInstant(true) else SportController.rememberedSport = false
    }

    /** Spor ekranı yalnız "Spor modu (deneysel)" açıksa (varsayılan kapalı); demo zorlaması her zaman geçer. */
    private fun sportAllowed(): Boolean = sportModeOn || (LauncherDemo.enabled && LauncherDemo.sportForced != null)

    private fun observedSportMode(): Int? = when {
        LauncherDemo.enabled -> LauncherDemo.sportForced?.let { if (it) DriveAccent.SPORT else DriveAccent.NORMAL } ?: LauncherDemo.driveMode
        else -> observedDriveMode
    }

    /** Spor kararı: [SportGate] (~1 sn titreşim süzgeci); düzenleme ya da sayfa açıkken ertelenir. */
    private fun sportTick() {
        val ctl = sport ?: return
        val observed = if (sportAllowed()) observedSportMode() else DriveAccent.NORMAL
        // Süzgeç sayarken durağan çizim şimdiden hazırlanır; mod geri dönerse bırakılır.
        if (observed == DriveAccent.SPORT && !editing) ctl.prepare() else ctl.idle()
        // Monoton saat: ana ünitenin saati eşitlenince (araç saati yanlış) süzgeç sıçramasın ya da takılmasın.
        // Pencere odakta değilken (aracın kendi sürüş modu penceresi üstte) geçiş bekler: iki animasyon üst üste binmez,
        // pencere kapanıp odak dönünce geçiş başlar.
        val to = sportGate.update(android.os.SystemClock.elapsedRealtime(), observed, !editing && sheet == null && windowFocused) ?: return
        ctl.request(to)
    }

    /**
     * Sürüş modu değişimini uygular: gözlenen mod ~2 sn kararlı kalınca vurgu güncellenir ve etkinlik
     * ThemeTransition ile yeniden kurulur (renkler kurulumda okunur). Düzenleme/sayfa açıkken ertelenir.
     */
    private fun driveModeTick() {
        if (!driveAccentOn) return
        val raw = if (LauncherDemo.enabled) LauncherDemo.driveMode else observedDriveMode
        // Spor kendi ekranıyla gelir ([sportTick]); vurgu için yeniden kurulum (ve ikinci bir geçiş) yapılmaz.
        // Spor ekranı kapalıysa Spor da diğer modlar gibi yalnız vurgu rengini değiştirir.
        val observed = if (raw == DriveAccent.SPORT && sportAllowed()) null else raw
        val to = driveModePolicy.update(System.currentTimeMillis(), observed, !editing && sheet == null)
        if (to != null && to != LauncherTheme.driveMode) {
            LauncherTheme.driveMode = to
            ThemeTransition.retheme(this)
        }
    }

    /**
     * Sahne karşılamasını kurar. Süreçte ilk kez (araç ünitesinde ya da demo isteğiyle) ekran baştan siyah tutulur: güç
     * sinyali [WELCOME_ARM_MS] içinde gelirse karşılama karanlıktan başlar, gelmezse kısa solmayla açılır.
     */
    private fun setupWelcome(root: View) {
        val w = buildWelcome(root)
        welcome = w
        val demo = LauncherDemo.enabled && LauncherDemo.welcomeRequested
        if (!WelcomeCycle.gate.played && !WelcomeCycle.armedOnce && (demo || LauncherSafeArea.isDesayUnit(this))) {
            WelcomeCycle.armedOnce = true
            w.prepare()
            welcomeArmUntil = android.os.SystemClock.uptimeMillis() + WELCOME_ARM_MS
        }
    }

    private fun buildWelcome(root: View): SahneWelcome {
        val (stage, parts) = sahne.welcomeParts()
        val others = listOfNotNull(header.view, ourBar?.view) + parts
        return SahneWelcome(root, stage, others)
    }

    /**
     * Karşılama (§7.4): kontak açılınca (güç modu 1/2) ya da kontak açıkken ilk öne gelişte bir kez; spor açıkken atlanır.
     * Demo `launcher_demo_welcome true` beklemeden yeniden oynatır. Spor açılırsa ya da düzenlemeye girilirse hemen biter.
     */
    private fun welcomeTick() {
        val w = welcome ?: return
        val sportOn = sport?.shown == true
        if ((w.running || w.prepared) && (sportOn || editing || sheet != null)) {
            w.finish()
            welcomeArmUntil = 0L
            return
        }
        val ready = resumed && !editing && sheet == null && wifiPanel == null && bluetoothPanel?.isShowing != true
        if (LauncherDemo.enabled && LauncherDemo.welcomeRequested && ready) {
            LauncherDemo.welcomeRequested = false
            WelcomeCycle.gate.replay()
            welcomeArmUntil = 0L
            if (WelcomeCycle.gate.update(true, ready = true, sportActive = sportOn)) w.play() else w.finish()
            return
        }
        if (w.running) return
        if (WelcomeCycle.gate.update(observedPowerOn, ready, sportOn)) {
            welcomeArmUntil = 0L
            w.play()
        } else if (w.prepared && (WelcomeCycle.gate.played || android.os.SystemClock.uptimeMillis() > welcomeArmUntil)) {
            welcomeArmUntil = 0L
            w.reveal()
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

    /** Düzeni değiştirir: orta alan kısa süre kararır, yeni düzen kurulur, geri açılır (etkinlik yeniden yaratılmaz). */
    private fun switchMode(to: LayoutMode, animate: Boolean, automatic: Boolean = false) {
        if (to == activeMode) return
        val notice = layoutNoticeFor(activeMode, to, automatic, started)
        activeMode = to
        LauncherMotion.driveLayout = to == LayoutMode.DRIVE
        rememberedMode = to
        LauncherInfo.mode = to
        notice?.let { showLayoutNotice(it) }
        val apply = { loadForMode() }
        // Spor ekranında orta alan aynı kalır (düzen arkada değişir): solma yok.
        if (!animate || !started || sport?.shown == true) { apply(); return }
        val m = sahne
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
        val m = sahne
        m.animate().cancel()
        m.animate().alpha(0f).setDuration(EDIT_SWAP_OUT_MS).withEndAction {
            switchMode(to, animate = false)
            refreshEditLayoutUi()
            m.animate().alpha(1f).setDuration(EDIT_SWAP_IN_MS).start()
        }.start()
        // Seçici hemen yanıt verir; orta alan kısa geçişle ardından değişir.
    }

    private fun refreshEditLayoutUi() {
        editHelper.setText(when {
            ::sahne.isInitialized && sahne.sportShown -> R.string.launcher_sport_helper_edit
            activeMode == LayoutMode.DRIVE -> R.string.launcher_sahne_helper_drive
            else -> R.string.launcher_sahne_helper_park
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
        sportModeOn = SportModePrefs.enabled(this)
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
        sahne.onVisible()
        // Ana ekrana dönüş: araç uykusunda düşmüş olabilecek favori tuşu aboneliği tazelenir.
        FavoriteKey.refresh()
        PhoneSource.refreshVoiceKey()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        LauncherEdition.setHomeForeground(true)
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
        welcome?.finish()
        welcomeArmUntil = 0L
        LauncherTheme.stopAutoNight()
        LauncherEdition.setHomeForeground(false)
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
        bluetoothPanel?.dismiss()
        exitEditMode()
        metrics.removeListener(metricsListener)
        fixedWidgets.forEach { it.onHidden() }
        sahne.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        sport?.release()
        if (::fixedWidgets.isInitialized) fixedWidgets.forEach { it.release() }
        if (::sahne.isInitialized) sahne.release()
        // Ortak örnek: release() çağrılmaz (güneş çatısı vb. dinleyicileri sönerdi), yalnızca kendi dinleyicimiz çıkar.
        metrics.removeListener(metricsListener)
        settings.release()
        dataCheckExtra?.release()
        dataCheckExtra = null
        FavoriteKey.stop(this)
        PhoneSource.stopVoiceKey(this)
        super.onDestroy()
    }

    /** İzin sonuçları: depolama verilince açık fotoğraf seçici yenilenir. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Telefon izinleri (rehber, geçmiş, arama): kısmi verilse de sonuç yeniden okunur.
        if (requestCode == PhoneSource.REQ_PERMISSIONS) {
            PhoneSource.onPermissionsResult(this)
            return
        }
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

    /** Etkinlik penceresi odakta mı; aracın sürüş modu penceresi gibi sistem pencereleri açıkken false. */
    private var windowFocused = true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) { hideSystemBars(); sportTick() }
    }

    /** Etkin düzeni (Park/Sürüş) Sahne'ye uygular. */
    private fun loadForMode() = sahne.setMode(activeMode)

    // --- Düzenleme modu ---

    /** Düzenleme yalnızca araç dururken açılır; sürüş sırasında ekranla uğraşılmasın. */
    private fun enterEditMode() {
        if (editing) return
        if (editLocked()) return
        // Spor ekranı açıksa düzenleme spor küçük kadranlarında yapılır (uzun basınca seçici); spor kalır. Geçiş sürerken
        // (karartmada) ise spor geçişsiz kapanır: orta alan Sahne yuvalarının düzenlemesine geçer, çubuklar spor kalmaz.
        if (sport?.transitioning == true) { sport?.applyInstant(false); sportGate.reset(false) }
        sahne.editMode = true
        refreshEditLayoutUi()
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
        sahne.editMode = false
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
            setText(R.string.launcher_sahne_helper_park)
        }, weightLp(1f))
        addView(gap(context, 16))
        // Yuvalar sabit: ekleme yuvanın kendi seçicisinden yapılır.
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
                    sahne.resetToDefaults()
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

    /**
     * Alt çubuk telefon düğmesi ([PhoneUiLogic.phoneButtonAction]): telefon paneli ekrandaysa bir şey yapılmaz; bağlı
     * telefon var ama arama yolu yoksa OEM telefon uygulaması; yoksa telefon sayfası (bağlı değilse Bluetooth düğmesiyle).
     */
    private fun onPhoneButton() {
        if (editing || isFinishing || isDestroyed) return
        val s = PhoneSource.snapshot()
        val panelOnScreen = ::sahne.isInitialized && !sahne.sportShown && sahne.hasPanel(WidgetType.PHONE)
        when (PhoneUiLogic.phoneButtonAction(panelOnScreen, s.connected, s.canDial || LauncherDemo.enabled)) {
            // Panel zaten ekranda: dokunuş boşa gitmiş gibi görünmesin (titreşim + panel kısa bir nabız atar).
            PhoneUiLogic.PhoneButtonAction.NONE -> {
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                sahne.flashPanel(WidgetType.PHONE)
            }
            PhoneUiLogic.PhoneButtonAction.OEM_APP -> openOemPhoneApp(this)
            PhoneUiLogic.PhoneButtonAction.SHEET -> openPhoneSheet()
        }
    }

    /** Gelen arama şeridi göründü: üstteki sayfa ve Wi-Fi/Bluetooth panelleri kapanır (Cevapla/Reddet örtülmesin). */
    private fun onIncomingCall() {
        sheet?.dismiss()
        sheet = null
        wifiPanel?.dismiss()
        bluetoothPanel?.dismiss()
    }

    /** Telefon sayfası: Sahne paneliyle aynı görünüm; arama başlayınca kapanır, kapanınca panel bırakılır. */
    private fun openPhoneSheet() {
        sheet?.dismiss()
        var panel: PhonePanel? = null
        val d = showLauncherSheet(this, R.string.launcher_phone_title, PHONE_SHEET_WIDTH_DP) { dialog ->
            val p = PhonePanel(this@HomeLauncherActivity, sheet = true, onDialed = { dialog.dismiss() })
            panel = p
            addView(p.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(PHONE_SHEET_HEIGHT_DP)))
            p.onVisible()
        }
        d.setOnDismissListener {
            panel?.release()
            panel = null
            if (sheet === d) sheet = null
        }
        sheet = d
    }

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

    /** Bluetooth simgesine dokunma: launcher'dan çıkmadan simgenin altında Bluetooth paneli (ikinci dokunuş kapatır). */
    private fun toggleBluetoothPanel(anchor: View) {
        if (editing) return
        val panel = bluetoothPanel ?: BluetoothPanel(this, AndroidBluetoothBackend(this), onSettings = { openOemBluetooth(this) })
            .also { bluetoothPanel = it }
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
        // minSdk 29: WindowInsetsController API 30'da geldi; Android 10'da (ya da denetleyici yoksa) eski bayrak yolu.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && hideSystemBarsR()) return
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            if (LauncherTheme.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.R)
    private fun hideSystemBarsR(): Boolean {
        val it = window.insetsController ?: return false
        it.hide(WindowInsets.Type.statusBars())
        if (LauncherBottomBar.hidesOemBar(this)) it.hide(WindowInsets.Type.navigationBars())
        // Gündüz görünümünde gezinme çubuğu simgeleri açık zeminde görünür kalsın.
        it.setSystemBarsAppearance(
            if (LauncherTheme.night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        return true
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
        /** Soğuk açılışta karşılama için güç sinyalinin en çok beklendiği süre (ekran bu sırada siyah). */
        const val WELCOME_ARM_MS = 1_500L
        const val RESET_SHEET_WIDTH_DP = 640
        const val PHONE_SHEET_WIDTH_DP = 760
        const val PHONE_SHEET_HEIGHT_DP = 500
        /** Arama şeridinin üst kenardan payı (başlığın üstünde durur). */
        const val CALL_BANNER_TOP_DP = 12
    }
}
