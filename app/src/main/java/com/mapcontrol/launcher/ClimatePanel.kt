package com.mapcontrol.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.mapcontrol.R
import kotlin.math.abs

/** Alt çubukta yukarı kaydırma ile OEM klima panelini açma eşiği (saf mantık, birim testli). */
internal object ClimatePanelGesture {
    const val OPEN_SWIPE_DP = 60f

    /** Yukarı hareket (dy > 0 yukarı) kayma payını aşıp ağırlıkla dikey mi: yakalama için. */
    fun isUpwardDrag(dy: Float, dx: Float, slopPx: Float): Boolean = dy > slopPx && dy > abs(dx)

    /** Bırakınca panel açılsın mı: dikey yukarı hareket en az [OPEN_SWIPE_DP] ve yataydan büyük. */
    fun shouldOpen(dyPx: Float, dxPx: Float, density: Float): Boolean =
        dyPx >= OPEN_SWIPE_DP * density && dyPx > abs(dxPx)
}

/**
 * OEM klima panelini açar (araçta çubuğun gizlediği OEM yukarı kaydırma jestinin yerine). Araca yazma yok: yalnızca
 * OEM HVAC servisine arayüz açma isteği (`type` = OPEN_PANEL, `page` 0 = ön klima). Servis başarısız olursa OEM
 * uygulamasının ana etkinliği, o da yoksa kısa uyarı. Sonuç ([TAG]) bir kez günlüğe yazılır.
 */
internal object ClimatePanel {
    private const val TAG = "ClimatePanel"
    const val PACKAGE = "com.desaysv.svhvac"
    private const val SERVICE = "com.desaysv.svhvac.service.HvacService"
    const val TYPE_OPEN = "com.desaysv.svhvac.OPEN_PANEL"
    private const val PAGE_FRONT = 0

    /** Bilerek açma (çubukta yukarı kaydırma): koruma bunu asla kapatmaz ([ClimatePanelGuard.noteExplicitOpen]). */
    fun open(context: Context) {
        ClimatePanelGuard.noteExplicitOpen()
        val outcome = try {
            context.startService(
                Intent().setComponent(ComponentName(PACKAGE, SERVICE))
                    .putExtra("type", TYPE_OPEN)
                    .putExtra("page", PAGE_FRONT),
            )?.let { "service" } ?: fallback(context, "service not found")
        } catch (e: SecurityException) {
            fallback(context, "SecurityException")
        } catch (e: IllegalStateException) {
            fallback(context, "IllegalStateException")
        }
        Log.i(TAG, "open outcome=$outcome")
        if (outcome == "failed") Toast.makeText(context, R.string.launcher_climate_panel_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * OEM panelini YALNIZCA ARAYÜZDEN kapatır; araca yazma yok (HVAC 9 ← 2 gönderilmez). OEM yolu: `HvacService`
     * `type` = CLOSE_PANEL'i `ACTION_HVAC_SERVICE` yayınına çevirir (`SVHvac/.../service/HvacService.java:61-72`), SystemUI
     * `HvacActionReceiver` bunu `HideHvac` yapar (`SVSystemUI/.../receiver/HvacActionReceiver.java:122-131,178`) ve panel
     * `HvacDisplayStatus.NONE` ile kapanır (`viewmodel/MainViewModel.java:689-692`). Servis yoksa aynı yayın doğrudan gönderilir.
     */
    fun close(context: Context): String = try {
        context.startService(
            Intent().setComponent(ComponentName(PACKAGE, SERVICE)).putExtra("type", TYPE_CLOSE).putExtra("page", PAGE_FRONT),
        )?.let { "service" } ?: closeBroadcast(context)
    } catch (e: SecurityException) {
        closeBroadcast(context)
    } catch (e: IllegalStateException) {
        closeBroadcast(context)
    }

    private fun closeBroadcast(context: Context): String = try {
        context.sendBroadcast(Intent(ACTION_HVAC_SERVICE).putExtra("type", TYPE_CLOSE).putExtra("page", PAGE_FRONT))
        "broadcast"
    } catch (e: SecurityException) {
        "failed"
    }

    /** OEM paneli açık mı: SystemUI `HvacWindowController.showHvac/closeHvac` `hvacShow` yazar (`:870`, `:464`). */
    fun isShowing(context: Context): Boolean = try {
        val r = context.contentResolver
        Settings.System.getString(r, KEY_HVAC_SHOW) == "true" || Settings.System.getString(r, KEY_DIALOG_SHOW) == "true"
    } catch (e: RuntimeException) {
        false
    }

    private const val TYPE_CLOSE = "com.desaysv.svhvac.CLOSE_PANEL"
    private const val ACTION_HVAC_SERVICE = "com.desaysv.svhvac.ACTION_HVAC_SERVICE"
    // SVVrControl `a/b.java:462-465` paneli bu iki anahtardan biri "true" ise açık sayıyor.
    private const val KEY_HVAC_SHOW = "hvacShow"
    private const val KEY_DIALOG_SHOW = "HVAC_DIALOG_IS_SHOW"

    private fun fallback(context: Context, reason: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return "failed"
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "activity ($reason)"
        } catch (e: ActivityNotFoundException) {
            "failed"
        } catch (e: SecurityException) {
            "failed"
        }
    }
}

/**
 * "Klima değişince fabrika paneli: Göster | Gizle" (varsayılan GİZLE). Gizle iken alt çubuktaki sıcaklık/fan OEM'in kendi
 * dock'unun kimlikleriyle yazılır ve [ClimatePanelGuard] kısa süre izler; Göster iken eski panel kimlikleri (10/13/12).
 */
internal object ClimatePanelPrefs {
    private const val PREFS_NAME = "launcher_settings"
    private const val KEY_SHOW = "climate_oem_panel_on_change"

    fun showOnChange(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(Profiles.key(context, KEY_SHOW), false)

    fun setShowOnChange(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(Profiles.key(context, KEY_SHOW), show).apply()
    }
}

/**
 * Bizim yazmamızdan sonra OEM panelinin kendiliğinden açılmasına dair SAF karar (birim testli).
 *
 * Neden açılıyor: SystemUI paneli HVAC 9 (`ID_AC_DISPLAY_ACTIVE`) geri 1 okununca açar
 * (`SVSystemUI/.../model/svhvac/model/HvacModel.java:260-264` → `svhvac/viewmodel/MainViewModel$mHvacCallback$1.java:79-88`).
 * OEM panelinin kendi düğmeleri HVAC 10/13/12 yazar (`HvacModel.java:764`, `:891`, `onWindSpeedClick`); OEM alt dock'u ise
 * AYNI ham değeri ayrı "dock" kimliklerine yazar: sürücü 76, yolcu 78, fan 77 (`HvacModel.java:774`, `:899`, `:1085`;
 * `MainViewModel.java:827`, `:989`, `:646`, `isDock` dalı). Yazmada "kaynak/sessiz" bayrağı yok; OEM'in sessiz yolu bu ayrı
 * kimlikler. Gizle'de onlar kullanılır; panel yine açılırsa yalnızca arayüzden kapatılır ([ClimatePanel.close], araca yazma yok).
 */
internal object ClimatePanelPolicy {
    /** Yazmadan sonra panelin açılmasını bekleme süresi (araç gidiş-dönüşü + SystemUI animasyonu). */
    const val GUARD_MS = 4_000L
    const val POLL_MS = 200L

    /** Bir koruma penceresinde en çok bu kadar kapatma (döngüye girmesin). */
    const val MAX_CLOSES = 3

    enum class Decision { WAIT, CLOSE, KEEP_EXPLICIT_OPEN, KEEP_ALREADY_OPEN, EXPIRED, LIMIT }

    /**
     * [explicitOpenSinceArm]: kullanıcı korumadan sonra paneli bilerek açtı (yukarı kaydırma) → asla kapatma.
     * [shownAtArm]: yazmadan önce panel zaten açıktı → kullanıcının paneli, kapatma.
     */
    fun decide(
        explicitOpenSinceArm: Boolean,
        shownAtArm: Boolean,
        showingNow: Boolean,
        nowMs: Long,
        deadlineMs: Long,
        closes: Int,
    ): Decision = when {
        explicitOpenSinceArm -> Decision.KEEP_EXPLICIT_OPEN
        shownAtArm -> Decision.KEEP_ALREADY_OPEN
        nowMs > deadlineMs -> Decision.EXPIRED
        !showingNow -> Decision.WAIT
        closes >= MAX_CLOSES -> Decision.LIMIT
        else -> Decision.CLOSE
    }
}

/**
 * Gizle ayarında alt çubuktan yazmadan hemen önce kurulur ([arm]); [ClimatePanelPolicy.GUARD_MS] boyunca `hvacShow`'u
 * izler, panel kendiliğinden açılırsa arayüzden kapatır. Kararlar `ClimatePanel` etiketiyle günlüğe yazılır.
 */
internal object ClimatePanelGuard {
    private const val TAG = "ClimatePanel"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var armedAt = 0L
    private var deadline = 0L
    private var shownAtArm = false
    private var closes = 0
    private var explicitOpenAt = Long.MIN_VALUE
    private val tick = Runnable { check() }

    private fun now() = SystemClock.elapsedRealtime()

    /** Bilerek açıldı: süren koruma hemen biter, panel açık kalır. */
    fun noteExplicitOpen() {
        explicitOpenAt = now()
        if (deadline > 0L) {
            Log.i(TAG, "guard end reason=explicit-open closes=$closes")
            disarm()
        }
    }

    /** Ana iş parçacığında, yazmadan ÖNCE çağrılır. Süre dolmadan yeniden kurulursa yalnızca süre uzar. */
    fun arm(context: Context, what: String) {
        val t = now()
        app = context.applicationContext
        if (deadline <= t) {
            armedAt = t
            shownAtArm = ClimatePanel.isShowing(context)
            closes = 0
        }
        deadline = t + ClimatePanelPolicy.GUARD_MS
        Log.i(TAG, "write what=$what mode=hide ids=dock guard=arm shownAtArm=$shownAtArm")
        main.removeCallbacks(tick)
        main.postDelayed(tick, ClimatePanelPolicy.POLL_MS)
    }

    private fun disarm() {
        main.removeCallbacks(tick)
        deadline = 0L
    }

    private fun check() {
        val ctx = app ?: return disarm()
        val showing = ClimatePanel.isShowing(ctx)
        when (val d = ClimatePanelPolicy.decide(explicitOpenAt >= armedAt, shownAtArm, showing, now(), deadline, closes)) {
            ClimatePanelPolicy.Decision.WAIT -> main.postDelayed(tick, ClimatePanelPolicy.POLL_MS)
            ClimatePanelPolicy.Decision.CLOSE -> {
                closes++
                Log.i(TAG, "guard close n=$closes outcome=${ClimatePanel.close(ctx)}")
                main.postDelayed(tick, ClimatePanelPolicy.POLL_MS)
            }
            else -> {
                Log.i(TAG, "guard end reason=${d.name.lowercase()} closes=$closes")
                disarm()
            }
        }
    }
}
