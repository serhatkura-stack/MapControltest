package com.mapcontrol.launcher

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/** Başlıktaki Bluetooth göstergesinin durumu: kapalı, açık ama telefon yok, telefon bağlı. */
internal enum class BtUi { OFF, ON, CONNECTED }

/** Saf durum eşlemesi (JVM'de sınanır). Bağdaştırıcı yoksa (emülatör) ya da kapalıysa OFF; bağlı yalnızca açıkken sayılır. */
internal object BtStateMap {
    fun map(adapterPresent: Boolean, enabled: Boolean, phoneConnected: Boolean): BtUi = when {
        !adapterPresent || !enabled -> BtUi.OFF
        phoneConnected -> BtUi.CONNECTED
        else -> BtUi.ON
    }
}

/**
 * Bluetooth durumunu olaylarla izler (zamanlayıcı yok): bağdaştırıcı açık/kapalı ve bağlantı yayınları (A2DP, HEADSET).
 * Telefon bağlı = A2DP ya da HEADSET profili bağlı. BLUETOOTH_CONNECT verilmemişse okuma SecurityException atar; kapalı sayılır.
 * [start]/[stop] eşleşmelidir; her ikisi de ana iş parçacığında çağrılır.
 */
internal class BluetoothStatus(context: Context, private val onChange: (BtUi) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private val refreshTask = Runnable { if (started) onChange(read()) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            // Profil yayını durum oturmadan önce gelebilir; okuma kısa gecikmeyle yapılır.
            main.removeCallbacks(refreshTask)
            main.postDelayed(refreshTask, SETTLE_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        })
        onChange(read())
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(refreshTask)
        try { app.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { }
    }

    @Suppress("DEPRECATION")
    private fun read(): BtUi {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return try {
            val enabled = adapter?.isEnabled == true
            val phone = enabled && (connected(adapter, BluetoothProfile.A2DP) || connected(adapter, BluetoothProfile.HEADSET))
            BtStateMap.map(adapter != null, enabled, phone)
        } catch (e: SecurityException) {
            BtUi.OFF
        } catch (e: RuntimeException) {
            BtUi.OFF
        }
    }

    private fun connected(adapter: BluetoothAdapter, profile: Int) =
        adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED

    private companion object {
        const val SETTLE_MS = 300L
    }
}
