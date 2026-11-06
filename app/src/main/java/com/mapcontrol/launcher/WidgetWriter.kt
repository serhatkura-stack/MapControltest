package com.mapcontrol.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.comfort.ComfortPlan
import com.mapcontrol.vehicle.VehicleQuickControls
import java.util.concurrent.Executors

/**
 * Launcher widget'larının araca yazdığı TEK yer (iki derlemede de, yalnızca [LauncherEdition]'dan, kullanıcı dokunuşuyla).
 * Her deneme: beyaz liste ([WidgetWritePolicy.evaluate]; sürüş kilidi yok, hareket halinde de gider) →
 * `Log.i("WidgetWrite", ...)` tek satır → gönderilirse 3 sn'lik geri okuma (ilgili sinyallerin değişimleri günlüğe).
 */
internal object WidgetWriter {

    private const val READBACK_MS = 3_000L
    private const val READBACK_INTERVAL_MS = 250L

    private val readbackPool = Executors.newCachedThreadPool { r -> Thread(r, "widget-readback").apply { isDaemon = true } }

    /** CarInfoProxy.sendItemValue ile gönderilen yazmalar (koltuk, klima, bagaj). */
    fun send(proxy: CarInfoProxy, module: Int, id: Int, value: Int) {
        attempt(module, id, value, isConnected = { proxy.isServiceConnnected }) {
            proxy.sendItemValue(module, id, value)
        }
    }

    /** Ses alanı (DSP): beyaz listede modül = SOUND_FIELD olay kimliği, kimlik sabit, değer = fade; balance korunarak yazılır. */
    fun sendSoundField(balance: Int, fade: Int) {
        attempt(com.desaysv.ivi.vdb.event.id.dsp.VDEventDsp.SOUND_FIELD, SoundFieldMath.WRITE_ID, fade, isConnected = { true }) {
            SoundFieldIo.write(balance, fade)
        }
    }

    /** Merkezi kilit: komut VehicleQuickControls içinde gider; değer aynı formülle ([VehicleQuickControls.centralLockValue]) denetlenir. */
    fun sendLock(context: Context, locked: Boolean) {
        val controls = VehicleQuickControls.getInstance(context.applicationContext)
        attempt(
            VDEventCarInfo.MODULE_CAR_SETTING, com.desaysv.ivi.extra.project.carinfo.CarSettingID.ID_CAR_IVI_LOCK,
            VehicleQuickControls.centralLockValue(locked),
            isConnected = { controls.isCarInfoConnected },
        ) { controls.setCentralLock(locked) }
    }

    private fun attempt(module: Int, id: Int, value: Int, isConnected: () -> Boolean, send: () -> Unit) {
        val verdict = WidgetWritePolicy.evaluate(module, id, value)
        if (verdict !is WidgetWritePolicy.Verdict.Allowed) {
            val widget = WidgetWritePolicy.find(module, id, value)?.widget
            Log.w(WidgetWritePolicy.LOG_TAG, WidgetWritePolicy.logLine(widget, module, id, value, WidgetWritePolicy.resultText(verdict)))
            return
        }
        val entry = verdict.entry
        if (!isConnected()) {
            Log.w(WidgetWritePolicy.LOG_TAG, WidgetWritePolicy.logLine(entry.widget, module, id, value, "refused-no-service"))
            return
        }
        val before = entry.readBack.map { it to describe(it) }
        send()
        Log.i(WidgetWritePolicy.LOG_TAG, WidgetWritePolicy.logLine(entry.widget, module, id, value, "sent") +
            " before=" + before.joinToString(" ") { "${it.first.name}=${it.second}" })
        readbackPool.execute { watch(entry, before) }
    }

    private fun describe(s: ComfortPlan.Signal): String =
        if (s.module == com.desaysv.ivi.vdb.event.id.dsp.VDEventDsp.SOUND_FIELD) SoundFieldIo.read()?.let { "[balance=${it.balance},fade=${it.fade}]" } ?: "null"
        else if (s.module == VDEventCarInfo.MODULE_HVAC && s.id == com.desaysv.ivi.extra.project.carinfo.HvacID.ID_AC_DISPLAY_ACTIVE)
            ClimatePanelBits.describe(CarSignals.readInt(s.module, s.id)) // HVAC 9 bit alanı: ör. 1[front]
        else CarSignals.read(s.module, s.id)?.joinToString(",", "[", "]") ?: "null"

    /** 3 sn boyunca sinyalleri 250 ms'de bir okur; yalnızca değişimleri günlüğe yazar. */
    private fun watch(entry: WidgetWritePolicy.Entry, before: List<Pair<ComfortPlan.Signal, String>>) {
        val tracker = ComfortPlan.ChangeTracker()
        before.forEach { (s, v) -> tracker.observe(s.name, v) }
        val start = SystemClock.elapsedRealtime()
        var changes = 0
        while (true) {
            val t = SystemClock.elapsedRealtime() - start
            for (s in entry.readBack) {
                val v = describe(s)
                if (tracker.observe(s.name, v)) {
                    changes++
                    Log.i(WidgetWritePolicy.LOG_TAG, "widget=${entry.widget} readback +${t}ms ${s.name}=$v")
                }
            }
            if (t >= READBACK_MS) break
            SystemClock.sleep(READBACK_INTERVAL_MS)
        }
        if (changes == 0) Log.i(WidgetWritePolicy.LOG_TAG, "widget=${entry.widget} readback ${READBACK_MS}ms: no change")
    }
}
