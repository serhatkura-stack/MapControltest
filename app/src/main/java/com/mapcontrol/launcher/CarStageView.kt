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
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
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
 */
class CarStageView(context: Context) : View(context) {

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

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        bitmaps = CarBitmaps.get(context, CarColorPrefs.get(context))
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
        invalidate()
    }

    val isAnimating: Boolean get() = running > 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        setColor(CarColorPrefs.get(context))
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
    }

    override fun onDraw(canvas: Canvas) {
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

/** Gölge bitmap'inin bulanıklık payı (base px) ve BlurMaskFilter yarıçapı (CSS blur σ ≈ 7.1 px → yarıçap ≈ σ / 0.57735). */
private const val SHADOW_PAD = 24f
private const val SHADOW_BLUR_RADIUS = 12f
