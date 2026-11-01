package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.hypot
import kotlin.math.min

/**
 * Aracın (Tiggo 7) alt çubuğunun silueti, 1920x110 başvuru bandında (OEM gezinme penceresi y 610–720):
 * gövde y 21–98 (78), uçları ~45° eğik dış yamuk ve ortada ayrı, daha açık sıcaklık·fan yamuğu. Ölçüler araç
 * fotoğrafından (katalog/oem-alt-cubuk-olculeri.md); çizim bize ait. Her şey başvurunun oranı olarak ölçeklenir.
 */
internal object DockShape {
    const val REF_W = 1920f
    const val REF_H = 110f
    const val BODY_TOP = 21f
    const val BODY_BOTTOM = 98f

    /** Dış gövde köşeleri: sol-üst, sağ-üst, sağ-alt, sol-alt. */
    private val OUTER = floatArrayOf(117f, 21f, 1803f, 21f, 1886f, 98f, 34f, 98f)
    private val OUTER_R = floatArrayOf(22f, 22f, 12f, 12f)

    /** Orta yamuk köşeleri: sol-üst, sağ-üst, sağ-alt, sol-alt. */
    private val MID = floatArrayOf(630f, 21f, 1290f, 21f, 1367f, 98f, 553f, 98f)
    private val MID_R = floatArrayOf(22f, 22f, 10f, 10f)

    /** Pencere bandının yüksekliği (px): 110 başvuru birimi, ekran genişliğine oranla. 1920x720'de tam 110. */
    fun bandPx(context: Context): Int {
        val dm = context.resources.displayMetrics
        return Math.round(REF_H * maxOf(dm.widthPixels, dm.heightPixels) / REF_W)
    }

    fun outer(sx: Float, sy: Float): Path = poly(OUTER, OUTER_R, sx, sy, closed = true)
    fun mid(sx: Float, sy: Float): Path = poly(MID, MID_R, sx, sy, closed = true)

    /** Orta yamuğun açık hattı (alt kenarsız): parıltı çizgisi üst ve eğik kenarlarda. */
    fun midRim(sx: Float, sy: Float): Path {
        val v = floatArrayOf(MID[6], MID[7], MID[0], MID[1], MID[2], MID[3], MID[4], MID[5])
        return poly(v, floatArrayOf(0f, MID_R[0], MID_R[1], 0f), sx, sy, closed = false)
    }

    /** Köşeleri ikinci derece eğriyle yuvarlanmış çokgen; [r] her köşenin kesme uzaklığı (başvuru birimi). */
    private fun poly(pts: FloatArray, r: FloatArray, sx: Float, sy: Float, closed: Boolean): Path {
        val n = pts.size / 2
        val path = Path()
        for (i in 0 until n) {
            val cx = pts[2 * i] * sx
            val cy = pts[2 * i + 1] * sy
            if (!closed && (i == 0 || i == n - 1)) {
                if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
                continue
            }
            val pi = (i + n - 1) % n
            val ni = (i + 1) % n
            val px = pts[2 * pi] * sx; val py = pts[2 * pi + 1] * sy
            val nx = pts[2 * ni] * sx; val ny = pts[2 * ni + 1] * sy
            val dp = hypot(px - cx, py - cy)
            val dn = hypot(nx - cx, ny - cy)
            val d = min(r[i] * sx, min(dp, dn) / 2f)
            val ax = cx + (px - cx) / dp * d; val ay = cy + (py - cy) / dp * d
            val bx = cx + (nx - cx) / dn * d; val by = cy + (ny - cy) / dn * d
            if (path.isEmpty) path.moveTo(ax, ay) else path.lineTo(ax, ay)
            path.quadTo(cx, cy, bx, by)
        }
        if (closed) path.close()
        return path
    }
}

/**
 * Siluetin yüzeyi: yumuşak alt gölge, yanlar için panel tonunda düşey gradyan, ortada daha açık yamuk (üst ve eğik
 * kenarlarında ince parıltı, altında hafif gölge). Sınırlar bandın tamamıdır (başvuru 1920x110'a ölçeklenir).
 */
internal class DockSurface(private val context: Context) : Drawable() {
    private val side = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0 }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var outer = Path()
    private var inner = Path()
    private var innerRim = Path()

    fun applyTheme() {
        if (!bounds.isEmpty) onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun onBoundsChange(b: Rect) {
        val sx = b.width() / DockShape.REF_W
        val sy = b.height() / DockShape.REF_H
        val top = b.top + DockShape.BODY_TOP * sy
        val bottom = b.top + DockShape.BODY_BOTTOM * sy
        side.shader = LinearGradient(0f, top, 0f, bottom, LauncherTheme.DOCK_SIDE_TOP, LauncherTheme.DOCK_SIDE_BOTTOM, Shader.TileMode.CLAMP)
        mid.shader = LinearGradient(0f, top, 0f, bottom, LauncherTheme.DOCK_MID_TOP, LauncherTheme.DOCK_MID_BOTTOM, Shader.TileMode.CLAMP)
        rim.color = LauncherTheme.DOCK_RIM
        rim.strokeWidth = maxOf(1f, context.dpF(1f))
        edge.color = LauncherTheme.DOCK_BORDER
        edge.strokeWidth = maxOf(1f, context.dpF(1f))
        outer = DockShape.outer(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
        inner = DockShape.mid(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
        innerRim = DockShape.midRim(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val sy = bounds.height() / DockShape.REF_H
        // Dış gölge: bandın alt payına doğru solan birkaç kaydırılmış koyu katman.
        shade.color = LauncherTheme.DOCK_SHADOW
        for (i in 1..SHADOW_STEPS) {
            canvas.save()
            canvas.translate(0f, i * 1.7f * sy)
            canvas.drawPath(outer, shade)
            canvas.restore()
        }
        canvas.drawPath(outer, side)
        if (edge.color != 0) canvas.drawPath(outer, edge)
        // Orta bölümün yan bölümlere düşen yumuşak gölgesi.
        shade.color = LauncherTheme.DOCK_SHADOW
        for (i in 1..MID_SHADOW_STEPS) {
            canvas.save()
            canvas.translate(0f, i * 1.2f * sy)
            canvas.drawPath(inner, shade)
            canvas.restore()
        }
        canvas.drawPath(inner, mid)
        canvas.drawPath(innerRim, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SHADOW_STEPS = 6
        const val MID_SHADOW_STEPS = 3
    }
}

/**
 * Başvuru bandı (1920x110) koordinatlarında kutu verilerek çocukları yerleştiren düzen: aynı oran her ekranda
 * korunur ([DockShape]). Çocuk kutuları genişliğe göre ölçeklenir.
 */
internal class DockLayout(context: Context) : ViewGroup(context) {
    private class Box(val l: Float, val t: Float, val r: Float, val b: Float)
    private val boxes = HashMap<View, Box>()

    fun addAt(child: View, left: Float, top: Float, right: Float, bottom: Float) {
        boxes[child] = Box(left, top, right, bottom)
        addView(child)
    }

    /** Merkez x, yüksekliği gövdenin tamamı olan [width] genişlikli kutu. */
    fun addCentered(child: View, cx: Float, width: Float) =
        addAt(child, cx - width / 2, DockShape.BODY_TOP, cx + width / 2, DockShape.BODY_BOTTOM)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val sx = w / DockShape.REF_W
        val sy = h / DockShape.REF_H
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val bx = boxes[c] ?: continue
            c.measure(
                MeasureSpec.makeMeasureSpec(Math.round((bx.r - bx.l) * sx), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round((bx.b - bx.t) * sy), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sx = (r - l) / DockShape.REF_W
        val sy = (b - t) / DockShape.REF_H
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val bx = boxes[c] ?: continue
            c.layout(Math.round(bx.l * sx), Math.round(bx.t * sy), Math.round(bx.l * sx) + c.measuredWidth, Math.round(bx.t * sy) + c.measuredHeight)
        }
    }
}
