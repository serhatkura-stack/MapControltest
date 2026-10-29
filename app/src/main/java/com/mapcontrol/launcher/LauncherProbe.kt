package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
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
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.client.bind.VDServiceDef
import com.desaysv.ivi.vdb.event.VDEvent
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.desaysv.ivi.vdb.event.id.carlan.VDEventCarLan
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
 * 3) Navigasyon: aynı süre boyunca CAR_LAN navigasyon olaylarını (yön, şerit, mesafe, gösterge/HUD alanı)
 *    2 sn'de bir OKUR, değişenleri yazar; HUD'a yön oklarının nasıl gittiğini anlamak için.
 * 4) Ekranlar: Android'in gördüğü ekranları (gösterge dahil) numara, boyut ve yoğunlukla yazar; izleme
 *    boyunca eklenen/değişen/kalkan ekranları da. Gösterge kartlarının ölçüsü buradan çıkacak.
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

    /** CAR_LAN navigasyon olayları: yön, şerit, toplam bilgi, gösterge/HUD alanı. */
    private val NAVI_EVENTS = intArrayOf(
        VDEventCarLan.NAVIGATION_LANE_INFO, VDEventCarLan.NAVIGATION_ROAD_INFO, VDEventCarLan.NAVIGATION_ROAD_INFO2,
        VDEventCarLan.NAVIGATION_TOTAL_INFO, VDEventCarLan.NAVIGATION_TOTAL_INFO2, VDEventCarLan.NAVIGATION_DIGITAL_INFO,
        VDEventCarLan.NAVIGATION_DISPLAY_TO_CLUSTER, VDEventCarLan.NAVIGATION_DISPLAY_AREA,
        VDEventCarLan.NAVIGATION_COMPASS_FUCTION, VDEventCarLan.NAVIGATION_AREA_DISPLAY_REQUEST,
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
        val displays = context.getSystemService(DisplayManager::class.java)
        displays.displays.forEach { Log.i(TAG, "DISPLAY ${describe(it)}") }
        val displayListener = displayListener(displays)
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        try {
            scanAndWatch(context, token)
        } finally {
            displays.unregisterDisplayListener(displayListener)
        }
    }

    private fun scanAndWatch(context: Context, token: Long) {
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
                // SDK'da bazı sabitler private; yalnızca değerini okumak için erişim açılır.
                val id = try {
                    f.isAccessible = true
                    f.getInt(null)
                } catch (e: ReflectiveOperationException) {
                    Log.w(TAG, "SCAN-SKIP $module.${f.name}: ${e.javaClass.simpleName}")
                    continue
                }
                val signal = Signal(module, f.name, moduleId, id)
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

        val navi = readNavigation()
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
            pollNavigation(navi)
        }
        Log.i(TAG, "WATCH-DONE")
    }

    /** Ekran eklenince, değişince (ör. gösterge navigasyon alanı açılınca) ya da kalkınca yazar. */
    private fun displayListener(displays: DisplayManager) = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = log("DISPLAY-ADD", displays.getDisplay(id))

        override fun onDisplayChanged(id: Int) = log("DISPLAY-CHANGE", displays.getDisplay(id))

        override fun onDisplayRemoved(id: Int) {
            Log.i(TAG, "DISPLAY-REMOVE id=$id")
        }

        private fun log(tag: String, d: Display?) {
            if (d != null) Log.i(TAG, "$tag ${describe(d)}")
        }
    }

    private fun describe(d: Display): String {
        val m = DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
        return "id=${d.displayId} ad=\"${d.name}\" ${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} " +
            "durum=${d.state} bayrak=${d.flags} yenileme=${d.refreshRate}"
    }

    private fun read(s: Signal): String? = CarSignals.read(s.moduleId, s.id)?.joinToString(",", "[", "]")

    /**
     * Navigasyon olaylarının son durumunu bir kez okur. Abonelik kullanılmaz: SDK'daki
     * VDConnector.subscribeCommit derlenmemiş (araçta UnsupportedOperationException), o yüzden
     * izleme boyunca [pollNavigation] ile 2 sn'de bir yeniden okunur. Göndermez.
     */
    private fun readNavigation(): MutableMap<Int, String> {
        val last = HashMap<Int, String>()
        try {
            VDBus.getDefault().bindService(VDServiceDef.ServiceType.CAR_LAN)
        } catch (e: RuntimeException) {
            Log.w(TAG, "NAVI-FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
        for (id in NAVI_EVENTS) {
            val now = readNavi(id) ?: continue
            last[id] = now
            Log.i(TAG, "NAVI-NOW $id $now")
        }
        return last
    }

    private fun pollNavigation(last: MutableMap<Int, String>) {
        for (id in NAVI_EVENTS) {
            val now = readNavi(id) ?: continue
            if (now != last[id]) {
                Log.i(TAG, "NAVI-CHANGE $id $now")
                last[id] = now
            }
        }
    }

    private fun readNavi(id: Int): String? = try {
        VDBus.getDefault().getOnce(VDEvent(id))?.let { describe(it) }
    } catch (e: RuntimeException) {
        null
    }

    /** Olay içeriğinin kısa özeti: anahtarlar ve değerler (Parcelable'lar toString ile), 400 karakter. */
    private fun describe(event: VDEvent): String = try {
        val payload = event.payload
        if (payload == null) "{}" else describePayload(payload)
    } catch (e: RuntimeException) {
        "okunamadı: ${e.javaClass.simpleName}"
    }

    private fun describePayload(payload: android.os.Bundle): String {
        payload.classLoader = LauncherProbe::class.java.classLoader
        return payload.keySet().joinToString(", ", "{", "}") { k ->
            val v = payload.get(k)
            "$k=" + when (v) {
                is IntArray -> v.joinToString(",", "[", "]")
                is ByteArray -> "bytes(${v.size})"
                else -> v.toString()
            }
        }.take(400)
    }
}
