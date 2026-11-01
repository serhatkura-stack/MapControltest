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
    fullScreen: Boolean = false,
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
        if (fullScreen) setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        else setLayout(activity.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.CENTER)
    }
    dialog.hideOemBarIfNeeded(activity)
    dialog.show()
    return dialog
}

/**
 * Diyalog penceresi açılınca sistem gezinme çubuğunu geri getirmesin: "Bizimki"/"Gizli" alt çubuk kipinde
 * aktivitedekiyle aynı gizleme (kaydırınca geçici görünür). Tüm sayfa/diyalog açılışları buradan geçer.
 * [Dialog.show]'dan ÖNCE çağrılır (pencere zaten oluşturulur).
 */
fun Dialog.hideOemBarIfNeeded(context: android.content.Context) {
    if (!LauncherBottomBar.hidesOemBar(context)) return
    val w = window ?: return
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        w.decorView // pencereyi oluştur
        w.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

fun sheetFullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private const val SHEET_WIDTH_DP = 760
