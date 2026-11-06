package com.mapcontrol.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.mapcontrol.R

/**
 * Sahne sol paneli: iki yüzlü kart. Ön yüz [MediaCard] (Sahne kipi), arka yüz [PhonePanel] (eski sağ telefon paneliyle aynı
 * görünüm ve davranış; mantık tek yerde). Sağ üst köşede sessiz bir çevirme simgesi ([FlipButton]; 64dp dokunma alanı,
 * ~22dp çizim) kartı gerçek bir dönüşle çevirir (rotationY, ~350 ms, yalnız dönüş sürerken donanım katmanı; içerik 90°'de
 * değişir; gölge kesilmesin diye katman cam panelde değil içerikte, cam zemin ve gölgesi panelle birlikte döner). Kartta yatay kaydırma da çevirir; ilerleme çubuğunda başlayan dokunuş çevirmez ([MediaFlipLogic.swipe]).
 *
 * Kurallar ([MediaFlipLogic]): bağlı telefon yoksa simge gizli ve kart medyada; telefon yüzündeyken telefon koparsa kart
 * medyaya döner (aynı görünürlükte geri bağlanınca kendiliğinden dönmez; bu yalnız bellekte tutulur). Son yüz kalıcıdır
 * ([CardFaceStore]) ve yalnız kullanıcı çevirince değişir: kopma kaydı silmez. Arama bitince kart kendiliğinden dönmez. Telefon yüzündeyken müzik
 * çalıyorsa simge yumuşakça ışır (yalnız simge; rozet/nokta yok).
 *
 * Yaşam döngüsü: medya kartı ekran görünürken hep çalışır (ışıma için çalma durumu); telefon paneli yalnız telefon yüzü
 * görünürken [PhoneSource]'u dinler. Bağlantı durumu için kart, arama şeridi gibi yalnız durum isteyen bir sahiptir
 * (`wantsData = false`: rehber/geçmiş okunmaz).
 */
@SuppressLint("ViewConstructor")
internal class MediaPhoneFlip(private val activity: Activity) : SizedWidget, PhoneListener {

    private val context: Context = activity
    val media = MediaCard(activity, sahne = true)
    private val phone = PhonePanel(activity, headerEndInsetDp = PHONE_HEADER_INSET_DP)
    private val store = CardFaceStore(activity)

    /** Kayıtlı (son seçilen) yüz. */
    private var saved = store.load()
    /** Ekrandaki yüz. */
    var face = CardFace.MEDIA
        private set
    private var connected = false
    /** Son bildirimdeki bağlantı (null: bu görünürlükte henüz bildirim gelmedi). */
    private var wasConnected: Boolean? = null
    /** Bu görünürlükte bağlı görülen telefon koptu: kart medyada tutulur (kaydedilmez; [MediaFlipLogic.holdAfter]). */
    private var held = false
    private var visible = false
    private var phoneListening = false
    private var turn: Animator? = null
    /** Dönüşün ikinci yarısında gösterilecek yüz (dönüş iptal edilirse hemen uygulanır). */
    private var pendingFace: CardFace? = null

    /** Ekrandaki yüz değişince (panel zeminindeki medya ışıması yalnız medya yüzünde). */
    var onFaceShown: ((CardFace) -> Unit)? = null

    /** Kart şu an dönüyor mu (panel nabzı dönüşü bozmasın). */
    val turning: Boolean get() = turn != null

    private val button = FlipButton(context).apply {
        setOnClickListener { flip(MediaFlipLogic.direction(0f)) }
    }

    private val frame = FlipFrame(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(media.view.also { it.background = null; it.elevation = 0f }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(phone.view.also { it.background = null; it.elevation = 0f; it.visibility = View.INVISIBLE }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(button, FrameLayout.LayoutParams(context.dp(FlipButton.TOUCH_DP), context.dp(FlipButton.TOUCH_DP), Gravity.TOP or Gravity.END))
    }

    init {
        media.onPlayingChanged = { refreshGlow() }
        connected = PhoneSource.snapshot().connected
        showFace(MediaFlipLogic.shown(saved, connected))
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
        finishTurn()
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
        // Kayıtlı yüz burada hiç değişmez; kopma yalnız bellekteki "medyada tut" bayrağını koyar.
        held = MediaFlipLogic.holdAfter(held, wasConnected, c)
        wasConnected = c
        connected = c
        button.visibility = if (MediaFlipLogic.flipAvailable(c)) View.VISIBLE else View.GONE
        val want = MediaFlipLogic.shown(saved, c, held)
        if (want == face && pendingFace == null) return
        // Kart görünürken yüz değişimi hep görünür bir dönüşle (kopma da, açılışta geç bağlanan telefonla son yüze dönüş de);
        // görünmüyorsa [turnTo] anında uygular.
        turnTo(want, -1)
    }

    // --- Çevirme ---

    /** Kullanıcı çevirdi (simge ya da kaydırma): yeni yüz kayda geçer. */
    private fun flip(direction: Int) {
        if (!connected || turn != null) return
        val target = MediaFlipLogic.other(face)
        held = false
        saved = target
        store.save(target)
        turnTo(target, direction)
    }

    /**
     * Kartı [target] yüzüne döndürür: panel (cam zemin dahil) 0 → 90° döner, 90°'de içerik değişir, -90° → 0 ile tamamlanır.
     * Dönüş boyunca kart içeriği ([frame]) tek donanım katmanıdır; bitince bırakılır. Katman panelin kendisine konmaz: cam
     * gölgesi panel sınırının dışına çizilir ve katman onu keserdi. Panel katmansız döner; zemini (tek yuvarlak dikdörtgen ve
     * gölgesi) her karede yeniden kaydedilmeden çizilir, gölge kartla birlikte döner. Görünmüyorsa ya da animasyonlar kapalıysa anında.
     */
    private fun turnTo(target: CardFace, direction: Int) {
        finishTurn()
        if (target == face) return
        val card = frame.parent as? View ?: frame
        if (!visible || !frame.isAttachedToWindow || card.width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            showFace(target)
            return
        }
        card.cameraDistance = context.resources.displayMetrics.density * CAMERA_DISTANCE
        frame.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        pendingFace = target
        val first = ObjectAnimator.ofFloat(card, View.ROTATION_Y, 0f, EDGE_DEG * direction).apply {
            // Dokunur dokunmaz hareket (yavaş başlayan hızlanma "geç tepki" gibi hissettiriyordu).
            duration = FIRST_MS
            interpolator = android.view.animation.LinearInterpolator()
        }
        val second = ObjectAnimator.ofFloat(card, View.ROTATION_Y, -EDGE_DEG * direction, 0f).apply {
            duration = SECOND_MS
            interpolator = DecelerateInterpolator(1.6f)
        }
        val set = android.animation.AnimatorSet().apply { playSequentially(first, second) }
        first.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (turn !== set) return
                pendingFace?.let { showFace(it) }
                pendingFace = null
            }
        })
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (turn !== set) return
                turn = null
                card.rotationY = 0f
                frame.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        turn = set
        set.start()
    }

    /** Süren dönüş bitirilir: kart düz, katman bırakılmış, hedef yüz ekranda. */
    private fun finishTurn() {
        val t = turn ?: return
        turn = null
        t.cancel()
        val card = frame.parent as? View ?: frame
        card.rotationY = 0f
        frame.setLayerType(View.LAYER_TYPE_NONE, null)
        pendingFace?.let { showFace(it) }
        pendingFace = null
    }

    private fun showFace(f: CardFace) {
        val changed = f != face
        face = f
        media.view.visibility = if (f == CardFace.MEDIA) View.VISIBLE else View.INVISIBLE
        phone.view.visibility = if (f == CardFace.PHONE) View.VISIBLE else View.INVISIBLE
        button.contentDescription = context.getString(
            if (f == CardFace.MEDIA) R.string.launcher_flip_to_phone else R.string.launcher_flip_to_media)
        syncPhoneListening()
        // Yüz kenardan (90°) değişti: ışıma geçişsiz (yeni yüzde eski ışıma sönerken görünmesin).
        refreshGlow(animate = false)
        if (changed) onFaceShown?.invoke(f)
    }

    /** Telefon paneli yalnız telefon yüzü görünürken dinler. */
    private fun syncPhoneListening() {
        val want = visible && face == CardFace.PHONE
        if (want == phoneListening) return
        phoneListening = want
        if (want) phone.onVisible() else phone.onHidden()
    }

    private fun refreshGlow(animate: Boolean = true) = button.setGlow(MediaFlipLogic.iconGlows(face, media.isPlaying), animate)

    // --- Kaydırma ---

    /**
     * Kart kabı: yatay kaydırmayı yakalar. Basış ilerleme çubuğunda ([MediaCard.horizontalDragViews]) başladıysa o dokunuş
     * çevirmez. Yatay niyet belli olunca dokunuş alttaki düğmeden alınır; çevirme mesafesinde kart bir kez döner.
     */
    private inner class FlipFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val flipPx = context.dpF(FLIP_DISTANCE_DP)
        private val hit = Rect()
        private var downX = 0f
        private var downY = 0f
        private var onDrag = false
        /** Dokunuşun kararı hareket boyunca hatırlanır (önce dikey giden sürükleme sonradan çevirmez). */
        private val gesture = MediaFlipLogic.SwipeGesture(slop, flipPx)

        private fun startsOnDrag(x: Float, y: Float): Boolean {
            if (face != CardFace.MEDIA) return false
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

        private fun decide(ev: MotionEvent): MediaFlipLogic.Swipe =
            gesture.move(ev.x - downX, ev.y - downY, connected && turn == null)

        private fun down(ev: MotionEvent) {
            downX = ev.x; downY = ev.y
            onDrag = startsOnDrag(ev.x, ev.y)
            gesture.down(onDrag)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> down(ev)
                MotionEvent.ACTION_MOVE -> when (decide(ev)) {
                    MediaFlipLogic.Swipe.CAPTURE -> return true
                    MediaFlipLogic.Swipe.FLIP -> { fire(ev); return true }
                    else -> Unit
                }
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
                    if (decide(ev) == MediaFlipLogic.Swipe.FLIP) fire(ev)
                    return true
                }
            }
            return true
        }

        /** [SwipeGesture] FLIP'i dokunuş başına bir kez verir. */
        private fun fire(ev: MotionEvent) {
            flip(MediaFlipLogic.direction(ev.x - downX))
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        /** Dönüşün iki yarısı: ilk yarı doğrusal ve kısa (anında tepki), ikinci yarı yavaşlayarak oturur; toplam 240 ms. */
        const val FIRST_MS = 100L
        const val SECOND_MS = 140L
        const val EDGE_DEG = 90f
        /** Kamera uzaklığı (dp cinsinden çarpan): kart dönerken kenarı aşırı büyümesin. */
        const val CAMERA_DISTANCE = 9000f
        const val FLIP_DISTANCE_DP = 72f
        const val DRAG_PAD_DP = 16
        /** Telefon başlığının sağında çevirme simgesine bırakılan pay. */
        const val PHONE_HEADER_INSET_DP = 28
    }
}

/**
 * Çevirme simgesi: 64dp dokunma alanının ortasında ~22dp ince çizgili iki dönüş oku, kartın kendi mürekkep renginde. Işıma
 * açıkken simgenin kendisi medya kartının sıcak renginde yanar ve arkasında yumuşak bir ışık lekesi belirir (rozet/halka yok);
 * geçiş 400 ms, kare başına ayırma yok (iki sabit tonlu kopya ve tek gölgelendirici).
 */
@SuppressLint("ViewConstructor")
internal class FlipButton(context: Context) : View(context) {
    private val base: Drawable = context.getDrawable(R.drawable.ic_sahne_flip)!!.mutate().apply { setTint(Sahne.INK_2) }
    private val lit: Drawable = context.getDrawable(R.drawable.ic_sahne_flip)!!.mutate().apply { setTint(warm()) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow = 0f
    private var glowOn = false
    private var anim: ValueAnimator? = null

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

/** Kartın son yüzü: profil başına kalıcı (yeniden başlatmada aynı yüz). */
internal class CardFaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Profiles.key(context, PREFS), Context.MODE_PRIVATE)

    fun load(): CardFace = MediaFlipLogic.decode(prefs.getString(KEY, null))

    fun save(face: CardFace) = prefs.edit().putString(KEY, MediaFlipLogic.encode(face)).apply()

    private companion object {
        const val PREFS = "launcher_media_flip"
        const val KEY = "face"
    }
}
