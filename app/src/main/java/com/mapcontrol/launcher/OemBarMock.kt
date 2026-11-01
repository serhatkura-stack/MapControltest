package com.mapcontrol.launcher

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mapcontrol.R

/**
 * Yalnızca hata ayıklama: Desay'ın uygulamaların üstüne çizdiği alt çubuğun görsel taklidi
 * (ana ekran · uygulamalar · araç · sıcaklık · fan · sıcaklık · ayarlar · telefon). Emülatörde ayrılan alt
 * bandın nasıl görüneceğini gösterir. Dokunulmaz; yalnızca `launcher_oem_inset` ekstrasıyla eklenir.
 */
object OemBarMock {

    private const val BAR_ALPHA = 0xE6
    private const val ICON_DP = 36
    private const val TEMP_SP = 22f

    fun build(context: Context): View {
        val tint = 0xFFE5E7EB.toInt()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, Color.argb(BAR_ALPHA, 0x0B, 0x0D, 0x11), 20f)
            setPadding(context.dp(32), 0, context.dp(32), 0)
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun slot(view: View) {
            bar.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun ic(res: Int) = icon(context, res, ICON_DP, tint).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP), 0f)
        }
        fun centered(vararg views: View) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            views.forEach { addView(it) }
        }
        fun temp() = centered(
            text(context, TEMP_SP, tint).apply { text = "‹  26.0°C  ›" },
        )

        slot(centered(ic(R.drawable.ic_mdi_home)))
        slot(centered(ic(R.drawable.ic_mdi_view_grid)))
        slot(centered(ic(R.drawable.ic_mdi_car)))
        slot(temp())
        slot(centered(ic(R.drawable.ic_mdi_fan)))
        slot(temp())
        slot(centered(ic(R.drawable.ic_mdi_cog)))
        slot(centered(ic(R.drawable.ic_mdi_phone)))

        return FrameLayout(context).apply {
            isClickable = false
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(text(context, 12f, 0xFF9CA3AF.toInt()).apply { text = context.getString(R.string.launcher_oem_mock_label) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                    .apply { setMargins(0, context.dp(6), context.dp(16), 0) })
        }
    }
}
