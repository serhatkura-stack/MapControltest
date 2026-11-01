package com.mapcontrol.launcher

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.event.VDEvent
import com.desaysv.ivi.vdb.event.id.dsp.VDEventDsp
import com.mapcontrol.R

/** Ses alanı: sol-sağ dengesi ve ön-arka dengesi (fader); okunamayan alan null. */
internal data class SoundFieldReading(val balance: Int? = null, val fade: Int? = null)

/**
 * Arka hoparlör kapatmanın saf kuralları (JVM'de sınanır). Arka hoparlör doğrudan kapatılamaz: ön-arka dengesi (fader) ön uca
 * çekilir, eski değer saklanır ve ikinci dokunuşta geri verilir.
 *
 * Kanıt: aracın kendi Ayarlar uygulaması (SVSetting `SoundSettingModel`): `setSoundFiled(balance, fade)` düz bir Bundle'a
 * `"type"` = balance, `"value"` = fade yazar ve `VDBus.set(VDEvent(VDEventDsp.SOUND_FIELD, bundle))` gönderir; `getBalance()` /
 * `getFade()` düz `getOnce(SOUND_FIELD)` payload'ından `getInt("type")` / `getInt("value")` okur (yedek: sistem özellikleri,
 * varsayılan 10). Orta 10/10, OEM sıfırla (10,10), sürücü ön ayarı (7,7). Her yazma İKİ değeri birlikte taşır: dengeyi korumak için
 * okunan balance aynen geri yazılır. Ön uç değeri ([FRONT_EXTREME]) ARAÇTA DOĞRULANMADI.
 * Gelecekte denenecek: `VDEventDsp.REAR_MUTE` (983045) doğrudan arka sessize alma olabilir; anlamı bilinmiyor, kullanılmıyor.
 */
internal object SoundFieldMath {

    /** Beyaz liste kimliği: SOUND_FIELD tek olaydır; kimlik yalnızca "fader yazması" kaydı için sabit 0'dır. */
    const val WRITE_ID = 0

    /** Orta konum (HAL günlüğü: 10/10) ve olası üst sınır (`soundFieldMax` 20 olabilir; doğrulanmadı). */
    const val CENTER = 10
    const val MAX = 20

    /**
     * Arka hoparlörleri susturan uç değer: ön uç. TODO (araç testi): 0 mı 20 mi doğrulanmalı. OEM "sürücü" ön ayarı 7/7 olduğu için
     * küçük değerin sol/ön olması bekleniyor ama kanıtlanmadı. Yanlışsa yalnızca bu sabit değişir.
     */
    const val FRONT_EXTREME = 0

    /** Aralık dışı ve geçersiz değerler null. */
    fun valid(v: Int?): Int? = v?.takeIf { it in 0..MAX }

    /** true: arka hoparlör kapalı (fader ön uçta); okunamadıysa null. */
    fun rearMuted(fade: Int?): Boolean? = valid(fade)?.let { it == FRONT_EXTREME }

    /**
     * Dokunuşun yazacağı fader: açıksa ön uç, kapalıysa [remembered] (geçersiz ya da uçla aynıysa [CENTER]). Okunamadıysa null (yazılmaz).
     */
    fun targetFade(currentFade: Int?, remembered: Int?): Int? {
        val muted = rearMuted(currentFade) ?: return null
        if (!muted) return FRONT_EXTREME
        return valid(remembered)?.takeIf { it != FRONT_EXTREME } ?: CENTER
    }
}

/** DSP servisinden ses alanı okuma/yazma (VDBus, düz Bundle: "type" = balance, "value" = fade). Hatalarda null / sessiz; çökmez. */
internal object SoundFieldIo {

    private var lastLogged: Pair<Int?, Int?>? = null

    /** Güncel (balance, fade); DSP yoksa (emülatör) ya da payload boşsa null. */
    fun read(): SoundFieldReading? = try {
        val payload = VDBus.getDefault().getOnce(VDEventDsp.SOUND_FIELD)?.payload
        val balance = SoundFieldMath.valid(payload?.takeIf { it.containsKey(KEY_BALANCE) }?.getInt(KEY_BALANCE))
        val fade = SoundFieldMath.valid(payload?.takeIf { it.containsKey(KEY_FADE) }?.getInt(KEY_FADE))
        if (lastLogged != balance to fade) {
            lastLogged = balance to fade
            Log.i("RearSpeaker", "SOUND_FIELD read balance=$balance fade=$fade")
        }
        if (balance == null && fade == null) null else SoundFieldReading(balance, fade)
    } catch (e: RuntimeException) {
        null
    }

    fun write(balance: Int, fade: Int) {
        Log.i("RearSpeaker", "SOUND_FIELD write balance=$balance fade=$fade")
        val bundle = android.os.Bundle().apply {
            putInt(KEY_BALANCE, balance)
            putInt(KEY_FADE, fade)
        }
        VDBus.getDefault().set(VDEvent(VDEventDsp.SOUND_FIELD, bundle))
    }

    private const val KEY_BALANCE = "type"
    private const val KEY_FADE = "value"
}

/** Arka hoparlör kutucuğunun saklanan "eski fader" değeri (araç yeniden başlasa da kalır). */
internal class RearSpeakerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launcher_rear_speaker", Context.MODE_PRIVATE)

    var restoreFade: Int?
        get() = if (prefs.contains(KEY)) prefs.getInt(KEY, SoundFieldMath.CENTER) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putInt(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "restore_fade"
    }
}

/** Ses alanını okur (yalnızca okuma; arka plan thread'inde). */
internal fun ComfortReader.readSound(): ComfortReading {
    if (LauncherDemo.enabled) return LauncherDemo.comfort()
    val sound = SoundFieldIo.read()?.takeIf { it.fade != null } ?: return ComfortReading()
    return ComfortReading(connected = true, sound = sound)
}

/**
 * Arka hoparlör: tek dokunuşla arka hoparlörleri kapatır (fader ön uca), tekrar dokununca eski değeri geri verir. Yazma yalnızca
 * [ComfortWriter.rearSpeakerEnabled] ise gider (doğrulanana dek bilgi mesajı).
 */
internal class RearSpeakerTile(context: Context) : ComfortTile(context) {

    private val store = RearSpeakerStore(context)

    /** İyimser durum: true arka kapalı. */
    private var pendingMuted: Boolean? = null

    private val iconView = icon(context, R.drawable.ic_mdi_volume_high, ICON_DP, LauncherTheme.TEXT_SECONDARY)
    private val title = text(context, TITLE_SP, LauncherTheme.TEXT_SECONDARY).apply {
        setText(R.string.launcher_widget_rear_speaker)
        theme.bind { setTextColor(LauncherTheme.TEXT_SECONDARY) }
    }
    private val stateText = text(context, STATE_SP, LauncherTheme.TEXT, medium = true).apply { gravity = Gravity.CENTER }

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, PADDING_DP).apply {
        gravity = Gravity.CENTER
        theme.bind { background = cardSurface(context) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(iconView)
            addView(gap(context, 8))
            addView(title)
        })
        addView(gap(context, 6, vertical = true))
        addView(stateText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        pressFeedback()
    }

    override val view: View get() = root

    override fun readNow(): ComfortReading = ComfortReader.readSound()

    override fun applySpan(span: Span) = Unit

    override fun clearPending() { pendingMuted = null }

    private fun muted(): Boolean? =
        pendingMuted ?: SoundFieldMath.rearMuted(reading.sound?.fade).takeIf { reading.connected }

    override fun render() {
        val muted = muted()
        val state = context.getString(
            when (muted) {
                null -> R.string.launcher_unavailable
                true -> R.string.launcher_comfort_off
                false -> R.string.launcher_comfort_on
            },
        )
        stateText.setTextSmooth(state)
        stateText.setTextColor(if (muted == true) LauncherTheme.ACCENT else if (muted == false) LauncherTheme.TEXT else LauncherTheme.TEXT_SECONDARY)
        iconView.setTint(if (muted == true) LauncherTheme.ACCENT else LauncherTheme.TEXT_SECONDARY)
        root.contentDescription = context.getString(R.string.launcher_widget_rear_speaker) + ": " + state
        root.isEnabled = muted != null || LauncherEdition.READ_ONLY
    }

    private fun onTap() {
        val fade = SoundFieldMath.valid(reading.sound?.fade)
        val target = SoundFieldMath.targetFade(fade, store.restoreFade)
        // Okunamıyorsa (test sürümü dışında) tahmin etme.
        if (target == null && !LauncherEdition.READ_ONLY) return
        val enabled = ComfortWriter.rearSpeakerEnabled
        if (enabled && target != null) {
            val nowMuted = muted() == true
            if (!nowMuted && fade != null) store.restoreFade = fade
            pendingMuted = !nowMuted
            io.runSafely { ComfortWriter.setFader(SoundFieldMath.valid(reading.sound?.balance) ?: SoundFieldMath.CENTER, target) }
        }
        afterCommit(enabled)
    }

    private companion object {
        const val PADDING_DP = 12
        const val ICON_DP = 24
        const val TITLE_SP = 18f
        const val STATE_SP = 40f
    }
}
