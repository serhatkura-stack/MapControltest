package com.mapcontrol.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.vehicle.ClimateAutomationStore
import java.util.concurrent.Executor

/** Koltuk ısıtma durumu: seviyeler 0 (kapalı) .. 3; okunamıyorsa null. */
internal data class SeatHeatState(
    val connected: Boolean = false,
    val driver: Int? = null,
    val passenger: Int? = null,
)

/**
 * Koltuk ısıtma okuma/yazma mantığı; alt çubuk ([ClimateBar]) ve koltuk kutucuğu ([SeatHeatTile]) paylaşır.
 *
 * Durum 3 sn'de bir okunur; dokununca seviye bir artar (3'ten sonra kapanır), ekran hemen güncellenir (iyimser),
 * komut arka planda yalnızca [LauncherEdition] üzerinden gider, kısa süre sonra gerçek durum okunur.
 * Yalnızca ClimateAutomationEngine'de kullanılan, değer anlamı bilinen komutlar gönderilir.
 */
internal class SeatHeatModel(
    private val context: Context,
    /** true: koltuk soğutma (havalandırma) sinyalleri; false: ısıtma (davranış değişmez). */
    private val vent: Boolean = false,
    private val onChange: (SeatHeatState) -> Unit,
) {

    private val io: Executor = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())
    private var startup: LauncherStartup.Deferred? = null

    var state = SeatHeatState()
        private set

    /** Klima durumu 3 sn'de bir okunur; sürücü bir şeye bastıktan kısa süre sonra da yeniden okunur. */
    private val poll = object : Runnable {
        override fun run() {
            refreshNow()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun onVisible() {
        onChange(state)
        startup?.cancel()
        startup = LauncherStartup.later { main.post(poll) }
    }

    fun onHidden() {
        startup?.cancel()
        main.removeCallbacks(poll)
    }

    /** Dokunuş: sıradaki seviyeyi yollar. Test sürümünde yalnızca bilgi mesajı gösterir. */
    fun cycle(driver: Boolean) {
        val current = if (driver) state.driver else state.passenger
        val level = nextLevel(current)
        val id = if (driver) CarSettingID.ID_CAR_DRIVER_SEAT_HEAT else CarSettingID.ID_CAR_COPILOT_SEAT_HEAT
        // Soğutma yazması araçta doğrulanana kadar kapalı: yalnızca sakin bir bilgi mesajı.
        if (vent && !(LauncherEdition.SEAT_VENT_WRITE_VERIFIED && !LauncherEdition.READ_ONLY)) {
            Toast.makeText(context, R.string.launcher_seat_vent_later, Toast.LENGTH_SHORT).show()
            return
        }
        if (LauncherEdition.READ_ONLY) {
            Toast.makeText(context, R.string.launcher_read_only, Toast.LENGTH_SHORT).show()
            return
        }
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return
        state = if (driver) state.copy(driver = level) else state.copy(passenger = level)
        onChange(state)
        io.runSafely {
            try {
                if (vent) LauncherEdition.sendSeatVentLevel(proxy, driver, level)
                else LauncherEdition.sendItemValue(proxy, SETTING, id, encode(level))
            } catch (e: RuntimeException) {
                // Okuma turu gerçek durumu geri getirir.
            }
        }
        main.removeCallbacks(poll)
        main.postDelayed(poll, CONFIRM_DELAY_MS)
    }

    private fun refreshNow() {
        io.runSafely {
            val read = read()
            main.post {
                // Okunan durum değişmediyse yeniden çizme (3 sn'lik yoklamada boşuna bind yok).
                if (read == state) return@post
                state = read
                onChange(state)
            }
        }
    }

    private fun read(): SeatHeatState {
        if (LauncherDemo.enabled) return SeatHeatState(
            connected = true,
            driver = if (vent) LauncherDemo.DRIVER_VENT_LEVEL else LauncherDemo.DRIVER_SEAT_LEVEL,
            passenger = if (vent) LauncherDemo.PASSENGER_VENT_LEVEL else LauncherDemo.PASSENGER_SEAT_LEVEL,
        )
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return SeatHeatState()
        return try {
            if (vent) return SeatHeatState(
                connected = true,
                driver = proxy.first(CarSettingID.ID_CAR_DRIVER_SEAT_VENTILATION)?.let(::decodeVent),
                passenger = proxy.first(CarSettingID.ID_CAR_COPILOT_SEAT_VENTILATION)?.let(::decodeVent),
            )
            SeatHeatState(
                connected = true,
                driver = proxy.first(CarSettingID.ID_CAR_DRIVER_SEAT_HEAT)?.let(::decode),
                passenger = proxy.first(CarSettingID.ID_CAR_COPILOT_SEAT_HEAT)?.let(::decode),
            )
        } catch (e: RuntimeException) {
            SeatHeatState()
        }
    }

    private fun CarInfoProxy.first(id: Int): Int? = getItemValues(SETTING, id)?.firstOrNull()

    companion object {
        private val SETTING = VDEventCarInfo.MODULE_CAR_SETTING
        private const val POLL_MS = 3_000L
        private const val CONFIRM_DELAY_MS = 800L

        /** Seviye 0..3 → etiket; ClimateAutomationStore.LEVEL_MAX = 3. */
        val LEVEL_LABELS = intArrayOf(
            R.string.launcher_seat_off, R.string.launcher_seat_low, R.string.launcher_seat_mid, R.string.launcher_seat_high,
        )

        fun levelLabel(level: Int): Int = LEVEL_LABELS[level.coerceIn(0, LEVEL_LABELS.lastIndex)]

        fun nextLevel(current: Int?): Int {
            val level = current ?: 0
            return if (level >= ClimateAutomationStore.LEVEL_MAX) 0 else level + 1
        }

        /** Araç değeri: 1 = kapalı, 2..4 = seviye 1..3 (ClimateAutomationEngine.wireSeatLevel). */
        fun decode(wire: Int): Int =
            if (wire <= 1) 0 else (wire - 1).coerceIn(ClimateAutomationStore.LEVEL_MIN, ClimateAutomationStore.LEVEL_MAX)

        /**
         * Soğutma değeri: 0 = kapalı, 1..3 = seviye. DOĞRULANMADI: araçta sinyal (93/94) şimdilik hep [0] okunuyor;
         * kodlama ısıtmayla aynı varsayılıyor ama gerçek araç testiyle teyit edilmeli.
         */
        fun decodeVent(wire: Int): Int = wire.coerceIn(0, ClimateAutomationStore.LEVEL_MAX)

        fun encode(level: Int): Int = if (level <= 0) 1 else level.coerceIn(
            ClimateAutomationStore.LEVEL_MIN, ClimateAutomationStore.LEVEL_MAX) + 1
    }
}
