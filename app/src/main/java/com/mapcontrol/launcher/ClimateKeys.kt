package com.mapcontrol.launcher

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.R

/** Klima tuşları: A/C, Oto, iç/dış hava, klima gücü. */
internal enum class ClimateKey { AC, AUTO, RECIRC, POWER }

/** Klima tuşlarının okunan durumu; okunamayan alan null. */
internal data class ClimateKeysReading(
    val ac: Boolean? = null,
    val auto: Boolean? = null,
    /** true iç hava (devridaim), false dış hava. */
    val recirc: Boolean? = null,
    val power: Boolean? = null,
)

/**
 * Klima tuşlarının saf kuralları (JVM'de sınanır). Kaynak: Desay SVSystemUI `HvacModel` (T1E24 dalı) ve SVVrControl `a/b.java`,
 * bkz. katalog/oem-komut-kodlamalari.md. Okuma: HVAC 1 (A/C), 4 (Oto), 18 (güç) `== 1` açık; HVAC 8 `== 0` iç hava
 * (OEM `getCirculationState`). Yazma: 1 = aç / 2 = kapat; devridaimde 1 = iç hava / 2 = dış hava. Hepsi araçta DOĞRULANMADI.
 */
internal object ClimateKeysMath {

    /** HvacID.ID_CIRCULATION_MODE: yeni yazılımda devridaim durumu (bu SDK jar'ında yok); yalnızca okunur, günlüğe yazılır. */
    const val ID_CIRCULATION_MODE_NEW = 111

    const val ON = 1
    const val OFF = 2
    const val RECIRC = 1
    const val FRESH = 2

    /** HVAC 1 / 4 / 18 okuması: 1 açık, başka her değer kapalı; okunamadıysa null. */
    fun decodeOn(raw: Int?): Boolean? = raw?.let { it == ON }

    /** HVAC 8 okuması: 0 iç hava (OEM `getCirculationState() == (HVAC 8 == 0)`), diğerleri dış hava; okunamadıysa null. */
    fun decodeRecirc(raw: Int?): Boolean? = raw?.let { it == 0 }

    fun id(key: ClimateKey): Int = when (key) {
        ClimateKey.AC -> HvacID.ID_AC_STATE
        ClimateKey.AUTO -> HvacID.ID_AC_AUTO_STATE
        ClimateKey.RECIRC -> HvacID.ID_AC_CIRCULATION_MODE
        ClimateKey.POWER -> HvacID.ID_AC_WORKING
    }

    /** [target] true: açık (iç hava); false: kapalı (dış hava). */
    fun commands(key: ClimateKey, target: Boolean): List<ComfortCmd> {
        val value = when (key) {
            ClimateKey.RECIRC -> if (target) RECIRC else FRESH
            else -> if (target) ON else OFF
        }
        return listOf(ComfortCmd(VDEventCarInfo.MODULE_HVAC, id(key), value))
    }

    fun current(r: ClimateKeysReading?, key: ClimateKey): Boolean? = when (key) {
        ClimateKey.AC -> r?.ac
        ClimateKey.AUTO -> r?.auto
        ClimateKey.RECIRC -> r?.recirc
        ClimateKey.POWER -> r?.power
    }
}

/** Klima tuşlarını okur (yalnızca okuma; arka plan thread'inde). */
internal fun ComfortReader.readClimateKeys(): ComfortReading {
    if (LauncherDemo.enabled) return LauncherDemo.comfort()
    val proxy = com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy.getInstance()
    if (!proxy.isServiceConnnected) return ComfortReading()
    val hv = VDEventCarInfo.MODULE_HVAC
    return try {
        val new111 = CarSignals.readInt(hv, ClimateKeysMath.ID_CIRCULATION_MODE_NEW)
        if (new111 != null && new111 != lastCirc111) {
            lastCirc111 = new111
            Log.i("ClimateKeys", "HVAC 111 (ID_CIRCULATION_MODE) = $new111")
        }
        ComfortReading(
            connected = true,
            keys = ClimateKeysReading(
                ac = ClimateKeysMath.decodeOn(CarSignals.readInt(hv, HvacID.ID_AC_STATE)),
                auto = ClimateKeysMath.decodeOn(CarSignals.readInt(hv, HvacID.ID_AC_AUTO_STATE)),
                recirc = ClimateKeysMath.decodeRecirc(CarSignals.readInt(hv, HvacID.ID_AC_CIRCULATION_MODE)),
                power = ClimateKeysMath.decodeOn(CarSignals.readInt(hv, HvacID.ID_AC_WORKING)),
            ),
        )
    } catch (e: RuntimeException) {
        ComfortReading()
    }
}

private var lastCirc111: Int? = null

/**
 * Klima tuşları: A/C, Oto, iç/dış hava ve klima gücü, tek şeritte dört düğme (alçak) ya da 2×2 (yüksek). Durumlar araçtan okunur;
 * yazma yalnızca [ComfortWriter.climateKeysEnabled] ise gider (doğrulanana dek bilgi mesajı).
 */
internal class ClimateKeysTile(context: Context) : ComfortTile(context) {

    private val pending = HashMap<ClimateKey, Boolean>()

    private val chips = linkedMapOf(
        ClimateKey.AC to ToggleChip(context, R.drawable.ic_mdi_snowflake, R.string.launcher_keys_ac) { toggle(ClimateKey.AC) },
        ClimateKey.AUTO to ToggleChip(context, R.drawable.ic_mdi_thermometer, R.string.launcher_keys_auto) { toggle(ClimateKey.AUTO) },
        ClimateKey.RECIRC to ToggleChip(context, R.drawable.ic_mdi_refresh, R.string.launcher_keys_recirc) { toggle(ClimateKey.RECIRC) },
        ClimateKey.POWER to ToggleChip(context, R.drawable.ic_mdi_power, R.string.launcher_keys_power) { toggle(ClimateKey.POWER) },
    )

    private val root: LinearLayout = card(context, LinearLayout.VERTICAL, PADDING_DP).apply {
        theme.bind { background = cardSurface(context) }
        gravity = Gravity.CENTER
    }

    override val view: View get() = root

    override fun readNow(): ComfortReading = ComfortReader.readClimateKeys()

    override fun applySpan(span: Span) {
        val tall = span.h >= TALL_ROWS
        root.removeAllViews()
        chips.values.forEach { c -> (c.view.parent as? LinearLayout)?.removeView(c.view); c.setCompact(!tall) }
        val all = chips.values.toList()
        if (tall) {
            all.chunked(2).forEachIndexed { i, pair ->
                if (i > 0) root.addView(gap(context, GAP_DP, vertical = true))
                root.addView(row(pair), weightLp(1f, vertical = true))
            }
        } else {
            root.addView(row(all), weightLp(1f, vertical = true))
        }
        val pad = context.dp(if (tall) PADDING_DP else COMPACT_PADDING_DP)
        root.setPadding(pad, pad, pad, pad)
    }

    private fun row(items: List<ToggleChip>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, c ->
            if (i > 0) addView(gap(context, GAP_DP))
            addView(c.view, weightLp(1f))
        }
    }

    override fun clearPending() = pending.clear()

    private fun shown(key: ClimateKey): Boolean? =
        pending[key] ?: ClimateKeysMath.current(reading.keys, key).takeIf { reading.connected }

    override fun render() {
        chips.forEach { (key, chip) ->
            if (key == ClimateKey.RECIRC) {
                val recirc = shown(key)
                chip.setLabel(if (recirc == false) R.string.launcher_keys_fresh else R.string.launcher_keys_recirc)
                chip.bind(recirc, if (recirc == false) R.string.launcher_keys_fresh_state else R.string.launcher_keys_recirc_state)
            } else {
                chip.bind(shown(key))
            }
        }
    }

    private fun toggle(key: ClimateKey) {
        val current = shown(key)
        // Okunamıyorsa (test sürümü dışında) tahmin etme.
        if (current == null && !LauncherEdition.READ_ONLY) return
        val target = !(current ?: false)
        pending[key] = target
        val enabled = ComfortWriter.climateKeysEnabled
        if (enabled) io.runSafely { ComfortWriter.setClimateKey(key, target) }
        afterCommit(enabled)
    }

    private companion object {
        const val PADDING_DP = 14
        const val COMPACT_PADDING_DP = 8
        const val GAP_DP = 8
        const val TALL_ROWS = 4
    }
}
