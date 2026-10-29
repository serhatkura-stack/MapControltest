package com.mapcontrol.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Izgaraya konan uygulama şeridi (3×1): alt çubuktaki [AppDock] ile aynı yuva mantığı ve tercihler,
 * ilk dört yuva. Dokunmak uygulamayı açar; uzun basmak seçiciyi açar. OEM çubuğu olan araçta alt çubuk
 * kaldırıldığı için uygulamalara bu kartla ulaşılır.
 */
class AppRowWidget(context: Context) : SizedWidget {

    private val dock = AppDock(context, slotCount = SLOTS, slotWidthDp = SLOT_WIDTH_DP, embedded = true)

    private val root: LinearLayout = card(context, LinearLayout.HORIZONTAL, paddingDp = LauncherTheme.CARD_PAD_COMPACT_DP).apply {
        gravity = Gravity.CENTER
        addView(dock.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override val view: View get() = root

    override fun applySpan(span: Span) = Unit

    fun setEditMode(editing: Boolean) = dock.setEditMode(editing)

    override fun onVisible() = dock.onVisible()

    override fun onHidden() = dock.onHidden()

    override fun release() = dock.release()

    private companion object {
        const val SLOTS = 4
        const val SLOT_WIDTH_DP = 200
    }
}
