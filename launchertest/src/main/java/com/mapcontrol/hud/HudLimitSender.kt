package com.mapcontrol.hud

import android.util.Log
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy

/**
 * Hız limiti tabelası (modül 327682, komut 123/125) için araca yazan TEK sınıf; HudNavSender'ın kardeşi.
 *  - yalnızca [HudLimitPayload.validate]'ten geçen yükler gider (beyaz liste: 327682 / 123 ve 125);
 *  - her gönderimden hemen önce servis bağlantısı ve park kapısı ([HudNavSender.parkDecision]) denetlenir;
 *    temizleme dahil muaf yoktur (bu yol yeni ve ölçülmemiş);
 *  - her gönderim `Log.i("HudNav", ...)` ile tam int[] olarak yazılır.
 * Yalnızca HudTestActivity çağırır (adb ile başlatılır).
 */
internal class HudLimitSender(private val nav: HudNavSender) {

    private val proxy = CarInfoProxy.getInstance()

    /** Beyaz liste + servis + park kapısı + gönderim. Reddedilirse hiçbir şey yazılmaz. */
    fun send(p: HudLimitPayload.Payload): HudNavSender.Result {
        val checked = try {
            HudLimitPayload.validate(p.module, p.cmd, p.values)
        } catch (e: IllegalArgumentException) {
            return refuse("geçersiz yük ${p.values.contentToString()}: ${e.message}")
        }
        if (!proxy.isServiceConnnected) return refuse("araç servisi bağlı değil")
        val decision = nav.parkDecision()
        if (!decision.allowed) return refuse("park kapısı: ${decision.reason}")
        return write(checked)
    }

    private fun refuse(why: String): HudNavSender.Result {
        Log.w(HudNavSender.TAG, "REFUSED $why")
        return HudNavSender.Result(false, "REDDEDİLDİ: $why")
    }

    /** Araca giden TEK çağrı: CarInfoProxy.sendItemValues(327682, 123|125, int[]). */
    private fun write(p: HudLimitPayload.Payload): HudNavSender.Result {
        Log.i(HudNavSender.TAG, "SEND module=${p.module} cmd=${p.cmd} int[]=${p.values.contentToString()}")
        proxy.sendItemValues(p.module, p.cmd, p.values)
        return HudNavSender.Result(true, "gönderildi ${p.cmd}: ${p.values.contentToString()}")
    }
}
