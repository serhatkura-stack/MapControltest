package com.mapcontrol.comfort

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.hud.HudParkGate
import com.mapcontrol.launcher.CarSignals

/**
 * Konfor testinde araca yazan TEK sınıf (tavan perdesi, buğu giderme, direksiyon ısıtma). Dar tutuldu:
 *  - yalnızca [ComfortPlan.ALLOWED] beyaz listesindeki (modül, kimlik, değer) üçlüleri gider;
 *  - HER yazmadan hemen önce park kapısı ([HudParkGate]: gösterge hızı 119 == 0, eski hız 0 == 0) ve servis bağlantısı denetlenir;
 *  - her gönderim `Log.i("ComfortTest", ...)` ile yazılır.
 * Yalnızca ComfortTestActivity çağırır (adb ile başlatılır). Okuma ([read]) salt-okurdur.
 */
internal class ComfortSender(context: Context) {

    class Result(val sent: Boolean, val message: String)

    private val appContext = context.applicationContext
    private val proxy = CarInfoProxy.getInstance()

    /** CarInfo servisine bağlanır (en çok [waitMs] bekler). Bağlıysa true. */
    fun connect(waitMs: Long = 3000): Boolean {
        if (!proxy.isServiceConnnected) proxy.init(appContext)
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (!proxy.isServiceConnnected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        return proxy.isServiceConnnected
    }

    /** Beyaz liste + servis bağlantısı + park kapısı + gönderim. Reddedilirse hiçbir şey yazılmaz. */
    fun send(write: ComfortPlan.Write): Result {
        val checked = try {
            ComfortPlan.validate(write)
        } catch (e: IllegalArgumentException) {
            return refuse(e.message ?: "geçersiz yazma")
        }
        if (!proxy.isServiceConnnected) return refuse("araç servisi bağlı değil")
        val decision = parkDecision()
        if (!decision.allowed) return refuse("park kapısı: ${decision.reason}")
        return write(checked)
    }

    /** Park kapısı kararı; ham değerler (vites, el freni) bilgi için günlüğe yazılır. */
    fun parkDecision(): HudParkGate.Decision {
        val ro = VDEventCarInfo.MODULE_READONLY_INFO
        val gauge = CarSignals.readInt(ro, ReadOnlyID.ID_SPEED_GAUGE_DISPLAY)
        val legacy = CarSignals.readInt(ro, ReadOnlyID.ID_CAR_SPEED)
        val gear = CarSignals.readInt(ro, ReadOnlyID.ID_GEARBOX_STATE)
        val brake = CarSignals.readInt(ro, ReadOnlyID.ID_HANDBRAKE_STATE)
        val d = HudParkGate.evaluate(gauge, legacy)
        Log.i(TAG, "GATE speed119=$gauge speed0=$legacy gear26=$gear handbrake75=$brake -> ${d.allowed} (${d.reason})")
        return d
    }

    /** SALT-OKUR: sinyalin ham değeri (okunamazsa null). */
    fun read(signal: ComfortPlan.Signal): IntArray? = CarSignals.read(signal.module, signal.id)

    private fun refuse(why: String): Result {
        Log.w(TAG, "REFUSED $why")
        return Result(false, "REDDEDİLDİ: $why")
    }

    /** Araca giden TEK çağrı: CarInfoProxy.sendItemValue(modül, kimlik, değer). */
    private fun write(w: ComfortPlan.Write): Result {
        Log.i(TAG, "SEND ${w}")
        proxy.sendItemValue(w.module, w.id, w.value)
        return Result(true, "gönderildi $w")
    }

    companion object {
        const val TAG = "ComfortTest"
    }
}
