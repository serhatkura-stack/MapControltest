package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mapcontrol.R

/**
 * Yalnızca hata ayıklama: Desay'ın uygulamaların üstüne çizdiği alt çubuğun görsel taklidi — aracın gerçek
 * silueti ([DockShape]: eğik uçlu gövde, ortada daha açık yamuk) ve OEM x konumlarındaki simgeler
 * (ana ekran · uygulamalar · araç · sıcaklık · fan · sıcaklık · ayarlar · telefon). Dokunulmaz; yalnızca
 * `launcher_oem_inset 110` ekstrasıyla eklenir. Renkler sabit (araç fotoğrafındaki gri-mavi), temadan bağımsız.
 */
object OemBarMock {

    private const val ICON_DP = 36
    private const val TEMP_SP = 38f

    fun build(context: Context): View {
        val tint = 0xFFF1F4F8.toInt()
        val bar = DockLayout(context).apply {
            background = MockSurface()
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun ic(res: Int, sizeDp: Int = ICON_DP) = icon(context, res, sizeDp, tint)
        fun slot(cx: Float, w: Float, vararg views: View) = bar.addCentered(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                views.forEach { addView(it) }
            }, cx, w)
        fun temp(cx: Float) {
            slot(cx, 240f,
                ic(R.drawable.ic_mdi_chevron_left, 34),
                text(context, TEMP_SP, tint, bold = true).apply { text = "26.0°C"; gravity = Gravity.CENTER; minWidth = context.dp(142) },
                ic(R.drawable.ic_mdi_chevron_right, 34))
        }

        slot(212f, 112f, ic(R.drawable.ic_mdi_home))
        slot(338f, 112f, ic(R.drawable.ic_mdi_view_grid))
        slot(464f, 112f, ic(R.drawable.ic_mdi_car))
        temp(750f)
        slot(960f, 180f, ic(R.drawable.ic_mdi_fan))
        temp(1170f)
        slot(1456f, 112f, ic(R.drawable.ic_mdi_cog))
        slot(1582f, 112f, ic(R.drawable.ic_mdi_steering))
        slot(1708f, 112f, ic(R.drawable.ic_mdi_phone))

        return FrameLayout(context).apply {
            isClickable = false
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(text(context, 12f, 0xFF9CA3AF.toInt()).apply { text = context.getString(R.string.launcher_oem_mock_label) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                    .apply { setMargins(0, context.dp(2), context.dp(16), 0) })
        }
    }

    /** [DockShape] ile aynı silüet, sabit gri-mavi renklerle (yan ≈ RGB 91,101,123; orta ≈ 1,7 kat açık). */
    private class MockSurface : Drawable() {
        private val side = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mid = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xB0FFFFFF.toInt(); strokeWidth = 1.5f }
        private var outer = android.graphics.Path()
        private var inner = android.graphics.Path()
        private var innerRim = android.graphics.Path()

        override fun onBoundsChange(b: Rect) {
            val sx = b.width() / DockShape.REF_W
            val sy = b.height() / DockShape.REF_H
            val top = b.top + DockShape.BODY_TOP * sy
            val bottom = b.top + DockShape.BODY_BOTTOM * sy
            side.shader = LinearGradient(0f, top, 0f, bottom, 0xFF8D9AB3.toInt(), 0xFF626E86.toInt(), Shader.TileMode.CLAMP)
            mid.shader = LinearGradient(0f, top, 0f, bottom, 0xFFCBD7E8.toInt(), 0xFF8696B0.toInt(), Shader.TileMode.CLAMP)
            outer = DockShape.outer(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
            inner = DockShape.mid(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
            innerRim = DockShape.midRim(sx, sy).also { it.offset(b.left.toFloat(), b.top.toFloat()) }
        }

        override fun draw(canvas: Canvas) {
            val sy = bounds.height() / DockShape.REF_H
            for (i in 1..6) { canvas.save(); canvas.translate(0f, i * 1.7f * sy); canvas.drawPath(outer, shade); canvas.restore() }
            canvas.drawPath(outer, side)
            for (i in 1..3) { canvas.save(); canvas.translate(0f, i * 1.2f * sy); canvas.drawPath(inner, shade); canvas.restore() }
            canvas.drawPath(inner, mid)
            canvas.drawPath(innerRim, rim)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
