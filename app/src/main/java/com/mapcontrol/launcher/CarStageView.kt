package com.mapcontrol.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.mapcontrol.R

/**
 * Sahne'nin ortasındaki araç: kapalı gövde (base) ve her açılır parça için boşluk → parça → açık hal fotoğrafı katmanları
 * (`.impeccable/mocks/premium/doors`, B seçeneği). Kapılar ön menteşe etrafında döner ve kısalır (gölge + dış kenar
 * ışığıyla), sonra açık hal fotoğrafına geçer; kaput ön cama doğru basılır; bagaj geriye kayarak söner.
 *
 * Her parça kendi [ValueAnimator]'ıyla, anim.json'daki kübik eğrilerle ([PathInterpolator]) bağımsız oynar; yarıda
 * tersine dönen parça o anki halinden döner ([PartMotion]). Kare başına yalnızca matris + saydamlık değişir; bellek
 * ayrılmaz. Görseller renk başına bir kez çözülür (süreç boyunca tek renk takımı tutulur); renk değişince eskisi bırakılır.
 * Görünüm base'in taşan parçalarını ([CarAnimSpec.padLeft] …) da kapsar; araç gövdesi [carLeft]/[carTop]'tan başlar.
 *
 * Dijital ikiz (§11, [setLights]): far huzmesi, fren ışığı ve kırmızı zemin havuzu, geri lambası, sinyaller ve kilitlenince
 * dörtlülerin tek yanışı. Uyarıda ilgili parçanın kendisi yanar (halka / rozet yok): düşük lastikte o lastik gövde kenarının
 * altından kehribar parlar ([setTireAlert]), yakıt kapağı açıkken kapak yerinde, motor suyu / akü uyarısında kaput üstünde
 * hafif kehribar ([setPartAlerts]); arabanın altında çok hafif yansıma
 * ve ~60 sn'de bir 1,5 sn'lik boya parlaması. Hepsi önceden çizilmiş küçük maskelerdir (paylaşılan ALPHA_8 bitmap'ler) ve
 * yalnızca görünüm alfası / konumu canlanır: kare başına çizim kaydı ve bellek ayırma yoktur. Işık katmanları çocuk
 * görünümlerdir; zemindekiler gövdenin altında, lambalar üstünde çizilir. [setActive] false iken (ekran görünmezken)
 * yanıp sönme ve parlama durur.
 */
class CarStageView(context: Context) : ViewGroup(context) {

    private val parts = CarPart.values()
    private val specs = Array(parts.size) { CarAnimSpec.part(parts[it]) }
    private val motions = Array(parts.size) { i ->
        val s = specs[i]
        val open = PathInterpolator(s.openEasing[0], s.openEasing[1], s.openEasing[2], s.openEasing[3])
        val close = PathInterpolator(s.closeEasing[0], s.closeEasing[1], s.closeEasing[2], s.closeEasing[3])
        PartMotion(s, open::getInterpolation, close::getInterpolation)
    }
    private val animators = arrayOfNulls<ValueAnimator>(parts.size)
    private var running = 0
        set(value) {
            val was = field > 0
            field = maxOf(0, value)
            if (was != field > 0) onAnimatingChanged?.invoke(field > 0)
        }

    /** Hareket başlayınca true, tüm parçalar durunca false (Sahne zeminini bu sürede donanım katmanında tutar). */
    var onAnimatingChanged: ((Boolean) -> Unit)? = null

    private var bitmaps: CarBitmaps? = null
    private val shadows = arrayOfNulls<Bitmap>(parts.size)
    private val rimPaints = arrayOfNulls<Paint>(parts.size)
    private val rimRect = RectF()

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // --- Cam vurgusu: dört yan cam ve tavan camı ([CarAnimSpec.windows], [CarAnimSpec.sunroof]) ---
    private val glassKeys: List<BodyPart?> = CarAnimSpec.windows.keys.toList() + null
    private val glasses = glassKeys.map { if (it == null) CarAnimSpec.sunroof else CarAnimSpec.windows.getValue(it) }
    private val glassDoor = IntArray(glasses.size) { i -> glasses[i].door?.let { parts.indexOf(it) } ?: -1 }
    private val glassFrom = FloatArray(glasses.size)
    private val glassNow = FloatArray(glasses.size)
    private val glassTarget = FloatArray(glasses.size)
    private var glassAnim: ValueAnimator? = null
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassRect = RectF()

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(CarColorPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CarColorPrefs.KEY) setColor(CarColorPrefs.get(context))
    }

    // --- Işıklar (§11): zemindekiler gövdenin altında, lambalar üstünde ---
    private val beamTop = sprite(LightMasks.beam, HEAD_BEAM)
    private val beamBottom = sprite(LightMasks.beam, HEAD_BEAM)
    private val brakePool = sprite(LightMasks.glow, BRAKE_POOL)
    private val reverseFloor = sprite(LightMasks.glow, REVERSE_FLOOR)
    private val reflection = LightSprite(context, null, 0xFFFFFFFF.toInt()).apply { alpha = REFLECTION_ALPHA }
    private val headTop = sprite(LightMasks.glow, HEAD_LAMP)
    private val headBottom = sprite(LightMasks.glow, HEAD_LAMP)
    private val tail = sprite(LightMasks.glow, TAIL_LAMP)
    private val reverseTop = sprite(LightMasks.glow, REVERSE_LAMP)
    private val reverseBottom = sprite(LightMasks.glow, REVERSE_LAMP)
    private val turnLeft = arrayOf(sprite(LightMasks.hot, TURN), sprite(LightMasks.hot, TURN), sprite(LightMasks.hot, TURN))
    private val turnRight = arrayOf(sprite(LightMasks.hot, TURN), sprite(LightMasks.hot, TURN), sprite(LightMasks.hot, TURN))
    /** Lastikler gövdenin altında (yalnızca kenardan taşan kısım ve ışıması görünür); [TireDecode] sırası. */
    private val tires = Array(4) { sprite(PartArt.tire, 0xFFFFFFFF.toInt()) }
    private val fuelCap = sprite(PartArt.fuelCap, 0xFFFFFFFF.toInt())
    private val hood = sprite(null, HOOD_GLOW)
    private val glint = GlintLayer(context)
    private val under: Array<View> = arrayOf(reflection, brakePool, reverseFloor, beamTop, beamBottom, *tires)
    private val over: Array<View> = arrayOf(tail, headTop, headBottom, reverseTop, reverseBottom, *turnLeft, *turnRight, hood, fuelCap, glint)
    /** Çocukların base pikseli kutuları (gövdenin sol üstüne göre): sol, üst, sağ, alt. */
    private val boxes: Map<View, FloatArray> = mapOf(
        beamTop to floatArrayOf(735f, -23f, 985f, 167f),
        beamBottom to floatArrayOf(735f, 189f, 985f, 379f),
        brakePool to floatArrayOf(-190f, 28f, 120f, 328f),
        reverseFloor to floatArrayOf(-160f, 88f, 40f, 268f),
        reflection to floatArrayOf(0f, CarAnimSpec.BASE_H - 10f, CarAnimSpec.BASE_W, CarAnimSpec.BASE_H + 86f),
        headTop to floatArrayOf(690f, 36f, 772f, 104f),
        headBottom to floatArrayOf(690f, 252f, 772f, 320f),
        tail to floatArrayOf(-16f, 22f, 64f, 334f),
        reverseTop to floatArrayOf(0f, 95f, 56f, 145f),
        reverseBottom to floatArrayOf(0f, 211f, 56f, 261f),
        turnLeft[0] to floatArrayOf(686f, 0f, 796f, 110f),
        turnLeft[1] to floatArrayOf(-30f, 0f, 80f, 110f),
        turnLeft[2] to floatArrayOf(476f, -18f, 544f, 50f),
        turnRight[0] to floatArrayOf(686f, 246f, 796f, 356f),
        turnRight[1] to floatArrayOf(-30f, 246f, 80f, 356f),
        turnRight[2] to floatArrayOf(471f, 306f, 539f, 374f),
        tires[0] to PartArt.tireViewBox(0),
        tires[1] to PartArt.tireViewBox(1),
        tires[2] to PartArt.tireViewBox(2),
        tires[3] to PartArt.tireViewBox(3),
        fuelCap to PartArt.capViewBox(),
        hood to PartArt.hoodBox(),
        glint to floatArrayOf(0f, 0f, CarAnimSpec.BASE_W, CarAnimSpec.BASE_H),
    )
    private var lights = CarLights.OFF
    /** Ekran görünür ve pencereye bağlı: yanıp sönme ve parlama yalnızca bu sırada çalışır. */
    private var active = false
    private var wantActive = false
    /**
     * Sinyal ritmi: yalnızca yanma / sönme anında iki alfa değişimi (LED gibi anlık); arada hiç kare çizilmez. İki taraf aynı
     * saatten beslenir (dörtlü aynı ritimde). [flashUntil] kilitlenince tek yanışın bitişi (0 = yok).
     */
    private var blinkOn = false
    private var blinking = false
    private var flashing = false
    private val blinkTick = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            applyBlink()
            postDelayed(this, if (blinkOn) BLINK_ON_MS else BLINK_OFF_MS)
        }
    }
    private val flashEnd = Runnable {
        flashing = false
        applyBlink()
    }
    private val glintRun = Runnable { startGlint() }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        for (v in under) addView(v)
        for (v in over) addView(v)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        bitmaps = CarBitmaps.get(context, CarColorPrefs.get(context))
        bitmaps?.let { reflection.setMask(it.reflection); hood.setMask(it.hoodMask) }
        glint.mask = bitmaps?.base
        for (i in parts.indices) {
            val s = specs[i]
            val shadow = s.shadow ?: continue
            shadows[i] = shadowBitmap(shadow)
            val rim = s.rim ?: continue
            rimPaints[i] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(rim.x - s.pivotX, 0f, rim.right - s.pivotX, 0f,
                    intArrayOf(0x00FFFFFF, 0xE6FFFFFF.toInt(), 0xE6FFFFFF.toInt(), 0x00FFFFFF),
                    floatArrayOf(0f, 0.25f, 0.8f, 1f), Shader.TileMode.CLAMP)
            }
        }
    }

    /** Ekrandaki ölçek: base pikselinden görünüm pikseline. */
    private val k: Float get() = width / (CarAnimSpec.BASE_W + CarAnimSpec.padLeft + CarAnimSpec.padRight)

    /** Verilen gövde genişliği için görünümün tam boyu ve gövdenin görünüm içindeki yeri (SahneStage yerleşimi). */
    companion object {
        fun widthFor(carW: Int): Int = (carW * (CarAnimSpec.BASE_W + CarAnimSpec.padLeft + CarAnimSpec.padRight) / CarAnimSpec.BASE_W).toInt()
        fun heightFor(carW: Int): Int = (carW * (CarAnimSpec.BASE_H + CarAnimSpec.padTop + CarAnimSpec.padBottom) / CarAnimSpec.BASE_W).toInt()
        fun carLeft(carW: Int): Int = (carW * CarAnimSpec.padLeft / CarAnimSpec.BASE_W).toInt()
        fun carTop(carW: Int): Int = (carW * CarAnimSpec.padTop / CarAnimSpec.BASE_W).toInt()
    }

    /**
     * Açık parçalar. [animate] false ise (ekran açılışındaki ilk durum) doğrudan son hal çizilir; aksi halde değişen her
     * parça kendi hareketiyle açılır/kapanır, yarıda gelen ters istek o anki halinden döner.
     */
    fun setOpen(open: Set<CarPart>, animate: Boolean) {
        val anim = animate && isAttachedToWindow && isLaidOut
        for (i in parts.indices) {
            val want = parts[i] in open
            val m = motions[i]
            if ((m.target == 1) == want && (anim || animators[i] == null)) continue
            animators[i]?.let { it.removeAllListeners(); it.cancel(); animators[i] = null; running-- }
            if (!anim) {
                m.snap(want)
                continue
            }
            val total = m.start(want)
            animators[i] = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
                duration = total
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    m.update(a.animatedValue as Float)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animators[i] === animation) {
                            animators[i] = null
                            running--
                        }
                    }
                })
                running++
                start()
            }
        }
        invalidate()
    }

    /**
     * Açık camlar ve tavan: üstlerine hafif buz mavisi (4 Kasım §1). [animate] false ise doğrudan son hal; aksi halde kısa
     * bir geçişle belirir / söner.
     */
    internal fun setGlass(windows: Collection<BodyPart>, sunroof: Boolean, animate: Boolean) {
        var changed = false
        for (i in glasses.indices) {
            val want = if (glassKeys[i] == null) sunroof else glassKeys[i] in windows
            val t = if (want) 1f else 0f
            if (glassTarget[i] != t) changed = true
            glassTarget[i] = t
        }
        if (!changed && glassAnim == null) return
        glassAnim?.let { it.removeAllListeners(); it.cancel() }
        glassAnim = null
        if (!(animate && isAttachedToWindow && isLaidOut)) {
            glassTarget.copyInto(glassNow)
            invalidate()
            return
        }
        glassNow.copyInto(glassFrom)
        glassAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CarAnimSpec.GLASS_FADE_MS
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                for (i in glasses.indices) glassNow[i] = glassFrom[i] + (glassTarget[i] - glassFrom[i]) * f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (glassAnim === animation) glassAnim = null
                }
            })
            start()
        }
    }

    /** Renk takımını değiştirir (tercih dinleyicisi ve ayar panelinden); görseller hemen değişir. */
    fun setColor(color: CarColor) {
        if (bitmaps?.color == color) return
        bitmaps = CarBitmaps.get(context, color)
        reflection.setMask(bitmaps?.reflection)
        hood.setMask(bitmaps?.hoodMask)
        glint.mask = bitmaps?.base
        invalidate()
    }

    /**
     * Dijital ikiz ışıkları (§11). [head] far huzmesi ve far lambaları (stoplar hafif yanar); [brake] stoplar parlar ve
     * arkada kırmızı zemin havuzu; [reverse] geri lambası ve arkada beyaz zemin ışığı; [turnLeft] / [turnRight] o taraftaki
     * köşelerde ve ayna sinyalinde kehribar yanıp sönme (ikisi birlikte = dörtlü, aynı ritimde); [hazardFlash] dörtlülerin
     * tek yanışı (kilitlenince). Değişmeyen durum hiçbir şey yapmaz; ekran görünmezken yalnızca durum saklanır.
     */
    fun setLights(head: Boolean, brake: Boolean, reverse: Boolean, turnLeft: Boolean, turnRight: Boolean, hazardFlash: Boolean = false) {
        val next = CarLights(head = head, brake = brake, reverse = reverse, left = turnLeft, right = turnRight)
        if (next != lights) {
            lights = next
            applyLights(animate = isAttachedToWindow && isLaidOut)
        }
        if (hazardFlash) flashHazard()
    }

    fun setLights(l: CarLights) = setLights(l.head, l.brake, l.reverse, l.blinkLeft, l.blinkRight)

    /** Dörtlülerin tek yanışı (kilitlenince); görünmezken ya da sinyal zaten yanıp sönerken atlanır. */
    fun flashHazard() {
        if (!active || blinking) return
        flashing = true
        applyBlink()
        removeCallbacks(flashEnd)
        postDelayed(flashEnd, CarAnimSpec.HAZARD_FLASH_MS)
    }

    /** Showroom zemini: arabanın altında çok hafif yansıma (Sahne; spor ekranında dönmüş araçta kapalı). Bir kez çizilir. */
    fun setFloorReflection(on: Boolean) {
        reflection.visibility = if (on) VISIBLE else GONE
    }

    private var tireWheel: Int? = null
    private val pulses = HashMap<View, Animator>(4)
    private var capOn = false
    private var hoodOn = false

    /**
     * Düşük lastik: o lastik gövde kenarının altından kehribar yanar ([wheel] [TireDecode] sırası; null ya da bilinmeyen =
     * hiçbiri). İlk çıkışta bir kez yavaş nabız, sonra sabit.
     */
    fun setTireAlert(wheel: Int?) {
        val next = wheel?.takeIf { it in 0..3 }
        if (next == tireWheel) return
        tireWheel = next
        for (i in tires.indices) showPart(tires[i], i == next, pulse = true)
    }

    /** Yakıt kapağı açık: kapak yerinde kehribar; [engine] motor suyu / akü uyarısı: kaput üstünde hafif kehribar (sabit). */
    fun setPartAlerts(fuelCapOpen: Boolean, engine: Boolean) {
        if (fuelCapOpen != capOn) {
            capOn = fuelCapOpen
            showPart(fuelCap, fuelCapOpen, pulse = true)
        }
        if (engine != hoodOn) {
            hoodOn = engine
            showPart(hood, engine, pulse = false)
        }
    }

    /** Parça ışığı yumuşakça belirir / söner (yalnızca görünüm alfası; donanım katmanı yalnız geçişte). */
    private fun showPart(v: View, on: Boolean, pulse: Boolean) {
        v.animate().cancel()
        pulses.remove(v)?.cancel()
        if (!(isAttachedToWindow && isLaidOut) || !ValueAnimator.areAnimatorsEnabled()) {
            v.alpha = if (on) 1f else 0f
            v.visibility = if (on) VISIBLE else GONE
            return
        }
        if (on) {
            v.visibility = VISIBLE
            if (pulse) {
                val a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 1f, PART_PULSE_LOW, 1f).apply {
                    duration = PART_PULSE_MS
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) { v.setLayerType(LAYER_TYPE_HARDWARE, null) }
                        override fun onAnimationEnd(animation: Animator) { v.setLayerType(LAYER_TYPE_NONE, null) }
                    })
                }
                pulses[v] = a
                a.start()
            } else {
                v.animate().alpha(1f).setDuration(PART_FADE_MS).withLayer().start()
            }
        } else {
            v.animate().alpha(0f).setDuration(PART_FADE_MS).withLayer().withEndAction { v.visibility = GONE }.start()
        }
    }

    /** Ekran görünür mü: false iken yanıp sönme, dörtlü ve parlama durur (durum korunur, görünür olunca sürer). */
    fun setActive(on: Boolean) {
        wantActive = on
        updateActive()
    }

    /** Görünüm (ya da bir atası, ör. spor ekranı açıkken Sahne) gizlenince hareket durur. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shownNow = isVisible
        updateActive()
    }

    private var shownNow = true

    private fun updateActive() {
        val now = wantActive && isAttachedToWindow && shownNow
        if (now == active) return
        active = now
        if (now) {
            applyLights(animate = false)
            scheduleGlint()
        } else {
            stopBlink()
            removeCallbacks(flashEnd)
            flashing = false
            applyBlink()
            removeCallbacks(glintRun)
            glint.stop()
        }
    }

    private fun applyLights(animate: Boolean) {
        val l = lights
        fade(beamTop, l.head, animate)
        fade(beamBottom, l.head, animate)
        fade(headTop, l.head, animate)
        fade(headBottom, l.head, animate)
        // Stoplar: farla birlikte hafif (park lambası), frende tam.
        fade(tail, l.head || l.brake, animate, if (l.brake) 1f else TAIL_POSITION_ALPHA)
        fade(brakePool, l.brake, animate)
        fade(reverseTop, l.reverse, animate)
        fade(reverseBottom, l.reverse, animate)
        fade(reverseFloor, l.reverse, animate)
        if (!active) return
        val want = l.left || l.right
        if (want && !blinking) {
            blinking = true
            flashing = false
            removeCallbacks(flashEnd)
            blinkOn = false
            blinkTick.run()
        } else if (!want && blinking) {
            stopBlink()
        }
        applyBlink()
    }

    private fun stopBlink() {
        blinking = false
        blinkOn = false
        removeCallbacks(blinkTick)
    }

    /** Sinyal görünümlerinin anlık hali: yanıp sönerken ritimdeki faz, tek yanışta açık, aksi halde gizli. */
    private fun applyBlink() {
        val l = lights
        side(turnLeft, if (blinking && l.left) blinkOn else flashing)
        side(turnRight, if (blinking && l.right) blinkOn else flashing)
    }

    private fun side(views: Array<LightSprite>, lit: Boolean) {
        for (t in views) {
            t.alpha = if (lit) 1f else 0f
            t.visibility = if (lit) VISIBLE else GONE
        }
    }

    /** Lamba / huzme yumuşakça yanar ya da söner; söndüğünde çizimden çıkar (GONE). */
    private fun fade(v: View, on: Boolean, animate: Boolean, level: Float = 1f) {
        val target = if (on) level else 0f
        v.animate().cancel()
        if (!animate) {
            v.alpha = target
            v.visibility = if (on) VISIBLE else GONE
            return
        }
        if (on) v.visibility = VISIBLE
        if (v.visibility != VISIBLE || v.alpha == target) {
            if (!on) v.visibility = GONE
            return
        }
        v.animate().alpha(target).setDuration(CarAnimSpec.LIGHT_FADE_MS).withEndAction { if (!on) v.visibility = GONE }.start()
    }

    private fun scheduleGlint() {
        removeCallbacks(glintRun)
        if (active) postDelayed(glintRun, CarAnimSpec.GLINT_EVERY_MS)
    }

    private fun startGlint() {
        if (!active) return
        // Kapı hareketi sürerken atlanır (iki hareket üst üste binmesin).
        if (running == 0 && width > 0) glint.play()
        scheduleGlint()
    }

    private fun sprite(mask: Bitmap?, color: Int) = LightSprite(context, mask, color)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val k = measuredWidth / (CarAnimSpec.BASE_W + CarAnimSpec.padLeft + CarAnimSpec.padRight)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val b = boxes[c] ?: continue
            c.measure(MeasureSpec.makeMeasureSpec(((b[2] - b[0]) * k).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(((b[3] - b[1]) * k).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val k = k
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val bx = boxes[c] ?: continue
            val x = ((bx[0] + CarAnimSpec.padLeft) * k).toInt()
            val y = ((bx[1] + CarAnimSpec.padTop) * k).toInt()
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    /** Çocuklar dokunuş almaz: araç dokunuşu (detay) görünümün kendisinde. */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = true

    /** Sıra: zemin ışıkları ve yansıma → gövde ve açılan parçalar → lambalar, halka ve parlama. */
    override fun dispatchDraw(canvas: Canvas) {
        val t = drawingTime
        for (v in under) if (v.visibility == VISIBLE) drawChild(canvas, v, t)
        drawCar(canvas)
        for (v in over) if (v.visibility == VISIBLE) drawChild(canvas, v, t)
    }

    val isAnimating: Boolean get() = running > 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        setColor(CarColorPrefs.get(context))
        updateActive()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        for (i in animators.indices) {
            animators[i]?.let { it.removeAllListeners(); it.end() }
            animators[i] = null
        }
        // Son hal korunur: kalan hareket bitmiş sayılır.
        motions.forEach { it.snap(it.target == 1) }
        glassAnim?.let { it.removeAllListeners(); it.cancel() }
        glassAnim = null
        glassTarget.copyInto(glassNow)
        running = 0
        super.onDetachedFromWindow()
        updateActive()
    }

    private fun drawCar(canvas: Canvas) {
        val b = bitmaps ?: return
        val k = k
        if (k <= 0f) return
        val save = canvas.save()
        canvas.scale(k, k)
        canvas.translate(CarAnimSpec.padLeft, CarAnimSpec.padTop)
        paint.alpha = 255
        b.base?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        drawGlass(canvas)
        // 1) Boşluklar (tüm parçalar), 2) parça + gölge + kenar ışığı, 3) açık hal fotoğrafları (anim.json zOrder).
        for (i in parts.indices) {
            val a = motions[i].gapAlpha()
            if (a <= 0.001f) continue
            val g = specs[i].gap
            drawAt(canvas, b.gap[i], g.x, g.y, a)
        }
        for (i in parts.indices) drawSprite(canvas, i, b)
        for (i in parts.indices) {
            val a = motions[i].overlayAlpha()
            if (a <= 0.001f) continue
            val o = specs[i].overlay
            drawAt(canvas, b.overlay[i], o.x, o.y, a)
        }
        canvas.restoreToCount(save)
    }

    /** Açık camların hafif rengi: gövdenin hemen üstünde (açılan kapının boşluğu ve parçası üstüne biner). */
    private fun drawGlass(canvas: Canvas) {
        glassPaint.color = Sahne.ICE
        for (i in glasses.indices) {
            val d = glassDoor[i]
            val a = CarAnimSpec.glassAlpha(glassNow[i], if (d >= 0) motions[d].x else 0f) * glasses[i].strength
            if (a <= 0.001f) continue
            val g = glasses[i].box
            glassPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            glassRect.set(g.x, g.y, g.right, g.bottom)
            canvas.drawRoundRect(glassRect, CarAnimSpec.GLASS_CORNER, CarAnimSpec.GLASS_CORNER, glassPaint)
        }
    }

    private fun drawSprite(canvas: Canvas, i: Int, b: CarBitmaps) {
        val m = motions[i]
        if (!m.spriteVisible()) return
        val s = specs[i]
        val so = m.spriteAlpha()
        val x = m.x
        val c = CarAnimSpec.clamp01(x)
        val save = canvas.save()
        when (s.kind) {
            CarAnimSpec.Kind.HINGE -> {
                canvas.translate(s.pivotX, s.pivotY)
                canvas.rotate(s.rotationEnd * x)
                canvas.scale(1f + (s.scaleXEnd - 1f) * c, 1f + (s.scaleYEnd - 1f) * c)
                val shadow = s.shadow
                val sh = shadows[i]
                if (shadow != null && sh != null) {
                    drawAt(canvas, sh, shadow.x - s.pivotX - SHADOW_PAD, shadow.y - s.pivotY - SHADOW_PAD, CarAnimSpec.SHADOW_MAX_ALPHA * c * so)
                }
                drawAt(canvas, b.sprite[i], s.sprite.x - s.pivotX, s.sprite.y - s.pivotY, so)
                val rim = s.rim
                val rp = rimPaints[i]
                if (rim != null && rp != null) {
                    rp.alpha = (255 * CarAnimSpec.RIM_MAX_ALPHA * c * so).toInt().coerceIn(0, 255)
                    rimRect.set(rim.x - s.pivotX, rim.y - s.pivotY, rim.right - s.pivotX, rim.bottom - s.pivotY)
                    canvas.drawRoundRect(rimRect, 1f, 1f, rp)
                }
            }
            CarAnimSpec.Kind.LID -> {
                // Kaput: ön cama doğru basılıp kısalır (ölçek kaput kenarındaki pivot etrafında, taşma dahil).
                canvas.translate(s.pivotX + s.translateXEnd * x, s.pivotY)
                canvas.scale(1f + (s.scaleXEnd - 1f) * x, 1f + (s.scaleYEnd - 1f) * x)
                drawAt(canvas, b.sprite[i], s.sprite.x - s.pivotX, s.sprite.y - s.pivotY, so)
            }
            CarAnimSpec.Kind.SLIDE_FADE -> {
                canvas.translate(s.translateXEnd * x, 0f)
                drawAt(canvas, b.sprite[i], s.sprite.x, s.sprite.y, so)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawAt(canvas: Canvas, bmp: Bitmap?, x: Float, y: Float, alpha: Float) {
        bmp ?: return
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, x, y, paint)
    }

    /** Kapı gölgesi: bulanık yuvarlak dikdörtgen bir kez çizilir (bulanıklık asla canlandırılmaz). */
    private fun shadowBitmap(r: CarAnimSpec.Box): Bitmap? = try {
        val w = (r.w + 2 * SHADOW_PAD).toInt() + 1
        val h = (r.h + 2 * SHADOW_PAD).toInt() + 1
        Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { bmp ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                maskFilter = BlurMaskFilter(SHADOW_BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(bmp).drawRoundRect(RectF(SHADOW_PAD, SHADOW_PAD, SHADOW_PAD + r.w, SHADOW_PAD + r.h),
                CarAnimSpec.SHADOW_CORNER, CarAnimSpec.SHADOW_CORNER, p)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Bir renk takımının çözülmüş görselleri (parça sırası [CarPart.values]). */
    private class CarBitmaps(val color: CarColor, val base: Bitmap?, val sprite: Array<Bitmap?>, val gap: Array<Bitmap?>, val overlay: Array<Bitmap?>) {
        /** Zemindeki yansıma: gövdenin alt kenarının küçük, ters ve aşağı doğru sönen kopyası (renk başına bir kez). */
        val reflection: Bitmap? = base?.let { reflectionOf(it) }
        /** Kaput ışığı maskesi: gövde alfası × yumuşak elips (renk başına bir kez, küçük ALPHA_8). */
        val hoodMask: Bitmap? by lazy { base?.let { PartArt.hoodMaskOf(it) } }

        companion object {
            /** Süreç boyunca yalnızca son kullanılan renk tutulur; tema değişimiyle yeniden kurulan ekran yeniden çözmez. */
            @Volatile private var cached: CarBitmaps? = null

            fun get(context: Context, color: CarColor): CarBitmaps {
                cached?.takeIf { it.color == color }?.let { return it }
                val res = context.resources
                // Kaynak tablosunun paket adı (launcher test APK'sında uygulama kimliğinden farklı olabilir).
                val pkg = res.getResourcePackageName(R.drawable.car_grafit_base)
                fun load(name: String): Bitmap? = try {
                    val id = res.getIdentifier("car_${color.key}_$name", "drawable", pkg)
                    if (id == 0) null else BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
                } catch (e: OutOfMemoryError) {
                    null
                }
                val all = CarPart.values()
                return CarBitmaps(
                    color,
                    load("base"),
                    Array(all.size) { load("sprite_" + all[it].asset) },
                    Array(all.size) { load("gap_" + all[it].asset) },
                    Array(all.size) { load("overlay_" + all[it].asset) },
                ).also { cached = it }
            }
        }
    }
}

/** Yansıma bitmap'i: base'in 1/4 genişliğinde, alt kenardan [REFL_H] satır; üstte %40, aşağı doğru sıfıra söner. */
private fun reflectionOf(base: Bitmap): Bitmap? = try {
    val s = REFL_W / CarAnimSpec.BASE_W
    Bitmap.createBitmap(REFL_W.toInt(), REFL_H, Bitmap.Config.ARGB_8888).also { out ->
        val c = Canvas(out)
        c.save()
        c.scale(s, -s)
        c.translate(0f, -CarAnimSpec.BASE_H)
        c.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        c.drawRect(0f, 0f, REFL_W, REFL_H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, REFL_H.toFloat(), 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
    }
} catch (e: OutOfMemoryError) {
    null
}

private const val REFL_W = 192f
private const val REFL_H = 24

/**
 * Işık maskeleri (süreç başına bir kez, paylaşılan ALPHA_8): [glow] yumuşak yuvarlak (kutuya gerilince elips), [hot] daha
 * dolu çekirdekli yuvarlak (sinyal), [beam] far huzmesi (solda dar, sağa açılıp sönen koni). Renk çizim boyasından gelir;
 * toplam ~16 KB.
 */
private object LightMasks {
    val glow: Bitmap? by lazy { radial(intArrayOf(0xFF000000.toInt(), 0x8C000000.toInt(), 0x33000000, 0x0D000000, 0)) }

    val hot: Bitmap? by lazy { radial(intArrayOf(0xFF000000.toInt(), 0xE0000000.toInt(), 0x80000000.toInt(), 0x26000000, 0)) }

    private fun radial(colors: IntArray): Bitmap? = try {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8).also {
            Canvas(it).drawCircle(32f, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(32f, 32f, 32f, colors, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
            })
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    val beam: Bitmap? by lazy {
        try {
            val w = 128
            val h = 64
            val px = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val u = x / (w - 1f)
                val v = (y - (h - 1) / 2f) / ((h - 1) / 2f)
                val spread = 0.16f + 0.84f * u
                val across = 1f - (v / spread) * (v / spread)
                if (across <= 0f) continue
                val along = (1f - u).let { it * it } * CarAnimSpec.smooth(u, 0f, 0.1f)
                px[y * w + x] = (255 * across * across * along).toInt().coerceIn(0, 255).toByte()
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px)) }
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/**
 * Tek ışık katmanı: [mask] (ALPHA_8 ise boyanın rengiyle, ARGB ise olduğu gibi) görünüm kutusuna gerilir. İçerik bir kez
 * kaydedilir; yalnızca görünüm alfası canlanır. Çakışan çizim yok ([hasOverlappingRendering] false): alfa ekran dışı katman
 * açmadan doğrudan boyaya uygulanır.
 */
private class LightSprite(context: Context, private var mask: Bitmap?, color: Int) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val dst = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        visibility = GONE
    }

    fun setMask(m: Bitmap?) {
        if (m === mask) return
        mask = m
        invalidate()
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val m = mask ?: return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(m, null, dst, paint)
    }
}

/**
 * Boya parlaması: gövde kutusunda çapraz, çok yumuşak beyaz bir bant [CarAnimSpec.GLINT_MS] boyunca soldan sağa adım adım
 * ([CarAnimSpec.GLINT_STEP_MS]) geçer;
 * gövde dışına taşmaz (base görselinin alfasıyla DST_IN maske). Yalnızca geçiş sırasında görünür ve donanım katmanındadır
 * (bant yalnızca konum değiştirir); arada GONE, katman bırakılır.
 */
private class GlintLayer(context: Context) : ViewGroup(context) {
    var mask: Bitmap? = null
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dst = RectF()
    private val band = object : View(context) {
        private val p = Paint()
        private var built = -1
        override fun hasOverlappingRendering(): Boolean = false
        override fun onDraw(canvas: Canvas) {
            if (built != width) {
                built = width
                val peak = if (LauncherTheme.night) 0x2EFFFFFF else 0x4DFFFFFF
                p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0, peak, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        }
    }
    private val ease = AccelerateDecelerateInterpolator()
    private var startedAt = 0L
    private var playing = false
    /** Adımlı geçiş ([CarAnimSpec.GLINT_STEP_MS]): her adımda yalnızca bandın konumu değişir. */
    private val step = object : Runnable {
        override fun run() {
            val f = (android.os.SystemClock.uptimeMillis() - startedAt) / CarAnimSpec.GLINT_MS.toFloat()
            if (f >= 1f) {
                stop()
                return
            }
            band.translationX = -band.width + ease.getInterpolation(f) * (width + band.width)
            postDelayed(this, CarAnimSpec.GLINT_STEP_MS)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        clipChildren = true
        addView(band)
    }

    fun play() {
        if (mask == null || playing) return
        playing = true
        band.translationX = -band.width.toFloat()
        visibility = VISIBLE
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startedAt = android.os.SystemClock.uptimeMillis()
        post(step)
    }

    fun stop() {
        playing = false
        removeCallbacks(step)
        if (visibility == GONE) return
        visibility = GONE
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        // Bant gövde genişliğinin %40'ı, eğik durduğundan yüksekliği taşar.
        band.measure(MeasureSpec.makeMeasureSpec((w * 0.4f).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((h * 1.8f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = -(band.measuredHeight - (b - t)) / 2
        band.layout(0, top, band.measuredWidth, top + band.measuredHeight)
        band.pivotX = band.measuredWidth / 2f
        band.pivotY = band.measuredHeight / 2f
        band.rotation = 18f
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val m = mask ?: return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(m, null, dst, maskPaint)
    }
}

/** Sinyalin yanık ve sönük süreleri ([CarAnimSpec.BLINK_MS] ritmi, [CarAnimSpec.BLINK_ON] yanık oran). */
private val BLINK_ON_MS = (CarAnimSpec.BLINK_MS * CarAnimSpec.BLINK_ON).toLong()
private val BLINK_OFF_MS = CarAnimSpec.BLINK_MS - BLINK_ON_MS

// Işık renkleri: alfa kanalı ışığın tam parlaklığıdır, görünüm alfası 0..1 açar / kapatır.
private val HEAD_BEAM: Int get() = if (LauncherTheme.night) 0xB3DCE8FF.toInt() else 0x73DCE8FF
private const val HEAD_LAMP = 0xE6EAF2FF.toInt()
private const val TAIL_LAMP = 0xFFFF2A20.toInt()
private const val BRAKE_POOL = 0x80FF2A20.toInt()
private const val REVERSE_LAMP = 0xE6F2F6FF.toInt()
private const val REVERSE_FLOOR = 0x6BF2F6FF
private const val TURN = 0xFFFFA24A.toInt()
/** Kaput ışığı (motor suyu / akü): maskenin en dolu yerinde ~%45 kehribar. */
private const val HOOD_GLOW = 0x73FFA040
private const val PART_FADE_MS = 360L
private const val PART_PULSE_MS = 1_800L
private const val PART_PULSE_LOW = 0.5f
/** Farla birlikte stopların park lambası parlaklığı (frende 1). */
private const val TAIL_POSITION_ALPHA = 0.35f
/** Yansımanın görünüm alfası (bitmap'te en çok %40; toplam ~%9). */
private const val REFLECTION_ALPHA = 0.22f

/** Gölge bitmap'inin bulanıklık payı (base px) ve BlurMaskFilter yarıçapı (CSS blur σ ≈ 7.1 px → yarıçap ≈ σ / 0.57735). */
private const val SHADOW_PAD = 24f
private const val SHADOW_BLUR_RADIUS = 12f

/**
 * Parça ışıkları (süreç başına bir kez, küçük bitmap'ler): [tire] üstten görülen lastik (yuvarlak köşeli dikdörtgen, sıcak
 * kehribar dolgu, çevresinde yumuşak ışıma; dört teker aynı bitmap'i paylaşır), [fuelCap] küçük yakıt kapağı ve ışıması.
 * Kutular base pikselinde; ışıma payı [GLOW_PAD].
 */
private object PartArt {
    const val GLOW_PAD = 22f
    private const val CAP_PAD = 12f
    private const val AMBER = 0xFFFFA040.toInt()

    fun tireViewBox(i: Int): FloatArray = CarAnimSpec.tireBox(i).let { floatArrayOf(it[0] - GLOW_PAD, it[1] - GLOW_PAD, it[2] + GLOW_PAD, it[3] + GLOW_PAD) }

    fun capViewBox(): FloatArray = CarAnimSpec.FUEL_CAP.let { floatArrayOf(it.x - CAP_PAD, it.y - CAP_PAD, it.right + CAP_PAD, it.bottom + CAP_PAD) }

    fun hoodBox(): FloatArray = floatArrayOf(
        CarAnimSpec.HOOD_CX - CarAnimSpec.HOOD_RX, CarAnimSpec.HOOD_CY - CarAnimSpec.HOOD_RY,
        CarAnimSpec.HOOD_CX + CarAnimSpec.HOOD_RX, CarAnimSpec.HOOD_CY + CarAnimSpec.HOOD_RY,
    )

    val tire: Bitmap? by lazy { lit(CarAnimSpec.TIRE_L, CarAnimSpec.TIRE_W, GLOW_PAD, 10f, 16f) }

    val fuelCap: Bitmap? by lazy { lit(CarAnimSpec.FUEL_CAP.w, CarAnimSpec.FUEL_CAP.h, CAP_PAD, 4f, 8f) }

    /**
     * Işıyan parça: iki kat bulanık kehribar ışıma, üstünde parça. Dolgu uzunlamasına tonlu (uçlar koyu, orta açık: lastik
     * sırtının yuvarlaklığı), üstte ince parlak çizgi. Base pikseli ölçeğinde.
     */
    private fun lit(w: Float, h: Float, pad: Float, corner: Float, blur: Float): Bitmap? = try {
        Bitmap.createBitmap((w + 2 * pad).toInt(), (h + 2 * pad).toInt(), Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val r = RectF(pad, pad, pad + w, pad + h)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (AMBER and 0x00FFFFFF) or 0xCC000000.toInt()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(r, corner, corner, glow)
            glow.maskFilter = BlurMaskFilter(blur * 0.5f, BlurMaskFilter.Blur.OUTER)
            c.drawRoundRect(r, corner, corner, glow)
            c.drawRoundRect(r, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(r.left, 0f, r.right, 0f,
                    intArrayOf(0xFFD8701E.toInt(), 0xFFFFB45C.toInt(), AMBER, 0xFFFFB45C.toInt(), 0xFFD8701E.toInt()),
                    floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
            })
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Kaput maskesi: base alfası (kaput bölgesi) × elips içinde yumuşak sönüm; ALPHA_8, ~60 KB, renk başına bir kez. */
    fun hoodMaskOf(base: Bitmap): Bitmap? = try {
        val b = hoodBox()
        val x0 = b[0].toInt().coerceIn(0, base.width - 1)
        val y0 = b[1].toInt().coerceIn(0, base.height - 1)
        val w = (b[2].toInt() - x0).coerceAtMost(base.width - x0)
        val h = (b[3].toInt() - y0).coerceAtMost(base.height - y0)
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, x0, y0, w, h)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x0 + x - CarAnimSpec.HOOD_CX) / CarAnimSpec.HOOD_RX
            val v = (y0 + y - CarAnimSpec.HOOD_CY) / CarAnimSpec.HOOD_RY
            val d = 1f - (u * u + v * v)
            if (d <= 0f) continue
            val a = (px[y * w + x] ushr 24) / 255f
            out[y * w + x] = (255f * a * d * d).toInt().coerceIn(0, 255).toByte()
        }
        // Görünüm kutusu tam elips kutusudur; kırpılan kenar varsa maske kutuya gerilir (kaput base içinde, kırpma yok).
        Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out)) }
    } catch (e: OutOfMemoryError) {
        null
    }
}
