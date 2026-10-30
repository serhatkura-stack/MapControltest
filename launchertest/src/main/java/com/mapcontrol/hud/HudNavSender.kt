package com.mapcontrol.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.HUDID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.launcher.CarSignals

/**
 * HUD'a (VDBus modülü 327683, CMD_ID 200) yazan TEK sınıf. Araca yazılan ilk koddur; bu yüzden dar tutuldu:
 *  - yalnızca [HudPayload.validate]'ten geçen diziler gider (beyaz liste: 3, 48, 49, 50, 51, 52, 53);
 *  - CMD_ID ve modül sabittir, çağıran veremez;
 *  - temizleme dışındaki her gönderimden hemen önce park kapısı ([HudParkGate]) ve servis bağlantısı denetlenir;
 *  - her gönderim `Log.i("HudNav", ...)` ile tam int[] olarak yazılır.
 *
 * Yalnızca HudTestActivity çağırır (adb ile başlatılır). Widget, ayar veya başka launcher arayüzü buna erişmez.
 *
 * Temizleme [3,1,1] park kapısından muaftır: ekrandaki bir HUD içeriğini kaldırmak yürürken de güvenlidir
 * ve test sonunda her koşulda çalışmalıdır. Yine de servis bağlı olmalıdır.
 */
internal class HudNavSender(context: Context) {

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

    /** Park kapısı + beyaz liste + gönderim. Reddedilirse hiçbir şey yazılmaz. */
    fun send(values: IntArray): Result {
        val checked = try {
            HudPayload.validate(values)
        } catch (e: IllegalArgumentException) {
            return refuse("geçersiz yük ${values.contentToString()}: ${e.message}")
        }
        if (!proxy.isServiceConnnected) return refuse("araç servisi bağlı değil")
        val isClear = checked[0] == HudPayload.SUB_CLEAR
        if (!isClear) {
            val decision = parkDecision()
            if (!decision.allowed) return refuse("park kapısı: ${decision.reason}")
        }
        return write(checked)
    }

    /** Temizle: `[3,1,1]`. Park kapısından muaf, servis bağlantısı gerekir. */
    fun clear(): Result = send(HudPayload.clear())

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

    private fun refuse(why: String): Result {
        Log.w(TAG, "REFUSED $why")
        return Result(false, "REDDEDİLDİ: $why")
    }

    /** Araca giden TEK çağrı: CarInfoProxy.sendItemValues -> VDBus.set(VDEvent(327683, {CMD_ID=200, VALUE})). */
    private fun write(values: IntArray): Result {
        Log.i(TAG, "SEND module=${HudPayload.MODULE_HUD} cmd=${HudPayload.CMD_ID} int[]=${values.contentToString()}")
        proxy.sendItemValues(HudPayload.MODULE_HUD, HudPayload.CMD_ID, values)
        return Result(true, "gönderildi ${values.contentToString()}")
    }

    companion object {
        const val TAG = "HudNav"

        /** Okuma yolu için HUD ayar kimlikleri (salt-okur kullanım: bkz. HudTestActivity bilgi modu). */
        val HUD_READ_IDS = intArrayOf(
            HUDID.ID_HUD_DISPLAY, HUDID.ID_HUD_HEIGHT, HUDID.ID_HUD_BRIGHTNESS, HUDID.ID_HUD_DISPLAY_MODE,
            HUDID.ID_HUD_STATE, HUDID.ID_HUD_NAVI_DISPLAY_POSI,
        )
    }
}
