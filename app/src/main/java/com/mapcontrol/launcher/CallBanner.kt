package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Arama şeridi: ekranın üstünde ortada, cam bir şerit. Yalnızca bir arama varken görünür ([PhoneUiLogic.bannerMode]):
 *  - Gelen arama: arayanın adı (yoksa numarası), "Gelen arama"; sağda iki büyük düğme, solda kırmızı **Reddet**, sağda
 *    yeşil **Cevapla** (≥ 96dp).
 *  - Aranıyor: ad/numara, "Aranıyor…" ve kırmızı **Kapat**.
 *  - Konuşma: ad, süre (mm:ss), **Sessiz** (yol yoksa gizli) ve **Kapat**.
 *  - Bitti: "Arama bitti" (arka uç ~2 sn sonra NONE yapar, şerit kapanır).
 * Aranıyor ve konuşma kipinde şerit kompakt bir hapa küçülür (başlık bandı yüksekliğinde, en çok [COMPACT_MAX_W_DP]
 * genişliğinde, düğmeler 76dp): dakikalarca süren konuşmada başlığı, Sahne panellerini ya da spor göstergelerini
 * örtmesin. Yalnızca gelen arama büyük düzeni kullanır.
 * Tek zamanlayıcı konuşma süresidir: yalnızca konuşma sürerken ve şerit görünürken saniyede bir, yazı değişince.
 * OEM telefon uygulaması kendi ekranını da gösterebilir; bu şerit onu bastırmaz, tamamlar.
 *
 * Yalnızca ana ekran görünürken dinler ([onVisible]/[onHidden]). İsim/numara günlüğe yazılmaz.
 */
internal class CallBanner(
    private val activity: Activity,
    /** Gelen arama şeridi göründüğünde (ana iş parçacığı): etkinlik üstteki sayfaları kapatır ki düğmeler örtülmesin. */
    private val onIncoming: (() -> Unit)? = null,
) : LauncherWidget, PhoneListener {

    private val context: Context = activity
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var call: CallState = CallState.IDLE
    private var canMute = false
    private var mode = PhoneUiLogic.BannerMode.HIDDEN
    /** Kurulu düğme dizisi (kip ya da Sessiz görünürlüğü değişince yeniden kurulur). */
    private var builtFor: Pair<PhoneUiLogic.BannerMode, Boolean>? = null
    private var muteButton: LinearLayout? = null
    private var compact = false
    private val actionButtons = ArrayList<View>()

    private val title = sahneText(context, TITLE_SP, Sahne.INK, 600).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val sub = sahneText(context, SUB_SP, Sahne.INK_3, 400).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(28), context.dp(14), context.dp(14), context.dp(14))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(20)
        })
        // Şeride dokunuş alttaki ekrana geçmez.
        isClickable = true
    }

    /** Genişlik en çok [MAX_W_DP], ekranda her yandan 24dp pay; üst-orta hizası ebeveynin (FrameLayout) yerleşim kuralından. */
    private val frame = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentW = MeasureSpec.getSize(widthMeasureSpec)
            val w = minOf(context.dp(if (compact) COMPACT_MAX_W_DP else MAX_W_DP), parentW - context.dp(2 * SIDE_DP)).coerceAtLeast(0)
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    override val view: View get() = frame

    init { applyTheme() }

    /** Tema değişti: cam ve düğmeler yeni jetonlarla yeniden kurulur. */
    fun applyTheme() {
        card.background = GlassDrawable(context, Sahne.PANEL_RADIUS_DP, control = true)
        card.elevation = context.dpF(ELEVATION_DP)
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) =
                outline.setRoundRect(0, 0, v.width, v.height, context.dpF(Sahne.PANEL_RADIUS_DP))
        }
        title.setTextColor(Sahne.INK)
        sub.setTextColor(Sahne.INK_3)
        builtFor = null
        if (mode != PhoneUiLogic.BannerMode.HIDDEN) bindCall()
    }

    // --- Yaşam döngüsü ---

    override fun onVisible() {
        if (visible) return
        visible = true
        // Şerit yalnızca arama durumunu ister: rehber/geçmiş okunmaz.
        PhoneSource.start(activity, this, wantsData = false)
        PhoneSource.addListener(this)
    }

    override fun onHidden() {
        if (!visible) return
        visible = false
        PhoneSource.removeListener(this)
        PhoneSource.stop(this)
        main.removeCallbacks(timer)
        main.removeCallbacks(unlock)
        // Kilit kalkar ve düğmeler yeniden kurulur: dönüşte aynı kipteki Cevapla/Reddet/Kapat ölü kalmasın.
        setLocked(false)
        builtFor = null
        frame.animate().cancel()
        frame.visibility = View.GONE
        mode = PhoneUiLogic.BannerMode.HIDDEN
        call = CallState.IDLE
    }

    override fun release() = onHidden()

    override fun onPhoneChanged(snapshot: PhoneSnapshot) {
        if (!visible) return
        val next = snapshot.call
        val muteChanged = snapshot.canMute != canMute
        if (next == call && !muteChanged) return
        call = next
        canMute = snapshot.canMute
        bindCall()
    }

    override fun onPhoneError(error: PhoneError) {
        if (!visible || mode == PhoneUiLogic.BannerMode.HIDDEN || !frame.isAttachedToWindow) return
        PhoneToast.show(context, PhoneUiLogic.errorRes(error))
    }

    // --- Bağlama ---

    private fun bindCall() {
        val next = PhoneUiLogic.bannerMode(call)
        val was = mode
        mode = next
        if (next == PhoneUiLogic.BannerMode.HIDDEN) {
            main.removeCallbacks(timer)
            if (was != PhoneUiLogic.BannerMode.HIDDEN) hide()
            return
        }
        val name = call.name?.trim()?.takeIf { it.isNotEmpty() }
        val number = call.number?.trim()?.takeIf { it.isNotEmpty() }
        setTextIfChanged(title, name ?: number.orEmpty())
        val key = next to (next == PhoneUiLogic.BannerMode.ACTIVE && canMute)
        if (key != builtFor) {
            builtFor = key
            applyCompact(next == PhoneUiLogic.BannerMode.ACTIVE || next == PhoneUiLogic.BannerMode.DIALING)
            buildButtons(next)
        }
        when (next) {
            PhoneUiLogic.BannerMode.INCOMING -> setTextIfChanged(sub,
                listOfNotNull(context.getString(R.string.launcher_call_incoming), if (name != null) number else null).joinToString(" · "))
            PhoneUiLogic.BannerMode.DIALING -> setTextIfChanged(sub, context.getString(R.string.launcher_call_dialing))
            PhoneUiLogic.BannerMode.ACTIVE -> tickTimer()
            PhoneUiLogic.BannerMode.ENDED -> setTextIfChanged(sub, context.getString(R.string.launcher_call_ended))
            PhoneUiLogic.BannerMode.HIDDEN -> Unit
        }
        if (next != PhoneUiLogic.BannerMode.ACTIVE) main.removeCallbacks(timer)
        muteButton?.let { bindMute(it) }
        card.contentDescription = listOf(title.text, sub.text).filter { it.isNotEmpty() }.joinToString(", ")
        if (was == PhoneUiLogic.BannerMode.HIDDEN) show()
        if (next == PhoneUiLogic.BannerMode.INCOMING && was != PhoneUiLogic.BannerMode.INCOMING) onIncoming?.invoke()
    }

    /** Kompakt hap (aranıyor/konuşma) ya da büyük gelen arama düzeni. */
    private fun applyCompact(on: Boolean) {
        if (on == compact) return
        compact = on
        val v = context.dp(if (on) COMPACT_PAD_DP else 14)
        card.setPadding(context.dp(if (on) 24 else 28), v, v, v)
        title.textSize = if (on) COMPACT_TITLE_SP else TITLE_SP
        sub.textSize = if (on) COMPACT_SUB_SP else SUB_SP
        frame.requestLayout()
    }

    private fun buildButtons(m: PhoneUiLogic.BannerMode) {
        buttons.removeAllViews()
        actionButtons.clear()
        muteButton = null
        main.removeCallbacks(unlock)
        val big = m == PhoneUiLogic.BannerMode.INCOMING
        val w = context.dp(if (big) BIG_DP else COMPACT_W_DP)
        val h = context.dp(if (big) BIG_DP else COMPACT_H_DP)
        fun add(v: View, first: Boolean) =
            buttons.addView(v, LinearLayout.LayoutParams(w, h).apply { if (!first) marginStart = context.dp(if (big) 14 else 8) })
        when (m) {
            PhoneUiLogic.BannerMode.INCOMING -> {
                // Reddet solda (kırmızı), Cevapla sağda (yeşil): telefonlardaki alışılmış yerleşim.
                add(callButton(R.drawable.ic_sahne_call_end, R.string.launcher_call_decline, Sahne.CALL_REJECT) { act { PhoneSource.reject() } }, true)
                add(callButton(R.drawable.ic_sahne_call, R.string.launcher_call_answer, Sahne.CALL_ACCEPT) { act { PhoneSource.answer() } }, false)
            }
            PhoneUiLogic.BannerMode.DIALING ->
                add(callButton(R.drawable.ic_sahne_call_end, R.string.launcher_call_hangup, Sahne.CALL_REJECT) { act { PhoneSource.hangUp() } }, true)
            PhoneUiLogic.BannerMode.ACTIVE -> {
                if (canMute) add(callButton(R.drawable.ic_sahne_mic_off, R.string.launcher_call_mute, Sahne.SOFT_FILL, Sahne.INK) {
                    PhoneSource.toggleMute()
                }.also { muteButton = it }, true)
                add(callButton(R.drawable.ic_sahne_call_end, R.string.launcher_call_hangup, Sahne.CALL_REJECT) { act { PhoneSource.hangUp() } }, !canMute)
            }
            PhoneUiLogic.BannerMode.ENDED, PhoneUiLogic.BannerMode.HIDDEN -> Unit
        }
        buttons.visibility = if (buttons.childCount == 0) View.GONE else View.VISIBLE
    }

    /** Sessiz düğmesi: kapalıyken yumuşak dolgu + mürekkep ikon; açıkken mürekkep dolgu + zemin renginde ikon (net "açık" hâli). */
    private fun bindMute(b: LinearLayout) {
        val on = call.muted
        val fill = if (on) Sahne.INK else Sahne.SOFT_FILL
        val ink = if (on) Sahne.GLASS_BOTTOM else Sahne.INK
        b.background = rounded(context, fill, BUTTON_RADIUS_DP, if (on) 0 else Sahne.HAIR_2)
        (b.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(ink)
        (b.getChildAt(1) as android.widget.TextView).setTextColor(ink)
        b.isSelected = on
    }

    /** Cevapla/Reddet/Kapat: istek gönderilince düğmeler [LOCK_MS] kilitlenir (çift dokunuş iki istek olmasın). */
    private fun act(action: () -> Boolean) {
        if (actionButtons.any { !it.isEnabled }) return
        setLocked(true)
        main.postDelayed(unlock, LOCK_MS)
        action()
    }

    private val unlock = Runnable { setLocked(false) }

    private fun setLocked(locked: Boolean) = actionButtons.forEach {
        it.isEnabled = !locked
        it.alpha = if (locked) LOCKED_ALPHA else 1f
    }

    private fun callButton(@DrawableRes icon: Int, @StringRes label: Int, fill: Int, ink: Int = Sahne.CALL_ON_INK, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(context, fill, BUTTON_RADIUS_DP, if (fill == Sahne.SOFT_FILL) Sahne.HAIR_2 else 0)
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)))
            addView(sahneText(context, LABEL_SP, ink, 600).apply {
                setText(label)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(4)
            })
            val who = title.text?.toString().orEmpty()
            contentDescription = if (who.isEmpty()) context.getString(label) else context.getString(label) + ", " + who
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressFeedback()
            if (icon != R.drawable.ic_sahne_mic_off) actionButtons += this
        }

    // --- Süre ---

    private val timer = Runnable { tickTimer() }

    /** Süreyi yazar ve bir sonraki saniye sınırına kurar (yalnız konuşma sürerken ve şerit görünürken). */
    private fun tickTimer() {
        main.removeCallbacks(timer)
        if (!visible || mode != PhoneUiLogic.BannerMode.ACTIVE) return
        val started = call.startedAtMs
        val elapsed = if (started > 0L) SystemClock.elapsedRealtime() - started else 0L
        setTextIfChanged(sub, PhoneUiLogic.formatDuration(elapsed))
        main.postDelayed(timer, 1_000L - (elapsed.coerceAtLeast(0L) % 1_000L))
    }

    // --- Gösterme / gizleme (180 ms kaydırma + saydamlık; başka kare işi yok) ---

    private fun show() {
        frame.animate().cancel()
        frame.visibility = View.VISIBLE
        frame.alpha = 0f
        frame.translationY = -context.dpF(SLIDE_DP)
        frame.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start()
    }

    private fun hide() {
        frame.animate().cancel()
        frame.animate().alpha(0f).translationY(-context.dpF(SLIDE_DP)).setDuration(ANIM_MS).withEndAction {
            if (mode == PhoneUiLogic.BannerMode.HIDDEN) {
                frame.visibility = View.GONE
                frame.translationY = 0f
                builtFor = null
            }
        }.start()
    }

    private companion object {
        const val MAX_W_DP = 900
        const val SIDE_DP = 24
        const val ELEVATION_DP = 12f
        const val TITLE_SP = 24f
        const val SUB_SP = 18f
        const val LABEL_SP = 15f
        const val ICON_DP = 32
        /** Gelen aramada Reddet/Cevapla kare düğmeleri (≥ 96dp). */
        const val BIG_DP = 104
        /** Aranıyor/konuşma hapındaki düğmeler (≥ 76dp dokunma alanı). */
        const val COMPACT_W_DP = 104
        const val COMPACT_H_DP = 76
        /** Kompakt hap: 4 + 76 + 4 = 84dp (başlık bandı 72dp + üst pay 12dp). */
        const val COMPACT_PAD_DP = 4
        const val COMPACT_MAX_W_DP = 640
        const val COMPACT_TITLE_SP = 20f
        const val COMPACT_SUB_SP = 16f
        const val BUTTON_RADIUS_DP = 28f
        const val LOCK_MS = 1_500L
        const val LOCKED_ALPHA = 0.6f
        const val ANIM_MS = 180L
        const val SLIDE_DP = 24f
    }
}
