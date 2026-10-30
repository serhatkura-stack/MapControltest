package com.mapcontrol.hud

import android.app.Activity
import android.graphics.Color
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.client.bind.VDServiceDef
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.launcher.CarSignals
import com.mapcontrol.vehicle.material.OemEolConfigReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tek seferlik, park halinde HUD denemesi. YALNIZCA adb ile, ekstralarla başlatılır (manifestte intent-filter
 * yok; exported=false iken shell başlatamadığı için exported=true, korumalar kodda). Launcher arayüzünden ulaşılmaz.
 *
 * Ekstralar (`--es hud ... --es ack PARKED`; ack olmadan hiçbir şey olmaz):
 *  - `demo`  : temizle, [52], yol adı "Test Cad.", [49] ikon 9 / 250 m, [51] 1200 m, 5 sn bekle, temizle.
 *  - `alt`   : aynısı, 49/51 yerine 50/53 ailesi (isteğe bağlı `--ei icon`, `--ei dist`).
 *  - `icon`  : `--ei icon N [--ei dist M]`: [52], tek [49] (ikon N, mesafe M, öntanımlı 250), 5 sn, temizle.
 *  - `clear` : yalnızca [3,1,1].
 *  - `limit` : `--ei kmh N [--ei status S] [--ez clear true]`: hız limiti tabelası (modül 327682, komut 123 sonra 125),
 *              ~8 sn her 1 sn yeniden gönderilir; `--ez clear true` yoksa sonunda temizlenmez.
 *  - `limit_off` : durum 0 / limit 0 / tabela 0, bir kez.
 *  - `turbodog` : `[--ei icon N] [--ei dist M] [--ez explicit true]`: araca YAZMAZ; Desay navi uygulamasına
 *              TurboDog yayınları (202, ~10 sn 201, 203). `amap`: aynısı AMap standart yayınları (10019/10001).
 *  - `info`  : SALT-OKUR; araç yapılandırması (106, 163) ve HUD/hız/vites sinyallerini okur, günlüğe yazar.
 * Yazan modlarda her adımdan önce park kapısı denetlenir; reddedilirse dizi durur. Başlayan her dizi sonunda
 * (istisna olsa da) bir temizleme gönderilir; hiçbir şey gönderilmediyse (ilk adım reddedildi) gönderilmez.
 */
class HudTestActivity : Activity() {

    private lateinit var status: TextView
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(48, 24, 48, 24)
        }
        setContentView(status)
        handle()
    }

    /** Çalışırken gelen yeni intent (am start aynı üstteki örneğe teslim eder) de aynı yoldan işlenir. */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        val mode = intent?.getStringExtra(EXTRA_MODE)
        // exported=true olmak zorunda (adb shell başlatabilsin); kötüye kullanıma karşı onay ekstrası ve başlatan denetimi.
        val caller = referrer?.host
        if (caller != null && caller != "com.android.shell") {
            show("REDDEDİLDİ: başlatan paket izinli değil ($caller); hiçbir şey gönderilmedi")
            Log.w(HudNavSender.TAG, "REFUSED başlatan paket: $caller")
            finishSoon(3000)
            return
        }
        if (mode != null && intent?.getStringExtra(EXTRA_ACK) != ACK_VALUE) {
            show("REDDEDİLDİ: onay ekstrası yok (--es ack $ACK_VALUE); hiçbir şey gönderilmedi")
            Log.w(HudNavSender.TAG, "REFUSED onay ekstrası yok")
            finishSoon(3000)
            return
        }
        val icon = if (intent?.hasExtra(EXTRA_ICON) == true) intent.getIntExtra(EXTRA_ICON, -1) else null
        val dist = intent?.getIntExtra(EXTRA_DIST, DEFAULT_DIST) ?: DEFAULT_DIST
        if (mode == null || mode !in MODES) {
            show("HUD testi: --es hud demo|alt|icon|clear|info|limit|limit_off|limit_unknown|turbodog|amap\n(geçersiz/eksik mod: $mode) hiçbir şey gönderilmedi")
            Log.w(HudNavSender.TAG, "geçersiz mod: $mode")
            finishSoon(4000)
            return
        }
        if (!RUNNING.compareAndSet(false, true)) {
            show("Başka bir HUD testi sürüyor; bu istek yok sayıldı")
            finishSoon(3000)
            return
        }
        Thread({
            try {
                run(mode, icon, dist)
            } catch (t: Throwable) {
                Log.e(HudNavSender.TAG, "test hatası", t)
                show("HATA: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                RUNNING.set(false)
            }
            finishSoon(4000)
        }, "HudTest").start()
    }

    private fun run(mode: String, icon: Int?, dist: Int) {
        val sender = HudNavSender(this)
        show("HUD testi: $mode\nservise bağlanılıyor...")
        val connected = sender.connect()
        if (mode == "info") {
            info(connected)
            return
        }
        if (!connected) {
            show("REDDEDİLDİ: araç servisi bağlı değil; hiçbir şey gönderilmedi")
            Log.w(HudNavSender.TAG, "REFUSED araç servisi bağlı değil")
            return
        }
        if (mode == "clear") {
            show(sender.clear().message)
            return
        }
        if (mode == "limit" || mode == "limit_off" || mode == "limit_unknown") {
            limitTest(sender, mode)
            return
        }
        if (mode == "turbodog" || mode == "amap") {
            broadcastTest(sender, mode)
            return
        }

        val steps: List<IntArray> = try {
            when (mode) {
                "demo" -> listOf(
                    HudPayload.navStarted(), HudPayload.roadName("Test Cad."),
                    HudPayload.maneuver(9, 250), HudPayload.totalDistance(1200),
                )
                "alt" -> listOf(
                    HudPayload.navStarted(), HudPayload.roadName("Test Cad."),
                    HudPayload.maneuverAlt(icon ?: 9, dist), HudPayload.totalDistanceAlt(1200),
                )
                else -> listOf(
                    HudPayload.navStarted(),
                    HudPayload.maneuver(icon ?: throw IllegalArgumentException("--ei icon N gerekli"), dist),
                )
            }
        } catch (e: IllegalArgumentException) {
            show("REDDEDİLDİ: ${e.message}")
            return
        }

        // Başlangıç temizlemesi de bir yazmadır: önce park kapısı, reddedilirse hiçbir şey yazılmaz.
        val gate = sender.parkDecision()
        if (!gate.allowed) {
            show("REDDEDİLDİ: park kapısı: ${gate.reason}\nhiçbir şey gönderilmedi")
            return
        }
        var anySent = false
        try {
            // Her yeni başlangıçtan önce temizle (adaptör davranışı).
            anySent = true
            val first = sender.clear()
            show(first.message)
            if (!first.sent) { anySent = false; return }
            for (step in steps) {
                Thread.sleep(STEP_GAP_MS)
                val r = sender.send(step)
                show(r.message)
                if (!r.sent) return
            }
            show("${lines.last()}\n\nHUD'u gözle (${HOLD_MS / 1000} sn)...")
            Thread.sleep(HOLD_MS)
        } finally {
            if (anySent) {
                val r = sender.clear()
                show("${r.message}\n(son temizleme)")
            }
        }
    }

    /**
     * Hız limiti tabelası (modül 327682): 123 sonra 400 ms sonra 125, ~8 sn her 1 sn yeniden gönderilir (özgün uygulama
     * da yeniden gönderir). Her gönderim park kapısından geçer; reddedilirse dizi durur. `--ez clear true` yoksa
     * sonunda temizlenmez (tabelanın kalıp kalmadığını görmek için).
     */
    private fun limitTest(nav: HudNavSender, mode: String) {
        val limit = HudLimitSender(nav)
        if (mode == "limit_unknown") {
            // ~8 sn: 112 "limit bilinmiyor" + 123 durum 0, özgün uygulama gibi her 1 sn.
            for (round in 1..LIMIT_ROUNDS) {
                val a = limit.send(HudLimitPayload.unknownLimit())
                show(a.message)
                if (!a.sent) return
                Thread.sleep(STEP_GAP_MS)
                val b = limit.send(HudLimitPayload.clearLimit())
                show(b.message)
                if (!b.sent) return
                Thread.sleep(1000L - STEP_GAP_MS)
            }
            return
        }
        if (mode == "limit_off") {
            show(limit.send(HudLimitPayload.clearLimit()).message)
            Thread.sleep(STEP_GAP_MS)
            show(limit.send(HudLimitPayload.clearSign()).message)
            return
        }
        if (intent?.hasExtra(EXTRA_KMH) != true) {
            show("REDDEDİLDİ: --ei kmh N gerekli; hiçbir şey gönderilmedi")
            return
        }
        val kmh = intent.getIntExtra(EXTRA_KMH, -1)
        val status = intent.getIntExtra(EXTRA_STATUS, HudLimitPayload.DEFAULT_STATUS)
        val wantClear = intent.getBooleanExtra(EXTRA_CLEAR, false)
        val limitPayload: HudLimitPayload.Payload
        val signPayload: HudLimitPayload.Payload
        try {
            limitPayload = HudLimitPayload.limit(kmh, status)
            signPayload = HudLimitPayload.sign(kmh)
        } catch (e: IllegalArgumentException) {
            show("REDDEDİLDİ: ${e.message}")
            return
        }
        var anySent = false
        try {
            for (round in 1..LIMIT_ROUNDS) {
                val a = limit.send(limitPayload)
                show("[$round/$LIMIT_ROUNDS] ${a.message}")
                if (!a.sent) return
                anySent = true
                Thread.sleep(STEP_GAP_MS)
                val b = limit.send(signPayload)
                show("[$round/$LIMIT_ROUNDS] ${b.message}")
                if (!b.sent) return
                Thread.sleep(1000 - STEP_GAP_MS)
            }
            show("${lines.last()}\n\nHUD/göstergeyi gözle (limit $kmh)")
        } finally {
            if (anySent && wantClear) {
                val a = limit.send(HudLimitPayload.clearLimit())
                Thread.sleep(STEP_GAP_MS)
                val b = limit.send(HudLimitPayload.clearSign())
                show("${a.message} / ${b.message}\n(temizleme)")
            }
        }
    }

    /**
     * Araca YAZMAZ: Desay navi uygulamasının dinleyebileceği TurboDog (`turbodog`) veya AMap (`amap`) yayınları.
     * Park kapısı başlamadan ve her saniye denetlenir; reddedilirse dizi durur, başlamış seyir için bitiş yayını gider.
     */
    private fun broadcastTest(nav: HudNavSender, mode: String) {
        val icon = if (intent?.hasExtra(EXTRA_ICON) == true) intent.getIntExtra(EXTRA_ICON, TurboDogPayload.DEFAULT_ICON)
        else TurboDogPayload.DEFAULT_ICON
        val dist = if (intent?.hasExtra(EXTRA_DIST) == true) intent.getIntExtra(EXTRA_DIST, TurboDogPayload.DEFAULT_DIST)
        else TurboDogPayload.DEFAULT_DIST
        val explicit = intent?.getBooleanExtra(EXTRA_EXPLICIT, false) ?: false
        val amap = mode == "amap"
        val action = if (amap) TurboDogPayload.AMAP_ACTION else TurboDogPayload.TURBODOG_ACTION
        val gate = nav.parkDecision()
        if (!gate.allowed) {
            show("REDDEDİLDİ: park kapısı: ${gate.reason}\nhiçbir şey gönderilmedi")
            return
        }
        var started = false
        try {
            broadcast(action, if (amap) TurboDogPayload.amapState(TurboDogPayload.AMAP_STATE_START) else TurboDogPayload.turboStart(), explicit)
            started = true
            show("$mode: başlangıç yayını gitti${if (explicit) " (paket: ${TurboDogPayload.TARGET_PACKAGE})" else ""}")
            for (second in 0 until BROADCAST_ROUNDS) {
                Thread.sleep(1000)
                val g = nav.parkDecision()
                if (!g.allowed) {
                    show("DURDURULDU: park kapısı: ${g.reason}")
                    return
                }
                val d = TurboDogPayload.distAt(dist, second)
                val extras = if (amap) TurboDogPayload.amapGuide(icon, d)
                else TurboDogPayload.turboTurn(icon, d, System.currentTimeMillis())
                broadcast(action, extras, explicit)
                show("[${second + 1}/$BROADCAST_ROUNDS] $mode ikon=$icon mesafe=$d")
            }
            show("${lines.last()}\n\nHUD/göstergeyi gözle")
        } finally {
            if (started) {
                broadcast(action, if (amap) TurboDogPayload.amapState(TurboDogPayload.AMAP_STATE_END) else TurboDogPayload.turboEnd(), explicit)
                show("${lines.last()}\n(bitiş yayını)")
            }
        }
    }

    /** Tek yayın gönderim noktası; her yayın günlüğe yazılır. [explicit] ise yalnızca Desay navi paketine gider. */
    private fun broadcast(action: String, extras: Map<String, Any>, explicit: Boolean) {
        val i = Intent(action)
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (explicit) i.setPackage(TurboDogPayload.TARGET_PACKAGE)
        for ((k, v) in extras) {
            when (v) {
                is Int -> i.putExtra(k, v)
                is Long -> i.putExtra(k, v)
                is String -> i.putExtra(k, v)
                else -> throw IllegalArgumentException("desteklenmeyen ekstra türü: $k")
            }
        }
        Log.i(HudNavSender.TAG, "BROADCAST action=$action explicit=$explicit extras=$extras")
        sendBroadcast(i)
    }

    /** SALT-OKUR: yazma yok. */
    private fun info(connected: Boolean) {
        val out = ArrayList<String>()
        out += "connected=$connected"
        if (connected) {
            val ro = VDEventCarInfo.MODULE_READONLY_INFO
            out += "speed119=${CarSignals.readInt(ro, 119)} speed0=${CarSignals.readInt(ro, 0)}"
            out += "gear26=${CarSignals.readInt(ro, 26)} handbrake75=${CarSignals.readInt(ro, 75)}"
            for (id in HudNavSender.HUD_READ_IDS) {
                out += "HUD id=$id -> ${CarSignals.read(VDEventCarInfo.MODULE_HUD, id)?.contentToString()}"
            }
        }
        // Yapılandırma bayt blokları VEHICLE_DEVICE servisinden (yoksa getprop) okunur.
        try {
            val vd = VDBus.getDefault()
            vd.bindService(VDServiceDef.ServiceType.VEHICLE_DEVICE)
            var waited = 0
            while (!vd.isServiceConnected(VDServiceDef.ServiceType.VEHICLE_DEVICE) && waited < 2500) {
                Thread.sleep(100); waited += 100
            }
        } catch (e: RuntimeException) {
            out += "VEHICLE_DEVICE bağlanamadı: ${e.message}"
        }
        val c4 = OemEolConfigReader.readConfig4Bytes()
        val c5 = OemEolConfigReader.readConfig5Bytes()
        out += "configs4=${c4?.contentToString()} configs5=${c5?.contentToString()}"
        // EolConfig: 106 = configs4[4] bit 4 (HUD var); 163 = configs5[6] (ülke); 170 = configs6[1]&3 (okunamadı).
        val c106 = if (c4 != null && c4.size >= 8) (c4[4] shr 4) and 1 else null
        val c163 = if (c5 != null && c5.size >= 8) c5[6] and 255 else null
        out += "config106(HUD var)=$c106 config163(ülke)=$c163 config170=okuma yolu yok (configs6)"
        out.forEach { Log.i(HudNavSender.TAG, "INFO $it") }
        show("HUD bilgi (salt-okur)\n" + out.joinToString("\n"))
    }

    private fun show(text: String) {
        synchronized(lines) { lines += text.lineSequence().first() }
        runOnUiThread { status.text = text }
    }

    private fun finishSoon(ms: Long) {
        status.postDelayed({ if (!isFinishing && !RUNNING.get()) finish() }, ms)
    }

    companion object {
        const val EXTRA_MODE = "hud"
        const val EXTRA_ICON = "icon"
        const val EXTRA_DIST = "dist"
        const val EXTRA_ACK = "ack"
        const val EXTRA_KMH = "kmh"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CLEAR = "clear"
        const val EXTRA_EXPLICIT = "explicit"
        const val ACK_VALUE = "PARKED"
        private const val DEFAULT_DIST = 250
        private const val STEP_GAP_MS = 400L
        private const val HOLD_MS = 5000L
        private const val LIMIT_ROUNDS = 8
        private const val BROADCAST_ROUNDS = 10
        private val MODES = setOf("demo", "alt", "icon", "clear", "info", "limit", "limit_off", "limit_unknown", "turbodog", "amap")
        private val RUNNING = AtomicBoolean(false)
    }
}
