package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.WindowInsets

/**
 * İsteğe bağlı tam ekran: OEM alt çubuğu (Desay'da gerçek NAVIGATION_BAR, y 610–720) gizlenir, ızgara 720 px'in
 * tamamını kullanır; alttan yukarı kaydırınca çubuk geçici görünür. Varsayılan KAPALI: içerik çubuğun üstünde biter.
 * Tercih profil başınadır. Hata ayıklama: `--ez launcher_fullscreen true|false` tercihi yazar (yalnızca debug yapı).
 */
object LauncherFullscreen {

    private const val PREFS_NAME = "launcher_settings"
    private const val KEY = "fullscreen"
    private const val EXTRA = "launcher_fullscreen"
    private const val TAG = "LauncherFullscreen"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(Profiles.key(context, KEY), false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(Profiles.key(context, KEY), on).apply()
    }

    /** `launcher_fullscreen` ekstrası varsa (yalnızca debug yapıda) tercihi o değere ayarlar. */
    fun applyFrom(activity: Activity, intent: Intent?) {
        if (intent?.hasExtra(EXTRA) != true) return
        if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        setEnabled(activity, intent.getBooleanExtra(EXTRA, false))
    }

    /** Yerleşimden sonra gerçek durumu günlüğe yazar: Desay gizlemeye uydu mu, kök yükseklik ne. */
    fun logResult(activity: Activity) {
        val decor = activity.window.decorView
        decor.post {
            val navVisible = if (Build.VERSION.SDK_INT >= 30) {
                decor.rootWindowInsets?.isVisible(WindowInsets.Type.navigationBars())
            } else null
            Log.i(TAG, "istenen=${enabled(activity)} kokYukseklik=${decor.height} navBarGorunur=$navVisible")
        }
    }
}
