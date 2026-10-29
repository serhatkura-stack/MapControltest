package com.mapcontrol.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R
import com.mapcontrol.vehicle.ClimateAutomationStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Alt kart: sürücü tarafı (koltuk ısıtma, klima) · ortada uygulama şeridi · yolcu tarafı (buğu çözme, koltuk ısıtma).
 *
 * Yalnızca mevcut kodda (ClimateAutomationEngine) kullanılan ve değer anlamı bilinen komutlar gönderilir.
 * Sıcaklık/fan ayarı bilerek yok: komut değerleri araçta doğrulanmadan klimaya yazılmaz.
 */
class ClimateBar(private val context: Context, appDock: View) : LauncherWidget {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var state = ClimateState()

    private val driverSeat = ToggleTile(R.drawable.ic_mdi_car_seat_heater, R.string.launcher_seat_heat_driver, levels = true) {
        setSeatLevel(driver = true, level = nextSeatLevel(state.driverSeat))
    }
    private val ac = ToggleTile(R.drawable.ic_mdi_air_conditioner, R.string.launcher_ac) {
        setAc(!(state.acOn ?: false))
    }
    private val defrost = ToggleTile(R.drawable.ic_mdi_car_defrost, R.string.launcher_defrost) {
        setDefrost(!(state.defrostOn ?: false))
    }
    private val passengerSeat = ToggleTile(R.drawable.ic_mdi_car_seat_heater, R.string.launcher_seat_heat_passenger, levels = true) {
        setSeatLevel(driver = false, level = nextSeatLevel(state.passengerSeat))
    }
    private val outside = text(context, LauncherTheme.BODY_SP, LauncherTheme.TEXT_SECONDARY)

    override val view: View = card(context, LinearLayout.HORIZONTAL, paddingDp = 16).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(driverSeat.root)
        addView(gap(context, 12))
        addView(ac.root)
        addView(gap(context, 24))
        addView(outside)
        addView(appDock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = context.dp(24)
            marginEnd = context.dp(24)
        })
        addView(defrost.root)
        addView(gap(context, 12))
        addView(passengerSeat.root)
    }

    /** Klima durumu 3 sn'de bir okunur; sürücü bir şeye bastıktan kısa süre sonra da yeniden okunur. */
    private val poll = object : Runnable {
        override fun run() {
            refreshNow()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onVisible() {
        bind()
        main.post(poll)
    }

    override fun onHidden() {
        main.removeCallbacks(poll)
    }

    override fun release() {
        io.shutdownNow()
    }

    private fun refreshNow() {
        io.runSafely {
            val read = readState()
            main.post {
                state = read
                bind()
            }
        }
    }

    private fun readState(): ClimateState {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return ClimateState()
        return try {
            ClimateState(
                connected = true,
                acOn = proxy.first(HVAC, HvacID.ID_AC_STATE)?.let { it == 1 },
                defrostOn = proxy.first(HVAC, HvacID.ID_AC_FRONT_GLASS_HEAT)?.let { it == 1 },
                driverSeat = proxy.first(SETTING, CarSettingID.ID_CAR_DRIVER_SEAT_HEAT)?.let(::decodeSeat),
                passengerSeat = proxy.first(SETTING, CarSettingID.ID_CAR_COPILOT_SEAT_HEAT)?.let(::decodeSeat),
                outsideC = proxy.first(HVAC, HvacID.ID_AC_TEMPERATURE_CELSIUS_OUTSIDE)?.let(::decodeOutside),
            )
        } catch (e: RuntimeException) {
            ClimateState()
        }
    }

    private fun CarInfoProxy.first(module: Int, id: Int): Int? = getItemValues(module, id)?.firstOrNull()

    private fun bind() {
        val s = state
        driverSeat.bind(s.connected && s.driverSeat != null, (s.driverSeat ?: 0) > 0, s.driverSeat ?: 0)
        passengerSeat.bind(s.connected && s.passengerSeat != null, (s.passengerSeat ?: 0) > 0, s.passengerSeat ?: 0)
        ac.bind(s.connected && s.acOn != null, s.acOn == true)
        defrost.bind(s.connected && s.defrostOn != null, s.defrostOn == true)
        val temp = s.outsideC
        outside.visibility = if (temp == null) View.GONE else View.VISIBLE
        if (temp != null) outside.text = context.getString(R.string.launcher_outside_temp, temp.roundToInt())
    }

    // --- Komutlar: değerler ClimateAutomationEngine.applyRule / deactivateRule ile birebir aynı. ---

    private fun setAc(on: Boolean) {
        val v = if (on) 1 else 0
        send(state.copy(acOn = on)) {
            it.sendItemValue(HVAC, HvacID.ID_AC_STATE, v)
            it.sendItemValue(HVAC, HvacID.ID_AC_WORKING, v)
            it.sendItemValue(HVAC, HvacID.ID_AC_AUTO_STATE, v)
        }
    }

    private fun setDefrost(on: Boolean) {
        val v = if (on) 1 else 0
        send(state.copy(defrostOn = on)) {
            it.sendItemValue(HVAC, HvacID.ID_AC_FRONT_GLASS_HEAT, v)
            it.sendItemValue(HVAC, HvacID.ID_AC_MAX_FRONT_DEFOREST, v)
        }
    }

    private fun setSeatLevel(driver: Boolean, level: Int) {
        val id = if (driver) CarSettingID.ID_CAR_DRIVER_SEAT_HEAT else CarSettingID.ID_CAR_COPILOT_SEAT_HEAT
        val optimistic = if (driver) state.copy(driverSeat = level) else state.copy(passengerSeat = level)
        send(optimistic) { it.sendItemValue(SETTING, id, encodeSeat(level)) }
    }

    /** Ekranı hemen günceller (iyimser), komutu arka planda yollar, kısa süre sonra gerçek durumu okur. */
    private fun send(optimistic: ClimateState, command: (CarInfoProxy) -> Unit) {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return
        state = optimistic
        bind()
        io.runSafely {
            try {
                command(proxy)
            } catch (e: RuntimeException) {
                // Okuma turu gerçek durumu geri getirir.
            }
        }
        main.removeCallbacks(poll)
        main.postDelayed(poll, CONFIRM_DELAY_MS)
    }

    private fun nextSeatLevel(current: Int?): Int {
        val level = current ?: 0
        return if (level >= ClimateAutomationStore.LEVEL_MAX) 0 else level + 1
    }

    /** Araç değeri: 1 = kapalı, 2..4 = seviye 1..3 (ClimateAutomationEngine.wireSeatLevel). */
    private fun decodeSeat(wire: Int): Int =
        if (wire <= 1) 0 else (wire - 1).coerceIn(ClimateAutomationStore.LEVEL_MIN, ClimateAutomationStore.LEVEL_MAX)

    private fun encodeSeat(level: Int): Int = if (level <= 0) 1 else level.coerceIn(
        ClimateAutomationStore.LEVEL_MIN, ClimateAutomationStore.LEVEL_MAX) + 1

    /** ClimateAutomationEngine.decodeOutsideCelsius ile aynı: (ham - 110) / 2, geçerli aralık -40..80. */
    private fun decodeOutside(raw: Int): Double? = ((raw - 110) / 2.0).takeIf { it in -40.0..80.0 }

    private data class ClimateState(
        val connected: Boolean = false,
        val acOn: Boolean? = null,
        val defrostOn: Boolean? = null,
        val driverSeat: Int? = null,
        val passengerSeat: Int? = null,
        val outsideC: Double? = null,
    )

    /**
     * 88dp kare düğme: ikon + kısa etiket; açıkken vurgu renginde. Koltuk düğmelerinde
     * seviye, renkten bağımsız olarak üç küçük nokta ile de gösterilir.
     */
    private inner class ToggleTile(iconRes: Int, labelRes: Int, private val levels: Boolean = false, onTap: () -> Unit) {
        private val iconView: ImageView = icon(context, iconRes, 32)
        private val labelView = text(context, LauncherTheme.LABEL_SP, medium = true)
        private val dots = List(ClimateAutomationStore.LEVEL_MAX) {
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply {
                    marginStart = context.dp(3); marginEnd = context.dp(3)
                }
            }
        }
        val root: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(TILE_DP), context.dp(TILE_DP))
            background = rounded(context, LauncherTheme.CONTROL)
            contentDescription = context.getString(labelRes)
            isClickable = true
            addView(iconView)
            addView(gap(context, 8, vertical = true))
            if (levels) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    dots.forEach { addView(it) }
                })
            } else {
                addView(labelView.apply { setText(labelRes) })
            }
            setOnClickListener { onTap() }
            pressFeedback()
        }

        fun bind(enabled: Boolean, active: Boolean, level: Int = 0) {
            root.isEnabled = enabled
            root.alpha = if (enabled) 1f else DISABLED_ALPHA
            (root.background as android.graphics.drawable.GradientDrawable)
                .setColor(if (active) LauncherTheme.ACCENT else LauncherTheme.CONTROL)
            val fg = if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT
            iconView.imageTintList = ColorStateList.valueOf(fg)
            labelView.setTextColor(fg)
            dots.forEachIndexed { i, dot ->
                // Açık düğme vurgu renginde: dolu nokta ON_ACCENT. Boş nokta her iki temada görünür kalsın diye ikincil metin renginde.
                dot.background = oval(when {
                    i < level -> if (active) LauncherTheme.ON_ACCENT else LauncherTheme.TEXT
                    else -> LauncherTheme.TEXT_SECONDARY
                })
                dot.alpha = if (i < level) 1f else 0.45f
            }
        }
    }

    private companion object {
        val HVAC = VDEventCarInfo.MODULE_HVAC
        val SETTING = VDEventCarInfo.MODULE_CAR_SETTING
        const val TILE_DP = 88
        const val POLL_MS = 3_000L
        const val CONFIRM_DELAY_MS = 800L
        const val DISABLED_ALPHA = 0.4f
    }
}
