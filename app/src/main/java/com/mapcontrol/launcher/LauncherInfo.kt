package com.mapcontrol.launcher

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Salt okunur cihaz/derleme bilgisi: başka araçlardaki (ör. Tiggo 8) test raporlarında hangi sürümün, hangi
 * ekranda ve hangi ızgarayla çalıştığı görünsün diye. Açılışta bir kez günlüğe yazılır, Veri kontrolü
 * panelinde "Cihaz" bölümü ve kaydedilen dosyanın başlığı olarak gösterilir. Araca hiçbir şey yazmaz.
 */
object LauncherInfo {
    const val TAG = "LauncherInfo"

    /** Izgaranın son ölçülen hücre boyutu (px); [LauncherGrid] yerleşimde bildirir. 0 = henüz ölçülmedi. */
    @Volatile private var cellWidthPx = 0f
    @Volatile private var cellHeightPx = 0f
    /** Etkin düzen modu; [HomeLauncherActivity] günceller. */
    @Volatile var mode: LayoutMode = LayoutMode.PARK
    @Volatile private var logged = false

    fun noteGrid(cellWidth: Float, cellHeight: Float) {
        cellWidthPx = cellWidth
        cellHeightPx = cellHeight
    }

    /** Derleme işlemesi (GIT_SHA); BuildConfig'te yoksa (ör. launchertest) "?" döner. */
    private fun buildCommit(): String = try {
        Class.forName("com.mapcontrol.BuildConfig").getField("GIT_SHA").get(null) as? String
    } catch (e: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: "?"

    /** (anahtar, değer) satırları, sabit sırayla. */
    fun entries(context: Context): List<Pair<String, String>> {
        val app = context.applicationContext
        val pkg = app.packageName
        val (versionName, versionCode) = try {
            val p = app.packageManager.getPackageInfo(pkg, 0)
            p.versionName to (if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong())
        } catch (e: Exception) {
            "?" to 0L
        }
        val dm = context.resources.displayMetrics
        val real = android.graphics.Point().also {
            try {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealSize(it)
            } catch (e: Exception) {
                it.set(dm.widthPixels, dm.heightPixels)
            }
        }
        val density = dm.density.takeIf { it > 0f } ?: 1f
        val cw = cellWidthPx
        val ch = cellHeightPx
        return listOf(
            "paket" to pkg,
            "sürüm" to "$versionName ($versionCode)",
            "derleme" to buildCommit(),
            "üretici/model" to "${Build.MANUFACTURER} / ${Build.MODEL}",
            "cihaz/ürün" to "${Build.DEVICE} / ${Build.PRODUCT}",
            "yazılım (DISPLAY)" to Build.DISPLAY,
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "ekran px" to "${real.x}×${real.y} (pencere ${dm.widthPixels}×${dm.heightPixels})",
            "ekran dp" to "${(dm.widthPixels / density).toInt()}×${(dm.heightPixels / density).toInt()}",
            "yoğunluk" to "${dm.densityDpi} dpi",
            "en küçük genişlik" to "${context.resources.configuration.smallestScreenWidthDp} dp",
            "ızgara" to if (cw > 0f && ch > 0f) "$GRID_COLS×$GRID_ROWS, hücre ${cw.toInt()}×${ch.toInt()} px" else "$GRID_COLS×$GRID_ROWS, hücre henüz ölçülmedi",
            "düzen modu" to mode.name,
        )
    }

    fun format(entries: List<Pair<String, String>>): String =
        entries.joinToString("\n") { (k, v) -> "$k: $v" }

    fun text(context: Context): String = format(entries(context))

    /** Açılışta bir kez (süreç başına) günlüğe yazar. */
    fun logOnce(context: Context) {
        if (logged) return
        logged = true
        text(context).lines().forEach { Log.i(TAG, it) }
    }
}
