package com.mapcontrol.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.mapcontrol.R

/**
 * Sahne sol paneli: iki sayfalı kart. İlk sayfa [MediaCard] (Sahne kipi), yandaki sayfa [PhonePanel] (eski sağ telefon
 * paneliyle aynı görünüm ve davranış; mantık tek yerde). Sayfalar kartın içinde yan yana durur ve yalnız içerik kayar
 * (translationX): cam zemin, gölgesi ve zemin görseli yerinde kalır; içerik kartın yuvarlak köşelerine kırpılır, kart
 * dışına hiçbir şey taşmaz. Yatay sürüklemede içerik parmağı 1:1 izler (uçlarda dirençle); bırakınca en yakın sayfaya,
 * %30'dan fazla sürüklendiyse ya da savrulduysa ([FLING_DP_S]) yandaki sayfaya oturur ([SETTLE_MS], yavaşlayarak, o anki
 * konumdan). Sağ üst köşedeki sessiz sayfa simgesi ([PageButton]; 64dp dokunma alanı, ~22dp çizim) aynı oturma
 * hareketiyle öbür sayfaya geçer. İlerleme çubuğunda başlayan dokunuş sayfa kaydırmaz ([MediaFlipLogic.SwipeGesture]).
 *
 * Kurallar ([MediaFlipLogic]): bağlı telefon yoksa simge gizli, kaydırma kapalı ve kart medyada; telefon sayfasındayken
 * telefon koparsa kart medyaya kayar (aynı görünürlükte geri bağlanınca kendiliğinden dönmez; bu yalnız bellekte tutulur).
 * Son sayfa kalıcıdır ([CardFaceStore]) ve yalnız kullanıcı değiştirince değişir: kopma kaydı silmez. Arama bitince kart
 * kendiliğinden kaymaz. Telefon sayfasındayken müzik çalıyorsa simge yumuşakça ışır (yalnız simge; rozet/nokta yok).
 *
 * Yaşam döngüsü: medya kartı ekran görünürken hep çalışır (ışıma için çalma durumu); telefon paneli yalnız telefon
 * sayfası (kısmen de olsa) görünürken [PhoneSource]'u dinler. Bağlantı durumu için kart, arama şeridi gibi yalnız durum
 * isteyen bir sahiptir (`wantsData = false`: rehber/geçmiş okunmaz).
 */
@SuppressLint("ViewConstructor")
internal class MediaPhoneFlip(private val activity: Activity) : SizedWidget, PhoneListener {

    private val context: Context = activity
    val media = MediaCard(activity, sahne = true)
    private val phone = PhonePanel(activity, headerEndInsetDp = PHONE_HEADER_INSET_DP)
    private val store = CardFaceStore(activity)

    /** Kayıtlı (son seçilen) sayfa. */
    private var saved = store.load()
    /** Oturmuş (ekrandaki) sayfa; kayarken eski sayfa, oturunca hedef. */
    var face = CardFace.MEDIA
        private set
    private var connected = false
    /** Son bildirimdeki bağlantı (null: bu görünürlükte henüz bildirim gelmedi). */
    private var wasConnected: Boolean? = null
    /** Bu görünürlükte bağlı görülen telefon koptu: kart medyada tutulur (kaydedilmez; [MediaFlipLogic.holdAfter]). */
    private var held = false
    private var visible = false
    private var phoneListening = false

    /** İçerik konumu, sayfa biriminde (0 medya, 1 telefon; uçlarda biraz taşabilir). */
    private var pos = 0f
    private var dragging = false
    private var settling = false
    private var settleFrom = 0f
    private var settleTo = 0f
    private var settleFace = CardFace.MEDIA
    /** Tek oturma animatörü (her kaydırmada yeniden kullanılır; karede ayırma yok: kesir ilkel okunur). */
    private val settle = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SETTLE_MS
        interpolator = DecelerateInterpolator(SETTLE_EASE)
        addUpdateListener { setPos(settleFrom + (settleTo - settleFrom) * it.animatedFraction) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = finishSettle()
        })
    }

    /** Oturmuş sayfa değişince (panel zeminindeki medya ışıması yalnız medya sayfasında). */
    var onFaceShown: ((CardFace) -> Unit)? = null

    /** Kart şu an kayıyor mu (sürükleme ya da oturma; panel nabzı hareketi bozmasın). */
    val moving: Boolean get() = dragging || settling

    private val button = PageButton(context).apply {
        setOnClickListener { userSlide(MediaFlipLogic.other(if (settling) settleFace else face)) }
    }

    private val frame = PagerFrame(context).apply {
        // İçerik kartın yuvarlak köşelerine kırpılır; cam zemin ve gölgesi panelde (GlassPanel) kalır, kırpılmaz.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) =
                outline.setRoundRect(0, 0, v.width, v.height, context.dpF(Sahne.PANEL_RADIUS_DP))
        }
        clipToOutline = true
        addView(media.view.also { it.background = null; it.elevation = 0f }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(phone.view.also { it.background = null; it.elevation = 0f; it.visibility = View.INVISIBLE }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(button, FrameLayout.LayoutParams(context.dp(PageButton.TOUCH_DP), context.dp(PageButton.TOUCH_DP), Gravity.TOP or Gravity.END))
    }

    init {
        media.onPlayingChanged = { refreshGlow() }
        connected = PhoneSource.snapshot().connected
        val f = MediaFlipLogic.shown(saved, connected)
        setPos(MediaFlipLogic.page(f).toFloat())
        showFace(f)
        button.visibility = if (MediaFlipLogic.flipAvailable(connected)) View.VISIBLE else View.GONE
    }

    override val view: View get() = frame
    override fun applySpan(span: Span) = Unit

    override fun onVisible() {
        if (visible) return
        visible = true
        media.onVisible()
        wasConnected = null
        held = false
        PhoneSource.start(activity, this, wantsData = false)
        PhoneSource.addListener(this)
        syncPhoneListening()
    }

    override fun onHidden() {
        if (!visible) return
        visible = false
        if (frame.abortDrag()) setPos(MediaFlipLogic.page(face).toFloat())
        if (settling) settle.end()
        media.onHidden()
        PhoneSource.removeListener(this)
        PhoneSource.stop(this)
        syncPhoneListening()
    }

    override fun release() {
        onHidden()
        media.onPlayingChanged = null
        media.release()
        phone.release()
        onFaceShown = null
    }

    // --- Bağlantı ---

    override fun onPhoneChanged(snapshot: PhoneSnapshot) {
        val c = snapshot.connected
        if (c == wasConnected) return
        // Kayıtlı sayfa burada hiç değişmez; kopma yalnız bellekteki "medyada tut" bayrağını koyar.
        held = MediaFlipLogic.holdAfter(held, wasConnected, c)
        wasConnected = c
        connected = c
        button.visibility = if (MediaFlipLogic.flipAvailable(c)) View.VISIBLE else View.GONE
        // Telefon koptuysa süren sürükleme kesilir (içerik aşağıda medyaya kayar).
        val aborted = !c && frame.abortDrag()
        val want = MediaFlipLogic.shown(saved, c, held)
        if (!aborted && want == (if (settling) settleFace else face)) return
        // Kart görünürken sayfa değişimi hep görünür bir kaymayla (kopma da, açılışta geç bağlanan telefonla son sayfaya
        // dönüş de); görünmüyorsa [slideTo] anında uygular.
        slideTo(want)
    }

    // --- Sayfalar ---

    /** Kullanıcı sayfa seçti (simge ya da kaydırma): seçim kayda geçer. */
    private fun userSlide(target: CardFace) {
        if (!connected && target == CardFace.PHONE) return
        held = false
        if (target != saved) {
            saved = target
            store.save(target)
        }
        slideTo(target)
    }

    /**
     * İçeriği [target] sayfasına o anki konumdan kaydırır ([SETTLE_MS], yavaşlayarak). Görünmüyorsa, ölçülmemişse ya da
     * animasyonlar kapalıysa anında.
     */
    private fun slideTo(target: CardFace) {
        val to = MediaFlipLogic.page(target).toFloat()
        settling = false
        settle.cancel()
        if (!visible || !frame.isAttachedToWindow || frame.width == 0 || !ValueAnimator.areAnimatorsEnabled() ||
            kotlin.math.abs(pos - to) < EPS) {
            setPos(to)
            showFace(target)
            return
        }
        settleFrom = pos
        settleTo = to
        settleFace = target
        settling = true
        settle.start()
    }

    /** Oturma bitti (ya da [ValueAnimator.end]): içerik tam sayfada, hedef sayfa ekranda. İptalde [settling] zaten kapalı. */
    private fun finishSettle() {
        if (!settling) return
        settling = false
        setPos(settleTo)
        showFace(settleFace)
    }

    /** İçerik konumu: iki sayfa yan yana, yalnız translationX; tamamen dışarıdaki sayfa çizilmez. */
    private fun setPos(p: Float) {
        pos = p
        val w = frame.width.toFloat()
        media.view.translationX = -p * w
        phone.view.translationX = (1f - p) * w
        media.view.visibility = if (p < 1f - EPS) View.VISIBLE else View.INVISIBLE
        phone.view.visibility = if (p > EPS) View.VISIBLE else View.INVISIBLE
        // Köşe simgesi yerinde durur; kayarken gelen sayfanın başlığıyla üst üste binmesin diye ortada söner.
        val f = p.coerceIn(0f, 1f)
        button.alpha = kotlin.math.abs(1f - 2f * f)
        syncPhoneListening()
    }

    private fun showFace(f: CardFace) {
        val changed = f != face
        face = f
        button.contentDescription = context.getString(
            if (f == CardFace.MEDIA) R.string.launcher_flip_to_phone else R.string.launcher_flip_to_media)
        syncPhoneListening()
        refreshGlow()
        if (changed) onFaceShown?.invoke(f)
    }

    /** Telefon paneli yalnız telefon sayfası (kısmen de olsa) görünürken dinler. */
    private fun syncPhoneListening() {
        val want = visible && (face == CardFace.PHONE || pos > EPS)
        if (want == phoneListening) return
        phoneListening = want
        if (want) phone.onVisible() else phone.onHidden()
    }

    private fun refreshGlow(animate: Boolean = true) = button.setGlow(MediaFlipLogic.iconGlows(face, media.isPlaying), animate)

    // --- Kaydırma ---

    /**
     * Kart kabı: yatay sürüklemeyi yakalar ve içeriği parmakla kaydırır. Basış ilerleme çubuğunda
     * ([MediaCard.horizontalDragViews]) başladıysa o dokunuş sayfa kaydırmaz. Yatay niyet belli olunca dokunuş alttaki
     * düğmeden alınır; bırakınca [MediaFlipLogic.settle] hedef sayfayı seçer. Oturma sürerken basılırsa oturma hemen biter.
     */
    private inner class PagerFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val flingPx = context.dpF(FLING_DP_S)
        private val hit = Rect()
        private var downX = 0f
        private var downY = 0f
        /** Sürüklemenin başladığı nokta ve sayfa (yakalama anından: içerik zıplamaz). */
        private var originX = 0f
        private var anchor = 0
        private var onDrag = false
        private var tracker: VelocityTracker? = null
        private val gesture = MediaFlipLogic.SwipeGesture(slop)

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Genişlik değişince sayfalar yeni genişliğe göre yerleşir.
            if (changed) setPos(pos)
        }

        private fun startsOnDrag(x: Float, y: Float): Boolean {
            if (face != CardFace.MEDIA || moving) return false
            for (v in media.horizontalDragViews()) {
                if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) continue
                hit.set(0, 0, v.width, v.height)
                offsetDescendantRectToMyCoords(v, hit)
                // Topuz ve süreler için biraz pay.
                val pad = context.dp(DRAG_PAD_DP)
                if (MediaFlipLogic.hits(x, y, hit.left - pad, hit.top - pad, hit.right + pad, hit.bottom + pad)) return true
            }
            return false
        }

        private fun track(ev: MotionEvent) {
            (tracker ?: VelocityTracker.obtain().also { tracker = it }).addMovement(ev)
        }

        private fun down(ev: MotionEvent) {
            // Oturma sürerken basış: hareket hemen tamamlanır, dokunuş oturmuş sayfada işlenir.
            if (settling) settle.end()
            tracker?.clear()
            track(ev)
            downX = ev.x; downY = ev.y
            onDrag = startsOnDrag(ev.x, ev.y)
            gesture.down(onDrag)
        }

        /** Hareket kararı; DRAG ilk geldiğinde sürükleme başlar. */
        private fun decide(ev: MotionEvent): Boolean {
            if (dragging) return true
            if (gesture.move(ev.x - downX, ev.y - downY, connected && width > 0) != MediaFlipLogic.Swipe.DRAG) return false
            dragging = true
            originX = ev.x
            anchor = MediaFlipLogic.page(face)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        private fun drag(ev: MotionEvent) {
            val raw = anchor - (ev.x - originX) / width
            setPos(MediaFlipLogic.resist(raw, maxPage()))
        }

        private fun maxPage(): Int = if (connected) 1 else 0

        private fun release(cancelled: Boolean) {
            val t = tracker
            tracker = null
            if (dragging) {
                dragging = false
                var v = 0f
                if (t != null && !cancelled) {
                    t.computeCurrentVelocity(1000)
                    v = t.xVelocity
                }
                val page = MediaFlipLogic.settle(anchor, pos, v, flingPx, maxPage())
                if (page == anchor) slideTo(face) else userSlide(MediaFlipLogic.faceAt(page))
            }
            t?.recycle()
        }

        /** Süren sürükleme dışarıdan kesilir (içerik yerinde kalır; çağıran kaydırır). Sürükleme vardı mı. */
        fun abortDrag(): Boolean {
            gesture.cancel()
            val was = dragging
            dragging = false
            return was
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(ev)
                MotionEvent.ACTION_MOVE -> {
                    track(ev)
                    if (decide(ev)) { drag(ev); return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(cancelled = true)
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                // Boş bir yere basıldı: kaydırma izlenebilsin diye dokunuş alınır (tıklama davranışı yok).
                MotionEvent.ACTION_DOWN -> {
                    down(ev)
                    return connected && !onDrag
                }
                MotionEvent.ACTION_MOVE -> {
                    track(ev)
                    if (decide(ev)) drag(ev)
                }
                MotionEvent.ACTION_UP -> { track(ev); release(cancelled = false) }
                MotionEvent.ACTION_CANCEL -> release(cancelled = true)
            }
            return true
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        /** Oturma: o anki konumdan hedef sayfaya, yavaşlayarak. */
        const val SETTLE_MS = 190L
        const val SETTLE_EASE = 1.5f
        /** Savurma eşiği (dp/s): bunu aşan bırakış yandaki sayfaya geçer. */
        const val FLING_DP_S = 800f
        const val EPS = 0.001f
        const val DRAG_PAD_DP = 16
        /** Telefon başlığının sağında sayfa simgesine bırakılan pay. */
        const val PHONE_HEADER_INSET_DP = 28
    }
}

/**
 * Sayfa simgesi: 64dp dokunma alanının ortasında ~22dp ince çizgili iki köşeli ok (sola/sağa), kartın kendi mürekkep renginde. Işıma
 * açıkken simgenin kendisi medya kartının sıcak renginde yanar ve arkasında yumuşak bir ışık lekesi belirir (rozet/halka yok);
 * geçiş 400 ms, kare başına ayırma yok (iki sabit tonlu kopya ve tek gölgelendirici).
 */
@SuppressLint("ViewConstructor")
internal class PageButton(context: Context) : View(context) {
    private val base: Drawable = context.getDrawable(R.drawable.ic_sahne_page)!!.mutate().apply { setTint(Sahne.INK_2) }
    private val lit: Drawable = context.getDrawable(R.drawable.ic_sahne_page)!!.mutate().apply { setTint(warm()) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow = 0f
    private var glowOn = false
    private var anim: ValueAnimator? = null

    /** Kayarken saydamlaşır: tek kopya çizildiği varsayılır (ara katman gerekmez). */
    override fun hasOverlappingRendering(): Boolean = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        pressFeedback()
    }

    fun setGlow(on: Boolean, animate: Boolean = true) {
        if (on == glowOn) return
        glowOn = on
        anim?.cancel()
        anim = null
        val target = if (on) 1f else 0f
        if (!animate || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            glow = target
            invalidate()
            return
        }
        val from = glow
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLOW_MS
            addUpdateListener { glow = from + (target - from) * it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        glow = if (glowOn) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = context.dpF(HALO_DP)
        val c = warm()
        halo.shader = RadialGradient(w / 2f, h / 2f, r,
            intArrayOf((c and 0x00FFFFFF) or (HALO_ALPHA shl 24), (c and 0x00FFFFFF) or ((HALO_ALPHA / 3) shl 24), c and 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val s = context.dp(ICON_DP)
        val l = (w - s) / 2
        val t = (h - s) / 2
        base.setBounds(l, t, l + s, t + s)
        lit.setBounds(l, t, l + s, t + s)
    }

    override fun onDraw(canvas: Canvas) {
        if (glow > 0f) {
            halo.alpha = (glow * 255).toInt()
            canvas.drawCircle(width / 2f, height / 2f, context.dpF(HALO_DP), halo)
            lit.alpha = (glow * 255).toInt()
        }
        if (glow < 1f) {
            base.alpha = ((1f - glow) * 255).toInt()
            base.draw(canvas)
        }
        if (glow > 0f) lit.draw(canvas)
    }

    /** Medya kartı ışımasının tonu (gece mor-pembe, gündüz sıcak turuncu), simgede okunacak kadar doygun. */
    private fun warm(): Int = if (LauncherTheme.night) 0xFFE59ACB.toInt() else 0xFFD0654A.toInt()

    companion object {
        const val TOUCH_DP = 64
        const val ICON_DP = 22
        const val HALO_DP = 22f
        const val HALO_ALPHA = 0x66
        const val GLOW_MS = 400L
    }
}

/** Kartın son sayfası: profil başına kalıcı (yeniden başlatmada aynı yüz). */
internal class CardFaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(context, PREFS), Context.MODE_PRIVATE)

    fun load(): CardFace = MediaFlipLogic.decode(prefs.getString(KEY, null))

    fun save(face: CardFace) = prefs.edit().putString(KEY, MediaFlipLogic.encode(face)).apply()

    private companion object {
        const val PREFS = "launcher_media_flip"
        const val KEY = "face"
    }
}
