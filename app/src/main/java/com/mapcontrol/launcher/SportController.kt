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
    /** Geçiş kuşağı: iptal edilen ya da yerine yenisi başlayan geçişin geç gelen geri çağrıları hiçbir şey yapmaz. */
    private var gen = 0

    /** Spor açık ya da açılıp kapanıyor (düzenlemeye girerken kapatılmalı). */
    val engaged: Boolean get() = shown || busy

    fun request(on: Boolean) {
        target = on
        if (busy || on == shown) return
        if (on) enter() else exit()
    }

    /**
     * Araç Spor'a geçmek üzere (karar süzgeci sayıyor): durağan çizim şimdiden arka planda hazırlanır, geçiş beklemesin.
     * Mod geri dönerse ([idle]) hazırlanan görünüm bırakılır.
     */
    fun prepare() {
        if (shown || busy) return
        sahne()?.sportStage()?.prepare()
    }

    fun idle() {
        if (shown || busy || target) return
        sahne()?.dropSport()
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
        // Durağan çizim hazır olunca tek seferde (boş kadranlar görünmesin). Çizim hiç olmazsa Normal kalır.
        val stage = s?.sportStage() ?: return
        val my = gen
        stage.whenReady {
            if (my != gen || !target || shown || busy || sahne() !== s || s.sportStage() !== stage) return@whenReady
            if (swap(true)) stage.showInstant()
        }
    }

    /**
     * Sahne/alt çubuk yeniden kuruldu (tema yerinde yenilendi): süren geçiş kesilir ve istenen son durum ([target]) yeni
     * parçalara anında uygulanır. Başlık yeniden kurulmaz (kendi spor durumunu korur); kök zemin yeniden kurulduğu için
     * Normal zemin yeniden saklanır.
     */
    fun onRebuilt() {
        cancelRunning()
        if (!shown) {
            if (target) applyInstant(true)
            return
        }
        val r = root()
        if (!target) {
            // Çıkış sürerken yenilendi: yeni parçalar zaten Normal; yalnız başlık ve durum geri alınır.
            shown = false
            rememberedSport = false
            header().setSport(false)
            savedGround = null
            return
        }
        r?.let {
            savedGround = it.background
            it.background = SportGround(activity)
        }
        bar()?.setSport(true)
        val s = sahne() ?: return
        if (!s.showSport(true)) { applyInstant(false); return }
        val stage = s.sportStage()
        val my = gen
        stage.whenReady { if (my == gen && shown && sahne() === s) stage.showInstant() }
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
        val my = ++gen
        val stage = s.sportStage()
        var dimmed = false
        var ready = false
        fun go() {
            if (my != gen || !dimmed || !ready || !busy) return
            // Örtünün altındaki an: sahne bu arada yenilendiyse ya da bırakıldıysa eski görünüm kullanılmaz.
            val now = sahne()
            if (now !== s || now.sportStage() !== stage || !swap(true)) { abortEnter(); return }
            stage.prepareIntro()
            stage.playIntro()
            fade(DIM_ALPHA, 0f, IN_REVEAL_MS, 0L) { if (my == gen) finish() }
        }
        stage.whenReady { ready = true; go() }
        // Durağan çizim gecikirse karartmada takılı kalınmaz: süre dolunca giriş bırakılır (Normal kalır). Çizim sonra
        // biterse ve Spor hâlâ isteniyorsa giriş yeniden denenir (bu kez çizim hazır, bekleme yok).
        stage.postDelayed({
            if (my != gen || ready) return@postDelayed
            abortEnter()
            stage.whenReady { if (target && !shown && !busy && sahne() === s) request(true) }
        }, READY_TIMEOUT_MS)
        fade(0f, DIM_ALPHA, IN_DIM_MS, 0L) { dimmed = true; go() }
    }

    /** Giriş yapılamadı (çizim gecikti, düzenleme açıldı): karartma kalkar, Normal kalır. */
    private fun abortEnter() {
        val my = ++gen
        fade(dim?.alpha ?: 0f, 0f, IN_REVEAL_MS, 0L) {
            if (my != gen) return@fade
            removeDim()
            busy = false
        }
    }

    private fun exit() {
        val s = sahne()
        if (s == null || !s.sportShown) { swap(false); return }
        if (!animationsOn()) { applyInstant(false); return }
        busy = true
        val my = ++gen
        val stage = s.sportStage()
        stage.playOutro()
        fade(0f, DIM_ALPHA, OUT_DIM_MS, OUT_DIM_DELAY_MS) {
            if (my != gen) return@fade
            swap(false)
            fade(DIM_ALPHA, 0f, OUT_REVEAL_MS, 0L) {
                if (my != gen) return@fade
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

    /** Asıl değişim (örtünün altında, tek seferde). Orta alan spora geçemediyse (düzenleme) hiçbir şey değişmez: false. */
    private fun swap(on: Boolean): Boolean {
        if (on) {
            val s = sahne() ?: return false
            if (!s.showSport(true)) return false
        } else {
            sahne()?.showSport(false)
        }
        shown = on
        rememberedSport = on
        header().setSport(on)
        bar()?.setSport(on)
        val r = root() ?: return true
        if (on) {
            savedGround = r.background
            r.background = SportGround(activity)
        } else {
            savedGround?.let { r.background = it }
            savedGround = null
        }
        return true
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
        gen++
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
