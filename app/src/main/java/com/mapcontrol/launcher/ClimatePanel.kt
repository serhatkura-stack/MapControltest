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
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
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
 * OEM HVAC servisine arayüz açma isteği (`type` = OPEN_PANEL, `page` 0 = ön klima). Not (bizim araç, T1E24): OPEN_PANEL
 * statik `isHvacShow` bayrağını true yapar ama paneli ZORLA açmaz; HVAC 9 bit 0 o an 0 ise panel kapalı kalır
 * (`SVHvac/.../svhvac/service/HvacService.java:83-87` → `view/h.java:647-655`). Servis başarısız olursa OEM
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
     * OEM panelini kapatır: `HvacService` `type` = CLOSE_HVAC_BT. Bizim araçta (T1E24, SVHvac 2025-11-12) panel SVHvac'ın
     * kendisidir ve servis bu isteği şöyle işler (`SVHvac/.../svhvac/service/HvacService.java:88-106`, T1E24'te
     * `svhvaclib.a.f()` false): HVAC 9 bit 0 (ön sıra) açıksa 9 ← 2, bit 2 (koku) açıksa 64 ← 2, bit 1 (PM2.5) açıksa 51 ← 0.
     * Bu yazmaları BİZ değil OEM uygulaması yapar; değerler OEM'in panelde aşağı kaydırma kapatmasıyla aynıdır
     * (`model/svhvac/f/d.java:179-181` X0: 9 ← 2). ECU 9=0 gönderince panel kendi animasyonuyla kapanır (`view/h.java:547-572`).
     *
     * CLOSE_PANEL KULLANILMAZ: servis statik `isHvacShow` bayrağını false yapar (`HvacService.java:79-82`) ve bayrak yeni bir
     * OPEN_PANEL gelene kadar öyle kalır; o sürede ECU 9=1 gönderse de (fiziksel klima düğmesi, OEM dock) panel açılmaz
     * (`view/h.java:554`; 6 Kasım günlüğü 15:48:42 `isHvacShow =false`). Arkadaşın build'inde servis aynı türü
     * `ACTION_HVAC_SERVICE` yayınına çevirir, SystemUI `HvacActionReceiver` CLOSE_HVAC_BT'yi de işler; servis yoksa yayını biz göndeririz.
     */
    fun close(context: Context): String {
        val outcome = try {
            context.startService(
                Intent().setComponent(ComponentName(PACKAGE, SERVICE)).putExtra("type", TYPE_CLOSE_HVAC_BT).putExtra("page", PAGE_FRONT),
            )?.let { "service" } ?: closeBroadcast(context)
        } catch (e: SecurityException) {
            closeBroadcast(context)
        } catch (e: IllegalStateException) {
            closeBroadcast(context)
        }
        // Araca yazmayı OEM servisi yapar (beyaz listemizden geçmez); iz kalsın diye WidgetWrite'a da bir satır.
        val hv9 = CarSignals.readInt(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_DISPLAY_ACTIVE)
        Log.i(WidgetWritePolicy.LOG_TAG, "widget=ClimatePanelClose via=oem-service type=CLOSE_HVAC_BT outcome=$outcome hv9=${ClimatePanelBits.describe(hv9)}")
        return outcome
    }

    private fun closeBroadcast(context: Context): String = try {
        context.sendBroadcast(Intent(ACTION_HVAC_SERVICE).putExtra("type", TYPE_CLOSE_HVAC_BT).putExtra("page", PAGE_FRONT))
        "broadcast"
    } catch (e: SecurityException) {
        "failed"
    }

    /** OEM paneli açık mı: [ClimatePanelBits.SHOW_KEYS] anahtarlarından biri "true". */
    fun isShowing(context: Context): Boolean = try {
        val r = context.contentResolver
        ClimatePanelBits.anyShown { Settings.System.getString(r, it) }
    } catch (e: RuntimeException) {
        false
    }

    private const val TYPE_CLOSE_HVAC_BT = "com.desaysv.svhvac.CLOSE_HVAC_BT"
    private const val ACTION_HVAC_SERVICE = "com.desaysv.svhvac.ACTION_HVAC_SERVICE"

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
 * OEM klima panelinin görünürlük anahtarları ve HVAC 9 bit alanı (saf, birim testli).
 *
 * Görünürlük: bizim araçta SVHvac `Settings.System "hvacshow"` (tamamı küçük harf) yazar; açılınca "true"
 * (`SVHvac/.../svhvac/view/h.java:668`), kapanma animasyonu bitince ve yok edilirken "false" (`h.java:219`, `:835`).
 * Settings anahtarları büyük/küçük harfe duyarlıdır. `hvacShow` ve `HVAC_DIALOG_IS_SHOW` arkadaşın build'inin (panel
 * SystemUI'de) anahtarlarıdır; yedek olarak sonra okunur.
 *
 * HVAC 9 düz 1/0 değil, bit alanıdır: bit 0 ön sıra klima, bit 1 PM2.5, bit 2 koku (`SVHvac/.../model/svhvac/f/d.java:185-212`,
 * `(f(9) >> n) & 1`).
 */
internal object ClimatePanelBits {
    val SHOW_KEYS = listOf("hvacshow", "hvacShow", "HVAC_DIALOG_IS_SHOW")

    /** [get]: anahtar → Settings değeri. Anahtarlardan biri tam "true" ise panel açık. */
    fun anyShown(get: (String) -> String?): Boolean = SHOW_KEYS.any { get(it) == "true" }

    fun frontRow(hv9: Int?): Boolean = hv9 != null && (hv9 and 1) != 0
    fun pm25(hv9: Int?): Boolean = hv9 != null && (hv9 and 2) != 0
    fun fragrance(hv9: Int?): Boolean = hv9 != null && (hv9 and 4) != 0

    /** Günlük için kısa çözüm: `null`, `0` ya da `5[front,fragrance]`. */
    fun describe(hv9: Int?): String {
        if (hv9 == null) return "null"
        val parts = listOfNotNull("front".takeIf { frontRow(hv9) }, "pm25".takeIf { pm25(hv9) }, "fragrance".takeIf { fragrance(hv9) })
        return if (parts.isEmpty()) "$hv9" else "$hv9[${parts.joinToString(",")}]"
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
 * Neden açılıyor (bizim araç, T1E24): panel SVHvac'tadır ve HVAC 9'un bitlerinden biri gelince açılır
 * (`SVHvac/.../svhvac/view/h.java:547-572` A1; [ClimatePanelBits]). Panel kimliğine (10) yazınca ECU ~85 ms sonra 9=1
 * gönderiyor (6 Kasım günlüğü 15:51:23.637 → .721). OEM alt dock'u (SVSystemUI) sıcaklığı ayrı kimliklere yazar: sürücü 76,
 * yolcu 78 (`SVSystemUI/.../model/svhvac/e/c.java:393-400`, `:431-438`); fan için bkz. [ClimateWriter.fanId]. Gizle'de bunlar
 * kullanılır; panel yine açılırsa [ClimatePanel.close] (CLOSE_HVAC_BT, OEM'in aşağı kaydırma kapatmasıyla aynı yazma).
 */
internal object ClimatePanelPolicy {
    /** Yazmadan sonra panelin açılmasını bekleme süresi (araç gidiş-dönüşü + SystemUI animasyonu). */
    const val GUARD_MS = 4_000L
    const val POLL_MS = 200L

    /**
     * Kapatma isteğinden sonra yeniden bakmadan önce bekleme: ECU ~130 ms'de 9=0 gönderir (6 Kasım günlüğü 15:48:51.722 →
     * .849), panelin kapanma animasyonu 800 ms sürer ve `hvacshow` ancak animasyon bitince "false" olur (`view/h.java:219`,
     * `:810`). Daha erken bakılırsa kapanmakta olan panel için 9 ← 2 boş yere yinelenirdi.
     */
    const val CLOSE_SETTLE_MS = 1_500L

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
 * Gizle ayarında alt çubuktan yazmadan hemen önce kurulur ([arm]); [ClimatePanelPolicy.GUARD_MS] boyunca `hvacshow`'u
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
                main.postDelayed(tick, ClimatePanelPolicy.CLOSE_SETTLE_MS)
            }
            else -> {
                Log.i(TAG, "guard end reason=${d.name.lowercase()} closes=$closes")
                disarm()
            }
        }
    }
}
