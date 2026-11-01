package com.mapcontrol.comfort

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tek seferlik, park halinde konfor yazma testi (tavan perdesi, ön/arka buğu, direksiyon ısıtma). YALNIZCA adb ile,
 * ekstralarla başlatılır (manifestte intent-filter yok; exported=false iken shell başlatamadığı için exported=true,
 * korumalar kodda). Launcher arayüzünden ulaşılmaz. HudTestActivity ile aynı desen.
 *
 * Ekstralar (`--es c <komut> --es ack PARKED`; ack olmadan hiçbir şey olmaz; salt-okur komutlar dahil):
 *  - `info`, `shade_time`, `trunk_info`: SALT-OKUR (shade_time perdeyi elle oynatırken 30 sn, 100 ms aralıkla geçişleri zaman damgalarıyla yazar).
 *  - `shade_open`, `shade_close`, `shade_open_vr`, `shade_close_vr`, `shade_stop [--ei stop 3|6|0]`,
 *    `shade_open_stop [--ei after_ms 2000] [--ei stop 3]`.
 *  - `defrost_front`, `defrost_front_max`, `defrost_rear`, `defrost_rear_both`, `wheel_heat` (`--ei v 1|0`).
 *  - Bagaj kapağı (HEPSİ DOĞRULANMADI ADAY): `trunk_open_vr` (VR 121←1), `trunk_close_vr` (VR 121←2), `trunk_open_cs` (CS 92←3),
 *    `trunk_close_cs` (CS 92←4), `trunk_stop_cs` (CS 92←2), `trunk_open_stop_cs [--ei after_ms 2000]` (aç, bekle, durdur).
 *    Yazmadan sonra 10 sn boyunca PLG 98 / TRUNK 19 / CS 92 geçişleri günlüğe yazılır.
 * Yazan komutlarda HER yazmadan önce park kapısı denetlenir; reddedilirse dizi durur. Yazmadan sonra ilgili kimlikler
 * okunur ve değişimler "ComfortTest" etiketiyle günlüğe yazılır. Sonda geri alma/temizleme YAPILMAZ.
 */
class ComfortTestActivity : Activity() {

    private lateinit var status: TextView

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

    /** Çalışırken gelen yeni intent de aynı yoldan işlenir (yeniden girişte RUNNING bayrağı korur). */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle()
    }

    private fun handle() {
        // exported=true olmak zorunda (adb shell başlatabilsin); kötüye kullanıma karşı onay ekstrası ve başlatan denetimi.
        val caller = referrer?.host
        if (caller != null && caller != "com.android.shell") {
            show("REDDEDİLDİ: başlatan paket izinli değil ($caller); hiçbir şey gönderilmedi")
            Log.w(ComfortSender.TAG, "REFUSED başlatan paket: $caller")
            finishSoon(3000)
            return
        }
        val name = intent?.getStringExtra(EXTRA_CMD)
        if (intent?.getStringExtra(EXTRA_ACK) != ACK_VALUE) {
            show("REDDEDİLDİ: onay ekstrası yok (--es ack $ACK_VALUE); hiçbir şey gönderilmedi")
            Log.w(ComfortSender.TAG, "REFUSED onay ekstrası yok")
            finishSoon(3000)
            return
        }
        fun intExtra(key: String): Int? = if (intent?.hasExtra(key) == true) intent.getIntExtra(key, -1) else null
        val cmd = try {
            ComfortPlan.parse(name, intExtra(EXTRA_V), intExtra(EXTRA_AFTER_MS), intExtra(EXTRA_STOP))
        } catch (e: IllegalArgumentException) {
            show("REDDEDİLDİ: ${e.message}\nhiçbir şey gönderilmedi")
            Log.w(ComfortSender.TAG, "REFUSED ${e.message}")
            finishSoon(4000)
            return
        }
        if (!RUNNING.compareAndSet(false, true)) {
            // Yeniden giriş: devam eden test varsa bu istek yok sayılır (yeni yazma yapılmaz).
            show("Başka bir konfor testi sürüyor; bu istek yok sayıldı")
            Log.w(ComfortSender.TAG, "REFUSED başka test sürüyor: ${cmd.name}")
            finishSoon(3000)
            return
        }
        Thread({
            try {
                run(cmd)
            } catch (t: Throwable) {
                Log.e(ComfortSender.TAG, "test hatası", t)
                show("HATA: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                RUNNING.set(false)
                // Her yolda kapan: yığılan örnekler siyah ekran bırakıyordu (araçta görüldü).
                finishSoon(4000)
            }
        }, "ComfortTest").start()
    }

    private fun run(cmd: ComfortPlan.Command) {
        val sender = ComfortSender(this)
        show("Konfor testi: ${cmd.name}\nservise bağlanılıyor...")
        val connected = sender.connect()
        Log.i(ComfortSender.TAG, "START ${cmd.name} connected=$connected")
        when (cmd.kind) {
            ComfortPlan.Kind.INFO -> info(sender, connected, cmd)
            ComfortPlan.Kind.TIMING -> {
                if (!connected) return notConnected(cmd)
                watch(sender, cmd, cmd.watchMs, "shade_time: perdeyi şimdi elle oynat")
            }
            ComfortPlan.Kind.WRITE -> {
                if (!connected) return notConnected(cmd)
                writeCommand(sender, cmd)
            }
        }
        Log.i(ComfortSender.TAG, "END ${cmd.name}")
    }

    private fun notConnected(cmd: ComfortPlan.Command) {
        show("REDDEDİLDİ: araç servisi bağlı değil; hiçbir şey gönderilmedi")
        Log.w(ComfortSender.TAG, "REFUSED araç servisi bağlı değil (${cmd.name})")
    }

    /** SALT-OKUR: yazma yok. */
    private fun info(sender: ComfortSender, connected: Boolean, cmd: ComfortPlan.Command) {
        val out = ArrayList<String>()
        out += "connected=$connected"
        if (connected) {
            val gate = sender.parkDecision()
            out += "park kapısı: ${gate.allowed} (${gate.reason})"
            for (s in cmd.watch.ifEmpty { ComfortPlan.ALL_SIGNALS }) out += "${s.name} (${s.module}/${s.id}) -> ${sender.read(s)?.contentToString()}"
        }
        out.forEach { Log.i(ComfortSender.TAG, "INFO $it") }
        show("Konfor bilgi (salt-okur)\n" + out.joinToString("\n"))
    }

    private fun writeCommand(sender: ComfortSender, cmd: ComfortPlan.Command) {
        val tracker = ComfortPlan.ChangeTracker()
        val t0 = SystemClock.elapsedRealtime()
        // Yazmadan önceki taban değerleri.
        poll(sender, cmd, tracker, t0)
        for (step in cmd.steps) {
            when (step) {
                is ComfortPlan.Step.Wait -> pollFor(sender, cmd, tracker, t0, step.ms, cmd.intervalMs)
                is ComfortPlan.Step.Send -> {
                    val r = sender.send(step.write)
                    show(r.message)
                    if (!r.sent) return // park kapısı/servis reddi: dizi durur
                    poll(sender, cmd, tracker, t0)
                }
            }
        }
        pollFor(sender, cmd, tracker, t0, cmd.watchMs, cmd.intervalMs)
        show("${cmd.name} bitti; ${cmd.watchMs / 1000} sn okuma tamam (günlük: adb logcat -s ComfortTest)")
    }

    private fun watch(sender: ComfortSender, cmd: ComfortPlan.Command, ms: Long, banner: String) {
        show(banner)
        val tracker = ComfortPlan.ChangeTracker()
        val t0 = SystemClock.elapsedRealtime()
        poll(sender, cmd, tracker, t0)
        pollFor(sender, cmd, tracker, t0, ms, cmd.intervalMs)
        show("${cmd.name} bitti (${ms / 1000} sn)")
    }

    private fun pollFor(
        sender: ComfortSender, cmd: ComfortPlan.Command, tracker: ComfortPlan.ChangeTracker, t0: Long, ms: Long, interval: Long,
    ) {
        val end = SystemClock.elapsedRealtime() + ms
        while (true) {
            val remaining = end - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            Thread.sleep(minOf(interval, remaining))
            poll(sender, cmd, tracker, t0)
        }
    }

    /** İzlenen sinyalleri bir kez okur; değişenleri (ve ilk değeri) t0'dan beri geçen ms ile günlüğe yazar. */
    private fun poll(sender: ComfortSender, cmd: ComfortPlan.Command, tracker: ComfortPlan.ChangeTracker, t0: Long) {
        val t = SystemClock.elapsedRealtime() - t0
        for (s in cmd.watch) {
            val v = sender.read(s)?.contentToString() ?: "null"
            if (tracker.observe(s.name, v)) {
                Log.i(ComfortSender.TAG, "T+${t}ms ${s.name} -> $v")
                show("T+${t}ms ${s.name} -> $v")
            }
        }
    }

    private fun show(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun finishSoon(ms: Long) {
        status.postDelayed({ if (!isFinishing && !RUNNING.get()) finish() }, ms)
        // Yeniden giriş sürerken (RUNNING) kapanma ertelenir; test bitince finally yeniden çağırır.
    }

    companion object {
        const val EXTRA_CMD = "c"
        const val EXTRA_V = "v"
        const val EXTRA_AFTER_MS = "after_ms"
        const val EXTRA_STOP = "stop"
        const val EXTRA_ACK = "ack"
        const val ACK_VALUE = "PARKED"
        private val RUNNING = AtomicBoolean(false)
    }
}
