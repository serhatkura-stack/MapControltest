package com.mapcontrol.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Spor modu geçişleri (Sahne düzeninde). Orta alan [SahneScreen.showSport] ile spor ekranına döner; üst çubuk
 * ([HeaderBar.setSport]), bizim alt çubuk ([OurBottomBar.setSport], OEM çubuğunda yok) ve kök zemin (karbon) aynı anda
 * değişir. Değişim, ekranı kısa süre karartan tam ekran örtünün altında yapılır (tek kare ağır iş görünmez).
 *
 * Giriş (~1,2 sn): karartma (250 ms) · değişim · karartma kalkar, kadran çerçeveleri sırayla parlar, ibre sona süpürüp
 * iner, canlı değerler belirir. Çıkış (~1,5 sn, sakin): değerler söner, ibre iner, karartma (400 ms) · değişim · karartma
 * yavaşça kalkar; parlama yok. Yalnız görünüm alfası/dönmesi (donanım katmanı). Animasyonlar kapalıysa
 * (ANIMATOR_DURATION_SCALE 0) anında.
 */
internal class SportController(
    private val activity: Activity,
    private val sahne: () -> SahneScreen?,
    private val header: () -> HeaderBar,
    private val bar: () -> OurBottomBar?,
    private val root: () -> View?,
) {
    /** Ekranda spor görünümü var mı (değişim yapıldı). */
    var shown = false
        private set
    /** İstenen son durum; geçiş sürerken gelen istek geçiş bitince uygulanır. */
    private var target = false
    private var busy = false
    private var dim: View? = null
    private var savedGround: Drawable? = null

    fun request(on: Boolean) {
        target = on
        if (busy || on == shown) return
        if (on) enter() else exit()
    }

    /** Düzenleme/tema yenilemesi gibi durumlarda animasyonsuz uygular. */
    fun applyInstant(on: Boolean) {
        target = on
        cancelRunning()
        if (on == shown) return
        val s = sahne()
        if (!on) {
            swap(false)
            s?.dropSport()
            return
        }
        // Durağan çizim hazır olunca tek seferde (boş kadranlar görünmesin).
        val stage = s?.sportStage() ?: return
        stage.whenReady {
            if (!target || shown || sahne() !== s) return@whenReady
            swap(true)
            stage.showInstant()
        }
    }

    /**
     * Sahne/alt çubuk yeniden kuruldu (tema yerinde yenilendi): spor açıksa yeni parçalara anında uygulanır.
     * Başlık kendi spor durumunu korur; kök zemin yeniden kurulduğu için karbon tekrar konur.
     */
    fun onRebuilt() {
        if (!shown) return
        cancelRunning()
        root()?.let { r ->
            savedGround = r.background
            r.background = SportGround(activity)
        }
        bar()?.setSport(true)
        val s = sahne() ?: return
        s.showSport(true)
        s.sportStage().whenReady { s.sportStage().showInstant() }
    }

    fun release() {
        cancelRunning()
    }

    private fun animationsOn(): Boolean = try {
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f && ValueAnimator.areAnimatorsEnabled()
    } catch (e: RuntimeException) {
        true
    }

    private fun enter() {
        val s = sahne() ?: return
        if (!animationsOn()) { applyInstant(true); return }
        busy = true
        val stage = s.sportStage()
        var dimmed = false
        var ready = false
        fun go() {
            if (!dimmed || !ready || !busy) return
            swap(true)
            stage.prepareIntro()
            stage.playIntro()
            fade(DIM_ALPHA, 0f, IN_REVEAL_MS, 0L) { finish() }
        }
        stage.whenReady { ready = true; go() }
        // Durağan çizim gecikirse (ör. ilk ölçüm gelmedi) karartmada takılı kalınmaz: süre dolunca yine geçilir.
        stage.postDelayed({ if (!ready) { ready = true; go() } }, READY_TIMEOUT_MS)
        fade(0f, DIM_ALPHA, IN_DIM_MS, 0L) { dimmed = true; go() }
    }

    private fun exit() {
        val s = sahne()
        if (s == null || !s.sportShown) { swap(false); return }
        if (!animationsOn()) { applyInstant(false); return }
        busy = true
        val stage = s.sportStage()
        stage.playOutro()
        fade(0f, DIM_ALPHA, OUT_DIM_MS, OUT_DIM_DELAY_MS) {
            swap(false)
            fade(DIM_ALPHA, 0f, OUT_REVEAL_MS, 0L) {
                sahne()?.dropSport()
                finish()
            }
        }
    }

    private fun finish() {
        removeDim()
        busy = false
        if (target != shown) request(target)
    }

    /** Asıl değişim (örtünün altında, tek seferde). */
    private fun swap(on: Boolean) {
        shown = on
        rememberedSport = on
        sahne()?.showSport(on)
        header().setSport(on)
        bar()?.setSport(on)
        val r = root() ?: return
        if (on) {
            savedGround = r.background
            r.background = SportGround(activity)
        } else {
            savedGround?.let { r.background = it }
            savedGround = null
        }
    }

    private fun fade(from: Float, to: Float, ms: Long, delay: Long, end: () -> Unit) {
        val v = dim ?: addDim() ?: run { end(); return }
        v.animate().cancel()
        v.alpha = from
        v.animate().alpha(to).setStartDelay(delay).setDuration(ms).setInterpolator(DecelerateInterpolator()).withEndAction(end).start()
    }

    private fun addDim(): View? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return null
        return View(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
            isClickable = true // geçiş sırasında dokunuşlar alttaki düğmelere gitmez
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            content.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            dim = this
        }
    }

    private fun removeDim() {
        val v = dim ?: return
        v.animate().cancel()
        (v.parent as? ViewGroup)?.removeView(v)
        dim = null
    }

    private fun cancelRunning() {
        if (!busy) { removeDim(); return }
        busy = false
        removeDim()
        if (!shown) sahne()?.dropSport()
    }

    companion object {
        const val DIM_ALPHA = 0.88f
        const val IN_DIM_MS = 250L
        const val IN_REVEAL_MS = 380L
        const val OUT_DIM_DELAY_MS = 350L
        const val OUT_DIM_MS = 420L
        const val OUT_REVEAL_MS = 650L
        const val READY_TIMEOUT_MS = 1_500L

        /** Etkinlik yeniden kurulursa (tema, tercih) spor ekranı geçişsiz geri gelsin diye. */
        @Volatile var rememberedSport = false
    }
}
