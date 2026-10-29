package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.LinearLayout

/** Izgara, yan yana duran kutucukları tek şerit gibi göstermek için bu arayüze komşu kenarları bildirir. */
interface Joinable {
    fun setJoins(left: Boolean, top: Boolean, right: Boolean, bottom: Boolean, diagonals: BooleanArray = NO_DIAGONALS)

    companion object {
        /** Çapraz komşu yok (sol-üst, sağ-üst, sağ-alt, sol-alt sırasıyla). */
        val NO_DIAGONALS = BooleanArray(4)
    }
}

/**
 * Kart yüzeyini kendisi çizen kutucuk kabuğu. Komşu kenarlarda yüzey, ızgara boşluğunun yarısı kadar
 * dışarı uzanır (komşusuyla ortada buluşur, aralarında boşluk kalmaz); köşeler yalnızca iki kenarı da
 * serbest olan köşelerde yuvarlaktır; komşu kenarın ortasına kısa, ince bir ayraç çizilir.
 * İçerik (ikon + etiket) yerinde kalır; yalnızca arka plan genişler. Üst kapların kırpması kapalı olmalı.
 */
class JoinedCard(context: Context, paddingDp: Int, private val gapPx: Int) : LinearLayout(context), Joinable {

    private var joinL = false
    private var joinT = false
    private var joinR = false
    private var joinB = false

    /** Çapraz komşular (sol-üst, sağ-üst, sağ-alt, sol-alt): L biçiminde içbükey köşe boş kalsın diye. */
    private val diag = BooleanArray(4)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(LauncherTheme.BORDER_DP.toFloat())
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = context.dpF(1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val radius = context.dpF(LauncherTheme.CARD_RADIUS_DP)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val p = context.dp(paddingDp)
        setPadding(p, p, p, p)
    }

    override fun setJoins(left: Boolean, top: Boolean, right: Boolean, bottom: Boolean, diagonals: BooleanArray) {
        if (left == joinL && top == joinT && right == joinR && bottom == joinB && diagonals.contentEquals(diag)) return
        joinL = left; joinT = top; joinR = right; joinB = bottom
        diagonals.copyInto(diag)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val half = gapPx / 2f
        // Yüzeyler 1 px üst üste biner: kesirli piksel sınırında ince bir çizgi (dikiş) görünmesin.
        val el = if (joinL) half + 1f else 0f
        val et = if (joinT) half + 1f else 0f
        val er = if (joinR) half + 1f else 0f
        val eb = if (joinB) half + 1f else 0f
        val w = width.toFloat()
        val h = height.toFloat()

        // Kenarlık, komşu kenarda görünmesin diye çizim alanı (kırpma) kendi yarım boşluğuyla sınırlanır.
        canvas.save()
        canvas.clipRect(-el, -et, w + er, h + eb)
        // İçbükey köşe (iki bitişik kenar komşu, çapraz boş): boşluğun o çeyreği doldurulmaz.
        if (joinT && joinL && !diag[0]) canvas.clipOutRect(-el, -et, 0f, 0f)
        if (joinT && joinR && !diag[1]) canvas.clipOutRect(w, -et, w + er, 0f)
        if (joinB && joinR && !diag[2]) canvas.clipOutRect(w, h, w + er, h + eb)
        if (joinB && joinL && !diag[3]) canvas.clipOutRect(-el, h, 0f, h + eb)

        fill.color = LauncherTheme.CARD
        rect.set(-el, -et, w + er, h + eb)
        path.reset()
        path.addRoundRect(rect, radii(), Path.Direction.CW)
        canvas.drawPath(path, fill)

        val border = LauncherTheme.CARD_BORDER
        if (border != 0) {
            stroke.color = border
            val s = stroke.strokeWidth
            val inset = s / 2
            // Komşu kenarda kenarlık kırpma dışına itilir: ortada çift çizgi oluşmaz.
            rect.set(
                if (joinL) -el - s else inset, if (joinT) -et - s else inset,
                if (joinR) w + er + s else w - inset, if (joinB) h + eb + s else h - inset,
            )
            path.reset()
            path.addRoundRect(rect, radii(), Path.Direction.CW)
            canvas.drawPath(path, stroke)
        }
        canvas.restore()

        // Ayraç: her komşu kenarda, boşluğun ortasında, kısa ve ince. Sonra çizilen komşunun yüzeyi bu çizginin
        // yarısını örter; komşu da aynı çizgiyi kendi tarafından çizdiği için çizgi bütün görünür.
        divider.color = LauncherTheme.TRACK
        val vLen = h * DIVIDER_FRACTION
        val hLen = w * DIVIDER_FRACTION
        if (joinR) canvas.drawLine(w + half, (h - vLen) / 2, w + half, (h + vLen) / 2, divider)
        if (joinL) canvas.drawLine(-half, (h - vLen) / 2, -half, (h + vLen) / 2, divider)
        if (joinB) canvas.drawLine((w - hLen) / 2, h + half, (w + hLen) / 2, h + half, divider)
        if (joinT) canvas.drawLine((w - hLen) / 2, -half, (w + hLen) / 2, -half, divider)
    }

    /** Köşe yarıçapları (sol-üst, sağ-üst, sağ-alt, sol-alt): iki bitişik kenardan biri komşuluysa düz. */
    private fun radii(): FloatArray {
        val tl = if (joinL || joinT) 0f else radius
        val tr = if (joinR || joinT) 0f else radius
        val br = if (joinR || joinB) 0f else radius
        val bl = if (joinL || joinB) 0f else radius
        return floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    }

    private companion object {
        const val DIVIDER_FRACTION = 0.4f
    }
}
