package com.mapcontrol.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.mapcontrol.R

/**
 * Launcher kartı görünümünde, ekranın ortasında bir panel: başlık, kapat düğmesi ve [build] ile kurulan içerik.
 * Ayarlar ve "Widget ekle" bunu kullanır; sistem diyaloğu yerine launcher'ın kendi renk ve ölçüleri.
 */
fun showLauncherSheet(
    activity: Activity,
    @StringRes title: Int,
    widthDp: Int = SHEET_WIDTH_DP,
    build: LinearLayout.(Dialog) -> Unit,
): Dialog {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val panel = card(activity).apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(activity, LauncherTheme.SECTION_SP, bold = true).apply { setText(title) },
                weightLp(1f).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT })
            addView(iconButton(activity, R.drawable.ic_mdi_close, activity.getString(R.string.launcher_close)) { dialog.dismiss() })
        }, sheetFullWidth())
        addView(gap(activity, 24, vertical = true))
        build(dialog)
    }
    dialog.setContentView(panel)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(activity.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
    }
    dialog.show()
    return dialog
}

fun sheetFullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private const val SHEET_WIDTH_DP = 760
