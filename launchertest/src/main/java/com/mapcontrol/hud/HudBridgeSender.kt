package com.mapcontrol.hud

import android.content.Context
import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy

/**
 * Yandex -> HUD köprüsünün araca yazan TEK sınıfı (HudNavSender'dan ayrı; o yalnızca HudTestActivity içindir).
 *  - Yalnızca {3, 52, 48, 50, 53} alt komutları ve yalnızca [HudPayload.validate]'ten geçenler gider.
 *  - Modül ve CMD_ID sabittir, çağıran veremez.
 *  - Park kapısı YOK: köprü yalnızca sürüş sırasında gösterge amaçlıdır. Hız sınırı ([HudRateLimiter]) motorda.
 *  - Her gönderim `Log.i("HudBridge", ...)` ile tam int[] olarak yazılır.
 */
internal class HudBridgeSender(context: Context) {

    private val appContext = context.applicationContext
    private val proxy = CarInfoProxy.getInstance()

    /** Servis bağlı değilse bağlanmayı başlatır, beklemez. Bağlıysa true. */
    fun ensureConnected(): Boolean {
        if (!proxy.isServiceConnnected) proxy.init(appContext)
        return proxy.isServiceConnnected
    }

    /** Doğrula + gönder. Reddedilir ya da servis yoksa false (hiçbir şey yazılmaz). */
    fun send(values: IntArray): Boolean {
        val checked = try {
            HudBridgeWhitelist.check(values)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "REFUSED ${values.contentToString()}: ${e.message}")
            return false
        }
        if (!ensureConnected()) {
            Log.w(TAG, "REFUSED araç servisi bağlı değil: ${checked.contentToString()}")
            return false
        }
        Log.i(TAG, "SEND module=${HudPayload.MODULE_HUD} cmd=${HudPayload.CMD_ID} int[]=${checked.contentToString()}")
        proxy.sendItemValues(HudPayload.MODULE_HUD, HudPayload.CMD_ID, checked)
        return true
    }

    companion object {
        const val TAG = "HudBridge"
    }
}
