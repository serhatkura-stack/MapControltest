package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy

/**
 * Salt-okur test sürümü: araca hiçbir komut gönderilmez ve MapControl'ün Ayarlar ekranı yoktur.
 * Bu dosya :app'teki LauncherEdition.kt'nin yerini alır; imzalar aynıdır.
 */
internal object LauncherEdition {

    const val READ_ONLY = true

    const val HAS_SETTINGS = false

    /** Test sürümünde koltuk soğutma yazması her zaman kapalı. */
    const val SEAT_VENT_WRITE_VERIFIED = false

    @Suppress("UNUSED_PARAMETER")
    fun sendSeatVentLevel(proxy: CarInfoProxy, driver: Boolean, level: Int) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun sendItemValue(proxy: CarInfoProxy, module: Int, id: Int, value: Int) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setCentralLock(context: Context, locked: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun openSettings(activity: Activity) = Unit
}
