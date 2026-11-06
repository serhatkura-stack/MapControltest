package com.mapcontrol.launcher.cluster

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.mapcontrol.launcher.LauncherTheme
import com.mapcontrol.launcher.cluster.ClusterMapMath.Box
import com.mapcontrol.launcher.cluster.ClusterMapMath.Frame
import com.mapcontrol.launcher.cluster.ClusterMapMath.FrameChoice
import com.mapcontrol.launcher.cluster.ClusterMapMath.MapApp
import com.mapcontrol.launcher.cluster.ClusterMapMath.WindowSize

/**
 * "Gösterge haritası (deneysel)", varsayılan KAPALI. Gerçek Yandex haritası göstergede küçültülmüş bir pencerede,
 * tasarlanmış bir çerçevenin içinde görünür:
 *  1) Gösterge ekranına tam ekran, dokunulmaz bir TYPE_APPLICATION_OVERLAY penceresi eklenir ([ClusterFrameView]):
 *     çerçeve + haritanın göründüğü şeffaf delik + delik kenarında iç gölge/kıl çizgi (maske). Overlay uygulama
 *     pencerelerinin ÜSTÜNDE olduğu için aynı pencere hem zemin hem maske olur.
 *  2) Harita uygulaması aynı ekranda serbest pencerede (freeform, windowingMode=5) deliğin sınırlarıyla açılır;
 *     uygulama kendini o boyuta göre düzenler (kırpılmaz). Başlık çubuğu çerçevenin altında kalır.
 * Herhangi bir adım olmazsa (ekran yok, SYSTEM_ALERT_WINDOW yok, freeform kapalı, başlatma hatası) her şey geri alınır,
 * sebep "ClusterMap" etiketiyle loga yazılır ve çağıran ESKİ davranışı uygular. Araca hiçbir şey yazmaz (VDBus yok).
 * Kapalıyken hiçbir şey çalışmaz; açıkken de çerçeve bir kez çizilir.
 *
 * Bilinen sınır (emülatör, Android 11): sınırlar yalnızca YENİ görevde uygulanır. Harita zaten açıksa (Yandex'in giriş
 * etkinliği singleTask) görevi tam ekran taşınır ve çerçeve onu kırparak maskeler; çerçeveli açılmış görev de sonradan
 * başka ekrana taşınınca küçük pencere kalır (başlıktaki büyüt düğmesi ya da son uygulamalardan kapatmak düzeltir).
 * B yolu (kendi VirtualDisplay'imiz) Android 11'de reddediliyor: "Permission Denial ... with launchDisplayId" (bkz. VD denemesi).
 *
 * Debug ekstraları (yalnızca debug yapı; HomeLauncherActivity, launcher açıkken de):
 *   --ez launcher_cluster_map true|false          çerçeveli haritayı aç/kapat (ayarı değiştirmez)
 *   --es launcher_cluster_frame gece|gunduz|uydu|otomatik   çerçeve stili (ayara yazılır)
 *   --es launcher_cluster_rect 510,150,1310,600   pencere (oturumluk; QNX alanına giriyorsa reddedilir)
 *   --ei launcher_cluster_map_display <id>        gösterge ekran numarasını zorla
 *   --es launcher_cluster_image /sdcard/Download/x.png   görseli göstergede tam ekran göster (1920x720'ye ölçeklenir)
 *   --ez launcher_cluster_image_off true          görseli kaldır
 */
internal object ClusterMap {

    const val TAG = "ClusterMap"
    const val EXTRA_MAP = "launcher_cluster_map"
    const val EXTRA_FRAME = "launcher_cluster_frame"
    const val EXTRA_RECT = "launcher_cluster_rect"
    const val EXTRA_DISPLAY = "launcher_cluster_map_display"
    const val EXTRA_IMAGE = "launcher_cluster_image"
    const val EXTRA_IMAGE_OFF = "launcher_cluster_image_off"

    /** Debug: B yolu denemesi (kendi VirtualDisplay'imize harita açmak); sonuç yalnızca loga yazılır. */
    const val EXTRA_VD_PROBE = "launcher_cluster_vd_probe"

    private const val PREFS = "launcher_cluster_map"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FRAME = "frame"
    private const val KEY_APP = "app"
    private const val KEY_WINDOW = "window"

    private const val SETTING_FREEFORM = "enable_freeform_support"
    private const val WINDOWING_MODE_FREEFORM = 5

    private val main = Handler(Looper.getMainLooper())

    private var frameView: ClusterFrameView? = null
    private var frameWm: WindowManager? = null
    private var imageView: View? = null
    private var imageWm: WindowManager? = null
    private var imagePresentation: Presentation? = null
    private var displayId = Display.INVALID_DISPLAY
    private var launchedPkg: String? = null
    private var appContext: Context? = null
    private var listening = false
    private var sessionHole: Box? = null
    private var forcedDisplay: Int? = null

    // --- Ayarlar ---

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    fun frameChoice(c: Context): FrameChoice = FrameChoice.of(prefs(c).getString(KEY_FRAME, null))
    fun setFrameChoice(c: Context, v: FrameChoice) = prefs(c).edit().putString(KEY_FRAME, v.key).apply()
    fun mapApp(c: Context): MapApp = MapApp.of(prefs(c).getString(KEY_APP, null))
    fun setMapApp(c: Context, v: MapApp) = prefs(c).edit().putString(KEY_APP, v.key).apply()
    fun windowSize(c: Context): WindowSize = WindowSize.of(prefs(c).getString(KEY_WINDOW, null))
    fun setWindowSize(c: Context, v: WindowSize) = prefs(c).edit().putString(KEY_WINDOW, v.key).apply()

    fun isRunning(): Boolean = frameView != null

    /** Gösterge ekranı var mı (ayar satırları yalnızca o zaman görünür). */
    fun hasCluster(c: Context): Boolean = findDisplay(c, null, log = false) != null

    /** Ayarın seçtiği, kurulu harita paketi (yoksa null). */
    fun chooseMapPackage(c: Context): String? = ClusterMapMath.choosePackage(mapApp(c), { isInstalled(c, it) }, isDebug(c))

    private fun currentFrame(c: Context): Frame = ClusterMapMath.resolveFrame(frameChoice(c), LauncherTheme.night)

    // --- Açma / kapama ---

    /**
     * Çerçeveyi kurar ve [pkg]'yi (null ise ayardaki Yandex) göstergede pencerede açar. Ana iş parçacığından çağrılır.
     * @return true: çerçeveli harita açıldı. false: hiçbir şey değişmedi; çağıran eski davranışı uygulamalı.
     */
    @JvmStatic
    @JvmOverloads
    fun startFramed(context: Context, pkg: String? = null): Boolean {
        val c = context.applicationContext
        appContext = c
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: chooseMapPackage(c)
            ?: return fail(c, "harita uygulaması kurulu değil (Yandex Navigator / Yandex Haritalar)")
        val display = findDisplay(c, forcedDisplay, log = true) ?: return fail(c, "gösterge ekranı bulunamadı")
        if (!Settings.canDrawOverlays(c)) return fail(c, "SYSTEM_ALERT_WINDOW izni yok (adb shell appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow)")
        if (!freeformOn(c)) return fail(c, "serbest pencere (freeform) kapalı (adb shell settings put global $SETTING_FREEFORM 1)")
        val launch = c.packageManager.getLaunchIntentForPackage(target) ?: return fail(c, "başlatma intent'i yok: $target")

        val hole = sessionHole ?: windowSize(c).hole
        if (!ClusterMapMath.isSafe(hole)) return fail(c, "pencere QNX alanına giriyor: $hole")
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val sx = m.widthPixels / ClusterMapMath.DESIGN_W.toFloat()
        val sy = m.heightPixels / ClusterMapMath.DESIGN_H.toFloat()
        if (sx != 1f || sy != 1f) Log.w(TAG, "gösterge ${m.widthPixels}x${m.heightPixels}, 1920x720 değil; ölçekleniyor")

        removeFrame()
        try {
            val dctx = c.createDisplayContext(display)
            val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = ClusterFrameView(dctx, currentFrame(c), hole)
            wm.addView(view, overlayParams("ClusterMapFrame"))
            frameView = view
            frameWm = wm
            displayId = display.displayId
        } catch (e: Throwable) {
            removeFrame()
            return fail(c, "çerçeve penceresi eklenemedi: ${e.javaClass.simpleName}: ${e.message}")
        }

        val b = ClusterMapMath.launchBounds(hole, sx, sy)
        try {
            // Sınırlar yalnızca YENİ görevde uygulanır. Yandex'in giriş etkinliği singleTask: uygulama zaten açıksa görevi
            // (tam ekran) bu ekrana taşınır ve çerçeve onu maskeler (kırpılmış görünüm). Belge/çoklu görev bayrakları
            // singleTask'ta yok sayılıyor (emülatörde denendi).
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = display.displayId
            opts.setLaunchBounds(Rect(b.left, b.top, b.right, b.bottom))
            val bundle = opts.toBundle()
            bundle.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM)
            c.startActivity(launch, bundle)
        } catch (e: Throwable) {
            removeFrame()
            return fail(c, "harita pencerede açılamadı: ${e.javaClass.simpleName}: ${e.message}")
        }
        launchedPkg = target
        listen(c, true)
        Log.i(TAG, "çerçeveli harita açıldı: $target ekran=${display.displayId} pencere=$hole sınır=$b çerçeve=${currentFrame(c).key}")
        return true
    }

    /**
     * Çerçeveyi kaldırır; harita penceresine dokunmaz. (Android 11'de başka uygulamanın görevini yeniden tam ekran
     * yapmanın yolu yok: farklı ekrana taşımak da pencere modunu korur. FrostNav'ın kapanış akışı haritayı her zamanki
     * gibi ana ekrana alır; orada başlıktaki "büyüt" düğmesi tam ekran yapar.)
     */
    @JvmStatic
    fun stop(context: Context, why: String) {
        val was = frameView != null
        removeFrame()
        launchedPkg = null
        listen(context.applicationContext, false)
        if (was) Log.i(TAG, "çerçeveli harita kapatıldı ($why)")
    }

    /** FrostNav "Göstergeye yansıt" kapanınca: çerçeve kalkar (sonra eski kapanış akışı aynen sürer). */
    @JvmStatic
    fun onProjectionClosed(context: Context) {
        val run = Runnable { if (frameView != null) stop(context, "yansıtma kapandı") }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    /** Otomatik stil: launcher gündüz/gece değişince çerçeve bir kez yeniden çizilir. */
    fun refreshStyle(context: Context) {
        val v = frameView ?: return
        v.setFrame(currentFrame(context.applicationContext))
    }

    // --- Görsel modu (konsept okunaklılık testi) ---

    fun showImage(context: Context, path: String) {
        val c = context.applicationContext
        hideImage()
        val display = findDisplay(c, forcedDisplay, log = true) ?: run { Log.w(TAG, "görsel: gösterge ekranı yok"); return }
        val bmp = decodeScaled(path) ?: return
        val dctx = c.createDisplayContext(display)
        val view = ImageView(dctx, bmp)
        if (Settings.canDrawOverlays(c)) {
            try {
                val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.addView(view, overlayParams("ClusterMapImage").apply { format = PixelFormat.OPAQUE })
                imageView = view
                imageWm = wm
                Log.i(TAG, "görsel gösterildi (overlay): $path ekran=${display.displayId}")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "görsel overlay reddedildi: ${e.message}; Presentation deneniyor")
            }
        }
        try {
            val p = Presentation(c, display)
            p.setContentView(ImageView(p.context, bmp))
            p.show()
            imagePresentation = p
            Log.i(TAG, "görsel gösterildi (Presentation): $path ekran=${display.displayId}")
        } catch (e: Throwable) {
            Log.e(TAG, "görsel gösterilemedi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun hideImage() {
        try {
            imageView?.let { imageWm?.removeView(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "görsel kaldırma: ${e.message}")
        }
        imageView = null
        imageWm = null
        try {
            imagePresentation?.dismiss()
        } catch (e: Throwable) {
            Log.w(TAG, "görsel Presentation kapatma: ${e.message}")
        }
        imagePresentation = null
    }

    private fun decodeScaled(path: String): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0) {
                Log.w(TAG, "görsel okunamadı: $path"); return null
            }
            var sample = 1
            while (o.outWidth / (sample * 2) >= ClusterMapMath.DESIGN_W && o.outHeight / (sample * 2) >= ClusterMapMath.DESIGN_H) sample *= 2
            val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            Log.i(TAG, "görsel ${o.outWidth}x${o.outHeight} (örnekleme $sample)")
            raw
        } catch (e: Throwable) {
            Log.w(TAG, "görsel okunamadı: $path: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Görseli ekranın tamamına (1920x720) ölçekleyerek bir kez çizer. */
    private class ImageView(context: Context, private val bmp: Bitmap) : View(context) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            c.drawBitmap(bmp, null, Rect(0, 0, width, height), p)
        }
    }

    // --- Debug ---

    /** HomeLauncherActivity onCreate/onNewIntent: debug yapıda ekstraları işler, işlenen ekstralar silinir. */
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent == null || !isDebug(activity)) return
        val keys = listOf(EXTRA_MAP, EXTRA_FRAME, EXTRA_RECT, EXTRA_DISPLAY, EXTRA_IMAGE, EXTRA_IMAGE_OFF, EXTRA_VD_PROBE)
        if (keys.none { intent.hasExtra(it) }) return
        val c = activity.applicationContext
        if (intent.hasExtra(EXTRA_DISPLAY)) forcedDisplay = intent.getIntExtra(EXTRA_DISPLAY, -1).takeIf { it >= 0 }
        if (intent.hasExtra(EXTRA_RECT)) {
            val raw = intent.getStringExtra(EXTRA_RECT)
            val box = ClusterMapMath.parseBox(raw)
            sessionHole = when {
                raw.isNullOrBlank() || raw == "off" -> null
                box == null -> { Log.w(TAG, "pencere biçimi bozuk: $raw (l,t,r,b)"); sessionHole }
                !ClusterMapMath.isSafe(box) -> { Log.w(TAG, "pencere reddedildi (QNX alanı): $box"); sessionHole }
                else -> box
            }
            Log.i(TAG, "oturum penceresi: ${sessionHole ?: "ayar (${windowSize(c).key})"}")
        }
        val frame = intent.getStringExtra(EXTRA_FRAME)
        val image = intent.getStringExtra(EXTRA_IMAGE)
        val imageOff = intent.getBooleanExtra(EXTRA_IMAGE_OFF, false)
        val map: Boolean? = if (intent.hasExtra(EXTRA_MAP)) intent.getBooleanExtra(EXTRA_MAP, false) else null
        val vdProbe = intent.getBooleanExtra(EXTRA_VD_PROBE, false)
        keys.forEach { intent.removeExtra(it) }
        main.post {
            if (frame != null) {
                val choice = FrameChoice.of(frame)
                if (choice.key != frame.trim().lowercase()) Log.w(TAG, "geçersiz çerçeve: $frame (gece|gunduz|uydu|otomatik)")
                setFrameChoice(c, choice)
                refreshStyle(c)
                Log.i(TAG, "çerçeve: ${choice.key} → ${currentFrame(c).key}")
            }
            if (imageOff) hideImage()
            if (!image.isNullOrBlank()) showImage(c, image)
            if (vdProbe) probeVirtualDisplay(c, 0)
            when (map) {
                true -> if (!startFramed(c)) Log.w(TAG, "debug: çerçeveli harita açılamadı (yukarıdaki sebep)")
                false -> stop(c, "debug komutu")
                null -> Unit
            }
        }
    }

    /**
     * B yolu (yalnızca debug, salt deneme): 800x450 @140 kendi sanal ekranımızı (önce özel, sonra herkese açık)
     * oluşturup harita uygulamasını üzerine açmayı dener; kabul/ret ve ekrandaki etkinlik loga yazılır, 5 sn sonra
     * ekran bırakılır. Android 11, başka uygulamanın sanal ekranına allowEmbedded olmayan etkinliği açmayı reddeder.
     */
    private fun probeVirtualDisplay(c: Context, step: Int) {
        val variants = listOf(
            "özel" to DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            "herkese açık" to (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY),
        )
        if (step >= variants.size) return
        val (name, flags) = variants[step]
        val target = chooseMapPackage(c) ?: run { Log.w(TAG, "VD denemesi: harita uygulaması yok"); return }
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val reader = android.media.ImageReader.newInstance(800, 450, PixelFormat.RGBA_8888, 2)
        val vd = try {
            dm.createVirtualDisplay("ClusterMapProbe", 800, 450, 140, reader.surface, flags)
        } catch (e: Throwable) {
            Log.w(TAG, "VD denemesi ($name): oluşturulamadı: ${e.javaClass.simpleName}: ${e.message}")
            reader.close()
            main.post { probeVirtualDisplay(c, step + 1) }
            return
        }
        val id = vd.display.displayId
        Log.i(TAG, "VD denemesi ($name): ekran id=$id flags=0x${Integer.toHexString(vd.display.flags)}")
        try {
            val li = c.packageManager.getLaunchIntentForPackage(target)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val o = ActivityOptions.makeBasic()
            o.launchDisplayId = id
            c.startActivity(li, o.toBundle())
            Log.i(TAG, "VD denemesi ($name): startActivity hata vermedi ($target → ekran $id); dumpsys ile doğrulayın")
        } catch (e: Throwable) {
            Log.w(TAG, "VD denemesi ($name): REDDEDİLDİ: ${e.javaClass.simpleName}: ${e.message}")
        }
        main.postDelayed({
            vd.release()
            reader.close()
            Log.i(TAG, "VD denemesi ($name): ekran bırakıldı")
            probeVirtualDisplay(c, step + 1)
        }, 5000)
    }

    // --- Yardımcılar ---

    private fun fail(c: Context, reason: String): Boolean {
        Log.w(TAG, "çerçeveli harita açılmadı: $reason → eski davranış")
        return false
    }

    private fun overlayParams(title: String) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { setTitle(title) }

    private fun removeFrame() {
        try {
            frameView?.let { frameWm?.removeView(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "çerçeve kaldırma: ${e.message}")
        }
        frameView = null
        frameWm = null
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayChanged(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) {
            if (id != displayId) return
            Log.w(TAG, "gösterge ekranı kalktı (id=$id); çerçeve kaldırılıyor")
            removeFrame()
            hideImage()
            launchedPkg = null
            appContext?.let { listen(it, false) }
        }
    }

    private fun listen(c: Context, on: Boolean) {
        if (on == listening) return
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (on) dm.registerDisplayListener(displayListener, main) else dm.unregisterDisplayListener(displayListener)
        listening = on
    }

    /** Gösterge: zorlanan numara; yoksa 2 (araç); yoksa 1920x720 varsayılan-dışı ekran; yoksa ilk sunum ekranı. */
    private fun findDisplay(c: Context, forced: Int?, log: Boolean): Display? {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val all = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        fun is1920(d: Display) = DisplayMetrics().also { d.getRealMetrics(it) }.let { it.widthPixels == 1920 && it.heightPixels == 720 }
        val d = if (forced != null) all.firstOrNull { it.displayId == forced }
        else all.firstOrNull { it.displayId == 2 } ?: all.firstOrNull(::is1920)
            ?: dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (log) Log.i(TAG, "gösterge ekranı: ${d?.let { "id=${it.displayId} ${it.name}" } ?: "yok"} (zorlanan=$forced, ekranlar=${all.map { it.displayId }})")
        return d
    }

    private fun freeformOn(c: Context): Boolean =
        Settings.Global.getInt(c.contentResolver, SETTING_FREEFORM, 0) != 0 ||
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    private fun isInstalled(c: Context, pkg: String): Boolean = try {
        c.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun isDebug(c: Context) = c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
