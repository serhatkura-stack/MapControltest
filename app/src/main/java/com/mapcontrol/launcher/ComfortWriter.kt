package com.mapcontrol.launcher

import android.os.Handler
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tavan perdesi durumu (CarSetting 10, araçta ölçüldü): 1 kapalı, 2 açılıyor, 3 yarıda durdu (tek çekişte yarıya
 * gelir, durur), 4 kapanıyor. 5 (açık) ve 6 (yarıda) bu araçta görülmedi; diğer araçlar için hoşgörülür.
 */
internal enum class SunshadeState(val raw: Int) {
    CLOSED(1), OPENING(2), PARTIAL(3), CLOSING(4), OPEN(5), UNKNOWN(-1);

    /** Hareket sonunda varılacak açıklık (0 kapalı .. 1 açık); bilinmiyorsa null. Yarıda durmuş perde ~0,5 sayılır. */
    val openFraction: Float?
        get() = when (this) {
            CLOSED, CLOSING -> 0f
            OPEN, OPENING -> 1f
            PARTIAL -> 0.5f
            UNKNOWN -> null
        }

    val moving: Boolean get() = this == OPENING || this == CLOSING
}

/** Araca gidecek tek bir yazma: modül, sinyal, değer. */
internal data class ComfortCmd(val module: Int, val id: Int, val value: Int)

/** Perde komut adımı: [delayMs] sonra [value] yazılır (2 aç, 1 kapat, [ComfortWriter.SUNSHADE_STOP_VALUE] durdur). */
internal data class SunshadeStep(val value: Int, val delayMs: Long)

/**
 * Konfor widget'larının saf hesapları (arayüzden bağımsız, birim testli): durum çözme, perde sürükleme ve
 * konum → durdurma gecikmesi, yazma komutları.
 */
internal object ComfortMath {

    /** Sürükleme bu orandan (parmak, parça yüksekliğinin payı) az hareket ettirdiyse yön yok sayılır. */
    const val SNAP_DRAG_FRACTION = 0.25f

    fun decodeSunshade(raw: Int?): SunshadeState = when (raw) {
        6 -> SunshadeState.PARTIAL
        else -> SunshadeState.values().firstOrNull { it.raw == raw && it != SunshadeState.UNKNOWN } ?: SunshadeState.UNKNOWN
    }

    /** Ön cam: yalnızca maksimum buğu giderme (Hvac 24) okunur (araçta ölçüldü; FRONT_GLASS_HEAT 65 hiç değişmedi). */
    fun decodeFront(maxDeforest: Int?): Boolean? = maxDeforest?.let { it > 0 }

    /** Arka cam: HVAC arka buğu (80) okunur (araçta ölçüldü: 0 → 1). */
    fun decodeRear(hvacDefrost: Int?): Boolean? = hvacDefrost?.let { it > 0 }

    /** Direksiyon ısıtma (CarSetting 160): 0/1; okunamıyorsa null. */
    fun decodeSteer(raw: Int?): Boolean? = raw?.let { it > 0 }

    // --- Perde ---

    /**
     * Sürükleme sırasındaki perde örtüsü (0 = tamamen açık/çekilmiş, 1 = tamamen kapalı): perde parmağı izler.
     * [dy] aşağı pozitif; aşağı sürükleme örtüyü artırır (kapatır).
     */
    fun dragCoverage(startCoverage: Float, dy: Float, trackPx: Float): Float =
        if (trackPx <= 0f) startCoverage else (startCoverage + dy / trackPx).coerceIn(0f, 1f)

    /**
     * Bırakınca hedef açıklık (0..1). Konum durdurma değeri bilinmiyorsa ([partialAllowed] false) yalnızca tam açık/kapalı:
     * başlangıçtan en az [SNAP_DRAG_FRACTION] kadar giden sürükleme o yöne tam gider, daha azı başlangıç durumunda kalır.
     * Ara konum izinliyse parmağın bıraktığı yer (0,05 adıma yuvarlanmış) hedeftir.
     */
    fun releaseTarget(startOpen: Float, coverage: Float, partialAllowed: Boolean): Float {
        val open = 1f - coverage
        if (partialAllowed) return (Math.round(open * 20f) / 20f).coerceIn(0f, 1f)
        val delta = open - startOpen
        return when {
            delta >= SNAP_DRAG_FRACTION -> 1f
            delta <= -SNAP_DRAG_FRACTION -> 0f
            else -> if (startOpen >= 0.5f) 1f else 0f
        }
    }

    /** Dokunma: kapalı/kapanıyorsa aç, açık/açılıyor/yarıdaysa kapat; durum bilinmiyorsa null (hiçbir şey yapılmaz). */
    fun toggleTarget(state: SunshadeState): Float? = when (state) {
        SunshadeState.CLOSED, SunshadeState.CLOSING -> 1f
        SunshadeState.OPEN, SunshadeState.OPENING, SunshadeState.PARTIAL -> 0f
        SunshadeState.UNKNOWN -> null
    }

    /**
     * Araçtaki düğme gibi adım adım dokunma: kapalı → yarı → tam açık, açıkken ters yön. Tek çekiş perdeyi yarıda
     * durdurur. Yarıdayken yön [lastOpening] (son adım açma yönündeyse true) ile belirlenir; bilinmiyorsa aç.
     * Hareket halindeyse ters yöne; durum bilinmiyorsa null.
     */
    fun tapStep(state: SunshadeState, lastOpening: Boolean?): Float? = when (state) {
        SunshadeState.CLOSED -> 0.5f
        SunshadeState.OPEN -> 0.5f
        SunshadeState.PARTIAL -> if (lastOpening == false) 0f else 1f
        SunshadeState.OPENING -> 0f
        SunshadeState.CLOSING -> 1f
        SunshadeState.UNKNOWN -> null
    }

    /** Tek adım: hedef mevcut açıklıktan büyükse "aç" (2), değilse "kapat" (1); mevcut bilinmiyorsa hedef >= 0,5 açar. */
    fun stepPlan(currentOpen: Float?, targetOpen: Float): List<SunshadeStep> {
        val opening = if (currentOpen != null) targetOpen > currentOpen else targetOpen >= 0.5f
        return listOf(SunshadeStep(if (opening) ComfortWriter.SUNSHADE_OPEN_VALUE else ComfortWriter.SUNSHADE_CLOSE_VALUE, 0))
    }

    /** [from] açıklığından [to] açıklığına gitmek için komuttan sonra durdurma gecikmesi (ms): yol payı × tam yol süresi. */
    fun stopDelayMs(from: Float, to: Float, travelMs: Long): Long =
        (abs(to.coerceIn(0f, 1f) - from.coerceIn(0f, 1f)) * travelMs).roundToLong()

    /**
     * Hedef açıklığa giden komut adımları. Tam açık (>= 1) tek "aç" (2), tam kapalı (<= 0) tek "kapat" (1).
     * Ara hedef yalnızca durdurma değeri biliniyorsa ve başlangıç açıklığı bilinirse: yönde komut, ardından
     * [stopDelayMs] sonra durdurma. Aksi halde en yakın uca (>= 0,5 açık) yuvarlanır.
     */
    fun planSunshade(
        currentOpen: Float?,
        targetOpen: Float,
        stopValue: Int? = ComfortWriter.SUNSHADE_STOP_VALUE,
        travelMs: Long? = ComfortWriter.SUNSHADE_TRAVEL_MS,
    ): List<SunshadeStep> {
        if (targetOpen >= 1f) return listOf(SunshadeStep(ComfortWriter.SUNSHADE_OPEN_VALUE, 0))
        if (targetOpen <= 0f) return listOf(SunshadeStep(ComfortWriter.SUNSHADE_CLOSE_VALUE, 0))
        if (stopValue == null || travelMs == null || currentOpen == null || currentOpen == targetOpen) {
            return planSunshade(currentOpen, if (targetOpen >= 0.5f) 1f else 0f, null, travelMs)
        }
        val dir = if (targetOpen > currentOpen) ComfortWriter.SUNSHADE_OPEN_VALUE else ComfortWriter.SUNSHADE_CLOSE_VALUE
        return listOf(SunshadeStep(dir, 0), SunshadeStep(stopValue, stopDelayMs(currentOpen, targetOpen, travelMs)))
    }

    // --- Komutlar ---

    /** Buğu/ısıtma yazması: ön cam HV 24 (ölçülen) + HV 65, arka cam HV 80 + CS 159. */
    fun defrostCommands(front: Boolean, on: Boolean): List<ComfortCmd> {
        val v = if (on) 1 else 0
        return if (front) listOf(
            ComfortCmd(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_MAX_FRONT_DEFOREST, v),
            ComfortCmd(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_FRONT_GLASS_HEAT, v),
        ) else listOf(
            ComfortCmd(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_REAR_DEFROST, v),
            ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_REAR_WINDOW_HEAT, v),
        )
    }

    fun steerCommands(on: Boolean): List<ComfortCmd> =
        listOf(ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_STEER_WHEEL_HEAT, if (on) 1 else 0))

    fun sunshadeCommand(value: Int): ComfortCmd =
        ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_SUNSHADE, value)
}

/**
 * Konfor widget'larının (tavan perdesi, buğu, direksiyon ısıtma) yazma noktası. Kodlamalar araçta DOĞRULANMADI:
 * [COMFORT_WRITE_VERIFIED] ve ilgili özellik bayrağı true olana kadar (ve test sürümünde hiçbir zaman) araca
 * HİÇBİR ŞEY gitmez. Araca ulaşan tek yol `LauncherEdition.sendItemValue` (test sürümünde boş).
 * Kapı [ClimateWriter.canWrite] ile aynıdır.
 */
internal object ComfortWriter {

    /** Ana anahtar: araç testinden sonra true yapılır. Özellik bayrakları da true olmalı. */
    const val COMFORT_WRITE_VERIFIED = false
    const val SUNSHADE_WRITE_VERIFIED = false
    const val DEFROST_WRITE_VERIFIED = false
    const val STEER_HEAT_WRITE_VERIFIED = false

    /** Perde komut değerleri (CarSetting 10): FrostNav'ın gönderdiği gibi 2 aç, 1 kapat. */
    const val SUNSHADE_OPEN_VALUE = 2
    const val SUNSHADE_CLOSE_VALUE = 1

    /** Perdenin tam yol süresi (ms): tam açılma süresi araçta ölçülecek; 11 sn yarıya kadar (araçta ölçüldü). Null = bilinmiyor. */
    val SUNSHADE_TRAVEL_MS: Long? = null

    /** Perdeyi yarıda durduran CarSetting 10 değeri; bilinmiyor, araç testinde bulunacak. Null iken yalnızca tam açık/kapalı. */
    val SUNSHADE_STOP_VALUE: Int? = null

    /** Ara konum (yüzde) kontrolü mümkün mü: durdurma değeri biliniyorsa. */
    val partialPositionKnown: Boolean get() = SUNSHADE_STOP_VALUE != null

    /** Saf kapı: ana anahtar ve özellik bayrağı true ve salt-okur sürüm değilse yazılır. */
    fun canWrite(master: Boolean, feature: Boolean, readOnly: Boolean): Boolean =
        ClimateWriter.canWrite(master && feature, readOnly)

    val sunshadeEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, SUNSHADE_WRITE_VERIFIED, LauncherEdition.READ_ONLY)
    val defrostEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, DEFROST_WRITE_VERIFIED, LauncherEdition.READ_ONLY)
    val steerEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, STEER_HEAT_WRITE_VERIFIED, LauncherEdition.READ_ONLY)

    /**
     * Kapılı yazma (birim testli): [allowed] false ise [send] hiç çağrılmaz ve false döner.
     */
    fun writeAll(allowed: Boolean, commands: List<ComfortCmd>, send: (ComfortCmd) -> Unit): Boolean {
        if (!allowed) return false
        commands.forEach(send)
        return true
    }

    /** Buğu: ön/arka cam aç/kapat. Kapalıyken false döner ve araca dokunmaz. */
    fun setDefrost(front: Boolean, on: Boolean): Boolean =
        writeAll(defrostEnabled, ComfortMath.defrostCommands(front, on), ::sendToCar)

    /** Direksiyon ısıtma aç/kapat. Kapalıyken false döner ve araca dokunmaz. */
    fun setSteerHeat(on: Boolean): Boolean =
        writeAll(steerEnabled, ComfortMath.steerCommands(on), ::sendToCar)

    /**
     * Perde adımlarını zamanlar (gecikmeli adım = durdurma). Önceki bekleyen adımlar iptal edilir.
     * Kapalıyken false döner ve hiçbir şey zamanlamaz.
     */
    class SunshadeRunner(private val main: Handler, private val io: Executor) {
        private val pending = mutableListOf<Runnable>()

        fun cancel() {
            pending.forEach(main::removeCallbacks)
            pending.clear()
        }

        fun run(steps: List<SunshadeStep>): Boolean {
            if (!sunshadeEnabled) return false
            cancel()
            steps.forEach { step ->
                val r = Runnable { io.runSafely { sendToCar(ComfortMath.sunshadeCommand(step.value)) } }
                pending += r
                if (step.delayMs <= 0) main.post(r) else main.postDelayed(r, step.delayMs)
            }
            return true
        }
    }

    private fun sendToCar(cmd: ComfortCmd) {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return
        LauncherEdition.sendItemValue(proxy, cmd.module, cmd.id, cmd.value)
    }
}

/** Konfor widget'larının okunan anlık durumu; okunamayan alan null. */
internal data class ComfortReading(
    val connected: Boolean = false,
    val sunshade: SunshadeState = SunshadeState.UNKNOWN,
    val frontDefrost: Boolean? = null,
    val rearDefrost: Boolean? = null,
    val steerHeat: Boolean? = null,
)

/** Konfor sinyallerini okur (yalnızca okuma; arka plan thread'inde çağrılır). */
internal object ComfortReader {
    fun read(): ComfortReading {
        if (LauncherDemo.enabled) return LauncherDemo.comfort()
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return ComfortReading()
        return try {
            fun cs(id: Int) = proxy.getItemValues(VDEventCarInfo.MODULE_CAR_SETTING, id)?.firstOrNull()
            fun hv(id: Int) = proxy.getItemValues(VDEventCarInfo.MODULE_HVAC, id)?.firstOrNull()
            ComfortReading(
                connected = true,
                sunshade = ComfortMath.decodeSunshade(cs(CarSettingID.ID_CAR_SUNSHADE)),
                frontDefrost = ComfortMath.decodeFront(hv(HvacID.ID_AC_MAX_FRONT_DEFOREST)),
                rearDefrost = ComfortMath.decodeRear(hv(HvacID.ID_AC_REAR_DEFROST)),
                steerHeat = ComfortMath.decodeSteer(cs(CarSettingID.ID_CAR_STEER_WHEEL_HEAT)),
            )
        } catch (e: RuntimeException) {
            ComfortReading()
        }
    }
}
