package com.mapcontrol.hud

import android.app.Activity
import android.graphics.Color
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
            show("HUD testi: --es hud demo|alt|icon|clear|info\n(geçersiz/eksik mod: $mode) hiçbir şey gönderilmedi")
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
        const val ACK_VALUE = "PARKED"
        private const val DEFAULT_DIST = 250
        private const val STEP_GAP_MS = 400L
        private const val HOLD_MS = 5000L
        private val MODES = setOf("demo", "alt", "icon", "clear", "info")
        private val RUNNING = AtomicBoolean(false)
    }
}
