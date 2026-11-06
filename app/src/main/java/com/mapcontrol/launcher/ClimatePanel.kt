package com.mapcontrol.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.mapcontrol.R
import kotlin.math.abs

/** Alt çubukta yukarı kaydırma ile OEM klima panelini açma eşiği (saf mantık, birim testli). */
internal object ClimatePanelGesture {
    const val OPEN_SWIPE_DP = 60f

    /** Yukarı hareket (dy > 0 yukarı) kayma payını aşıp ağırlıkla dikey mi: yakalama için. */
    fun isUpwardDrag(dy: Float, dx: Float, slopPx: Float): Boolean = dy > slopPx && dy > abs(dx)

    /** Bırakınca panel açılsın mı: dikey yukarı hareket en az [OPEN_SWIPE_DP] ve yataydan büyük. */
    fun shouldOpen(dyPx: Float, dxPx: Float, density: Float): Boolean =
        dyPx >= OPEN_SWIPE_DP * density && dyPx > abs(dxPx)
}

/**
 * OEM klima panelini açar (araçta çubuğun gizlediği OEM yukarı kaydırma jestinin yerine). Araca yazma yok: yalnızca
 * OEM HVAC servisine arayüz açma isteği (`type` = OPEN_PANEL, `page` 0 = ön klima). Servis başarısız olursa OEM
 * uygulamasının ana etkinliği, o da yoksa kısa uyarı. Sonuç ([TAG]) bir kez günlüğe yazılır.
 */
internal object ClimatePanel {
    private const val TAG = "ClimatePanel"
    const val PACKAGE = "com.desaysv.svhvac"
    private const val SERVICE = "com.desaysv.svhvac.service.HvacService"
    const val TYPE_OPEN = "com.desaysv.svhvac.OPEN_PANEL"
    private const val PAGE_FRONT = 0

    fun open(context: Context) {
        val outcome = try {
            context.startService(
                Intent().setComponent(ComponentName(PACKAGE, SERVICE))
                    .putExtra("type", TYPE_OPEN)
                    .putExtra("page", PAGE_FRONT),
            )?.let { "service" } ?: fallback(context, "service not found")
        } catch (e: SecurityException) {
            fallback(context, "SecurityException")
        } catch (e: IllegalStateException) {
            fallback(context, "IllegalStateException")
        }
        Log.i(TAG, "open outcome=$outcome")
        if (outcome == "failed") Toast.makeText(context, R.string.launcher_climate_panel_failed, Toast.LENGTH_SHORT).show()
    }

    private fun fallback(context: Context, reason: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return "failed"
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "activity ($reason)"
        } catch (e: ActivityNotFoundException) {
            "failed"
        } catch (e: SecurityException) {
            "failed"
        }
    }
}
