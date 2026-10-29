package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.AmbientLightID
import com.desaysv.ivi.extra.project.carinfo.AvmID
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.DVRID
import com.desaysv.ivi.extra.project.carinfo.HUDID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.MeterMalfunctionID
import com.desaysv.ivi.extra.project.carinfo.NewEnergyID
import com.desaysv.ivi.extra.project.carinfo.RadarID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.RvcID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import java.lang.reflect.Modifier

/**
 * Geliştirme aracı: aracın hangi sinyalleri verdiğini çıkarır. YALNIZCA OKUR (VDBus.getOnce);
 * hiçbir komut göndermez. Debug derlemede ve açıkça istenirse çalışır:
 *   adb shell am start -n <paket>/com.mapcontrol.launcher.HomeLauncherActivity --ez launcher_probe true
 *
 * 1) Tarama: SDK'daki her ID sınıfının sabitlerini kendi modülünden bir kez okur; cevap verenleri
 *    "LauncherProbe" etiketiyle loga yazar (cevapsızlar yalnızca sayılır).
 * 2) İzleme: cevap veren sinyalleri [WATCH_MS] boyunca [POLL_MS]'de bir yeniden okur, yalnızca
 *    değişenleri yazar. Sürücü kapıyı açar, klimayı değiştirir; hangi sinyalin karşılık geldiği görülür.
 */
internal object LauncherProbe {

    const val EXTRA = "launcher_probe"
    private const val TAG = "LauncherProbe"
    private const val CONNECT_WAIT_MS = 8_000L
    private const val POLL_MS = 2_000L
    private const val WATCH_MS = 15 * 60_000L

    /** Okunacak ID sınıfları ve modülleri. Teşhis/OTA/hesap modülleri bilerek yok. */
    private val SOURCES: List<Pair<Class<*>, Int>> = listOf(
        ReadOnlyID::class.java to VDEventCarInfo.MODULE_READONLY_INFO,
        CarSettingID::class.java to VDEventCarInfo.MODULE_CAR_SETTING,
        HvacID::class.java to VDEventCarInfo.MODULE_HVAC,
        NewEnergyID::class.java to VDEventCarInfo.MODULE_NEW_ENERGY,
        HUDID::class.java to VDEventCarInfo.MODULE_HUD,
        RadarID::class.java to VDEventCarInfo.MODULE_RADAR,
        AvmID::class.java to VDEventCarInfo.MODULE_AVM,
        DVRID::class.java to VDEventCarInfo.MODULE_DVR,
        MeterMalfunctionID::class.java to VDEventCarInfo.MODULE_METER_MALFUNCTION,
        AmbientLightID::class.java to VDEventCarInfo.MODULE_AMBIENT_LIGHT,
        RvcID::class.java to VDEventCarInfo.MODULE_RVC,
    )

    private class Signal(val module: String, val name: String, val moduleId: Int, val id: Int) {
        var last: String? = null
        override fun toString() = "$module.$name($id)"
    }

    @Volatile
    private var runToken = 0L

    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA, false) != true) return
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        start(activity.applicationContext)
    }

    private fun start(context: Context) {
        val token = SystemClock.elapsedRealtime()
        runToken = token
        Thread({ run(context, token) }, TAG).apply { isDaemon = true }.start()
    }

    private fun run(context: Context, token: Long) {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) proxy.init(context)
        val deadline = SystemClock.elapsedRealtime() + CONNECT_WAIT_MS
        while (!proxy.isServiceConnnected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        if (!proxy.isServiceConnnected) {
            Log.w(TAG, "SCAN-FAIL araç servisi bağlanmadı")
            return
        }

        val live = ArrayList<Signal>()
        var silent = 0
        for ((cls, moduleId) in SOURCES) {
            val module = cls.simpleName.removeSuffix("ID")
            var answered = 0
            for (f in cls.declaredFields) {
                if (f.type != Int::class.javaPrimitiveType || !Modifier.isStatic(f.modifiers)) continue
                val signal = Signal(module, f.name, moduleId, f.getInt(null))
                val value = read(signal)
                if (value == null) {
                    silent++
                } else {
                    signal.last = value
                    live += signal
                    answered++
                    Log.i(TAG, "SCAN $signal = $value")
                }
            }
            Log.i(TAG, "SCAN-MODULE $module cevap=$answered")
        }
        Log.i(TAG, "SCAN-DONE cevap=${live.size} sessiz=$silent; izleme ${WATCH_MS / 60_000} dk")

        val end = SystemClock.elapsedRealtime() + WATCH_MS
        while (runToken == token && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(POLL_MS)
            for (s in live) {
                val value = read(s) ?: continue
                if (value != s.last) {
                    Log.i(TAG, "CHANGE $s ${s.last} -> $value")
                    s.last = value
                }
            }
        }
        Log.i(TAG, "WATCH-DONE")
    }

    private fun read(s: Signal): String? = CarSignals.read(s.moduleId, s.id)?.joinToString(",", "[", "]")
}
