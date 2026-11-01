package com.mapcontrol.launcher

import android.os.Handler
import com.desaysv.ivi.extra.project.carinfo.CarSettingID
import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.ReadOnlyID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import com.mapcontrol.comfort.ParkGate
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tavan perdesi durumu (CarSetting 10, araçta ölçüldü): 1 kapalı, 2 açılıyor, 3 yarıda/durdu, 4 kapanıyor,
 * 5 tam açık (komuttan sonra), 6 tam açık (düğmeden sonra).
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

/**
 * Bagaj kapağı durumu. Okuma (araçta ölçüldü): ReadOnly PLG_POSITION (98) 0 kapalı .. ~81 tam açık, TRUNK_STATE (19) 0 kapalı / 1 açık,
 * CarSetting REAR_DOOR_CONTROL (92) 2 durdu / 3 açılıyor / 4 kapanıyor.
 */
internal enum class TrunkState {
    CLOSED, OPENING, CLOSING, PARTIAL, OPEN, UNKNOWN;

    val moving: Boolean get() = this == OPENING || this == CLOSING
}

/** Bagaj ham okuması; okunamayan alan null. */
internal data class TrunkReading(val position: Int? = null, val latchOpen: Int? = null, val motion: Int? = null)

/** Bagaj dokunuşunun sonucu: ne yapılacağı (saf karar, [TrunkMath.tapAction]). */
internal enum class TrunkAction { OPEN, CLOSE_CONFIRM, STOP, STOP_UNKNOWN, NONE }

/** Kapılı bagaj yazmasının sonucu. */
internal enum class TrunkWriteResult { SENT, DISABLED, NOT_PARKED }

/** Perde komut adımı: [delayMs] sonra [value] yazılır (VR 124: 1 aç, 2 kapat, [ComfortWriter.SUNSHADE_STOP_VALUE] durdur). */
internal data class SunshadeStep(val value: Int, val delayMs: Long)

/** Bagaj kapağının saf hesapları (JVM'de sınanır): ham okuma → durum ve yüzde, dokunuş kararı, yazma komutu. */
internal object TrunkMath {

    /** PLG_POSITION tam açıkken ölçülen değer (~81); yüzde buna göre. */
    const val FULL_OPEN = 81

    /** Bu değerden itibaren (tam açık - 2) "tam açık" sayılır; ölçüm kıpırdaması payı. */
    const val OPEN_THRESHOLD = FULL_OPEN - 2

    const val MOTION_STOPPED = 2
    const val MOTION_OPENING = 3
    const val MOTION_CLOSING = 4

    /** Açıklık yüzdesi 0..100; konum okunamıyorsa null. */
    fun percent(position: Int?): Int? =
        position?.let { Math.round(it.coerceIn(0, FULL_OPEN) * 100f / FULL_OPEN) }

    /**
     * Durum: önce hareket (CS 92: 3 açılıyor, 4 kapanıyor). Sonra konum: 0 → kapalı (mandal açık diyorsa açık), [OPEN_THRESHOLD] ve üstü → açık,
     * arası → yarı açık. Konum okunamazsa mandal (TRUNK_STATE 19). Hiçbiri yoksa bilinmiyor.
     */
    fun decode(r: TrunkReading?): TrunkState {
        if (r == null) return TrunkState.UNKNOWN
        when (r.motion) {
            MOTION_OPENING -> return TrunkState.OPENING
            MOTION_CLOSING -> return TrunkState.CLOSING
        }
        val pos = r.position
        if (pos != null) return when {
            pos <= 0 -> if (r.latchOpen == 1) TrunkState.OPEN else TrunkState.CLOSED
            pos >= OPEN_THRESHOLD -> TrunkState.OPEN
            else -> TrunkState.PARTIAL
        }
        return when (r.latchOpen) {
            0 -> TrunkState.CLOSED
            1 -> TrunkState.OPEN
            else -> TrunkState.UNKNOWN
        }
    }

    /** Çizim için açıklık 0..1; konum yoksa durumdan (kapalı 0, açık 1), bilinmiyorsa null. */
    fun openFraction(r: TrunkReading?): Float? {
        val pos = r?.position
        if (pos != null) return pos.coerceIn(0, FULL_OPEN) / FULL_OPEN.toFloat()
        return when (decode(r)) {
            TrunkState.CLOSED -> 0f
            TrunkState.OPEN -> 1f
            else -> null
        }
    }

    /**
     * Dokunuş: kapalı → aç; açık/yarı açık → onaylı kapat; hareket halinde → durdur (yalnızca durdurma değeri doğrulandıysa
     * [stopKnown], değilse ipucu); bilinmiyorsa hiçbir şey.
     */
    fun tapAction(state: TrunkState, stopKnown: Boolean): TrunkAction = when (state) {
        TrunkState.CLOSED -> TrunkAction.OPEN
        TrunkState.OPEN, TrunkState.PARTIAL -> TrunkAction.CLOSE_CONFIRM
        TrunkState.OPENING, TrunkState.CLOSING -> if (stopKnown) TrunkAction.STOP else TrunkAction.STOP_UNKNOWN
        TrunkState.UNKNOWN -> TrunkAction.NONE
    }

    /**
     * Yazma komutu. [viaVr] true: VR 121 (1 aç, 2 kapat); false: CarSetting 92 (3 aç, 4 kapat). İkisi de araçta DOĞRULANMADI;
     * hangisi çalışırsa [ComfortWriter.TRUNK_VIA_VR] ona ayarlanır.
     */
    fun command(open: Boolean, viaVr: Boolean): ComfortCmd =
        if (viaVr) ComfortCmd(ComfortWriter.MODULE_VR, ComfortWriter.ID_VR_TRUNK, if (open) 1 else 2)
        else ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_REAR_DOOR_CONTROL, if (open) 3 else 4)
}

/**
 * Konfor widget'larının saf hesapları (arayüzden bağımsız, birim testli): durum çözme, perde sürükleme ve
 * konum → durdurma gecikmesi, yazma komutları.
 */
internal object ComfortMath {

    /** Sürükleme bu orandan (parmak, parça yüksekliğinin payı) az hareket ettirdiyse yön yok sayılır. */
    const val SNAP_DRAG_FRACTION = 0.25f

    fun decodeSunshade(raw: Int?): SunshadeState = when (raw) {
        6 -> SunshadeState.OPEN
        else -> SunshadeState.values().firstOrNull { it.raw == raw && it != SunshadeState.UNKNOWN } ?: SunshadeState.UNKNOWN
    }

    /** Ön cam: yalnızca maksimum buğu giderme (Hvac 24) okunur (araçta ölçüldü; FRONT_GLASS_HEAT 65 hiç değişmedi). */
    fun decodeFront(maxDeforest: Int?): Boolean? = maxDeforest?.let { it > 0 }

    /** Arka cam: HVAC arka buğu (80) okunur (araçta ölçüldü: 0 → 1). */
    fun decodeRear(hvacDefrost: Int?): Boolean? = hvacDefrost?.let { it > 0 }

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
     * Dokunma = aç/kapat (durdurma değeri bilinmiyor, ara konum yok): kapalı → tam aç, açık → kapat.
     * Hareket halindeyse ters yöne (araçta hareket sırasındaki komut yönü ters çevirir, durdurmaz). Yarıda duran
     * perdede yön son adımın tersidir ([lastOpening] true ise kapat); bilinmiyorsa aç. Durum bilinmiyorsa null.
     */
    fun tapStep(state: SunshadeState, lastOpening: Boolean?): Float? = when (state) {
        SunshadeState.CLOSED -> 1f
        SunshadeState.OPEN -> 0f
        SunshadeState.PARTIAL -> if (lastOpening == true) 0f else 1f
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

    /**
     * Buğu yazması: ön cam HV 24, arka cam HV 80 (ikisi de araçta doğrulandı: 1 = açık). Kapatma değeri
     * [ComfortWriter.DEFROST_OFF_VALUE]. HV 65 ve CS 159 araçta etkisiz/ölçülmedi, yazılmaz.
     */
    fun defrostCommands(front: Boolean, on: Boolean): List<ComfortCmd> {
        val v = if (on) 1 else ComfortWriter.DEFROST_OFF_VALUE
        val id = if (front) HvacID.ID_AC_MAX_FRONT_DEFOREST else HvacID.ID_AC_REAR_DEFROST
        return listOf(ComfortCmd(VDEventCarInfo.MODULE_HVAC, id, v))
    }

    /** Perde yazması VR modülünden (327696) kimlik 124 (araçta doğrulandı); CarSetting 10 yazmaları yok sayılıyor. */
    fun sunshadeCommand(value: Int): ComfortCmd =
        ComfortCmd(ComfortWriter.MODULE_VR, ComfortWriter.ID_VR_SUN_SHADE, value)
}

/**
 * Konfor widget'larının (tavan perdesi, buğu, direksiyon ısıtma) yazma noktası. Yalnızca perde araçta doğrulandı;
 * buğu yalnızca AÇMA doğrulandı, kapatma henüz değil. [COMFORT_WRITE_VERIFIED] ve ilgili özellik bayrağı true
 * olmadıkça (ve test sürümünde hiçbir zaman) araca HİÇBİR ŞEY gitmez. Araca ulaşan tek yol `LauncherEdition.sendItemValue` (test sürümünde boş).
 * Kapı [ClimateWriter.canWrite] ile aynıdır.
 */
internal object ComfortWriter {

    /** Ana anahtar: araç testinden sonra true yapılır. Özellik bayrakları da true olmalı. */
    const val COMFORT_WRITE_VERIFIED = true
    const val SUNSHADE_WRITE_VERIFIED = true

    /** Bagaj kapağı yazması araçta DOĞRULANMADI: false iken kutucuk salt-okur, dokunuş "henüz doğrulanmadı" ipucu gösterir. */
    const val TRUNK_WRITE_VERIFIED = false

    /** Bagaj yazması hangi yoldan: true VR 121 (1 aç / 2 kapat), false CarSetting 92 (3 aç / 4 kapat). Araç testinde çalışana ayarlanır. */
    const val TRUNK_VIA_VR = true

    /** VrID.ID_VR_TRUNK (bagaj yazma adayı). */
    const val ID_VR_TRUNK = 121

    /** Hareketteki bagajı durduran CarSetting 92 değeri; araçta bulunamadı (2 denenecek). Null iken dokunuş yalnızca ipucu gösterir. */
    val TRUNK_STOP_VALUE: Int? = null

    /** Durdurma değeri doğrulandı mı. */
    val trunkStopKnown: Boolean get() = TRUNK_STOP_VALUE != null

    /** Buğu AÇMA doğrulandı; KAPATMA doğrulanmadı, o yüzden kapalı kalır (kapatma değeri [DEFROST_OFF_VALUE] denenecek). */
    const val DEFROST_WRITE_VERIFIED = false

    /** VDEventCarInfo.MODULE_VR ve VrID.ID_VR_SUN_SHADE: perde yazması buradan gider (CarSetting 10 yazmaları yok sayılır). */
    const val MODULE_VR = 327696
    const val ID_VR_SUN_SHADE = 124

    /** Perde komut değerleri (VR 124, araçta ölçüldü): 1 aç, 2 kapat. Hareket sırasındaki komut yönü ters çevirir. */
    const val SUNSHADE_OPEN_VALUE = 1
    const val SUNSHADE_CLOSE_VALUE = 2

    /** Buğu kapatma değeri (Hvac 24/80); 0 ve ikinci 1 kapatmadı. 2 araçta denenecek. */
    const val DEFROST_OFF_VALUE = 2 // araçta denenecek

    /** Perdenin tam açılma / kapanma süresi (ms), araçta ölçüldü. */
    const val SUNSHADE_OPEN_MS = 6_800L
    const val SUNSHADE_CLOSE_MS = 8_100L

    /** Tam yol süresi (ara konum hesabı için; yalnızca durdurma değeri bilinirse kullanılır). */
    val SUNSHADE_TRAVEL_MS: Long? = SUNSHADE_CLOSE_MS

    /** Perdeyi yarıda durduran değer; araçta bulunamadı (VR 124 = 3 denenecek). Null iken yalnızca tam açık/kapalı. */
    val SUNSHADE_STOP_VALUE: Int? = null

    /** Ara konum (yüzde) kontrolü mümkün mü: durdurma değeri biliniyorsa. */
    val partialPositionKnown: Boolean get() = SUNSHADE_STOP_VALUE != null

    /** Saf kapı: ana anahtar ve özellik bayrağı true ve salt-okur sürüm değilse yazılır. */
    fun canWrite(master: Boolean, feature: Boolean, readOnly: Boolean): Boolean =
        ClimateWriter.canWrite(master && feature, readOnly)

    val sunshadeEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, WidgetWritePolicy.gate(SUNSHADE_WRITE_VERIFIED, LauncherEdition.WRITE_TEST), LauncherEdition.READ_ONLY)
    val trunkEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, WidgetWritePolicy.gate(TRUNK_WRITE_VERIFIED, LauncherEdition.WRITE_TEST), LauncherEdition.READ_ONLY)
    val defrostEnabled: Boolean get() = canWrite(COMFORT_WRITE_VERIFIED, WidgetWritePolicy.gate(DEFROST_WRITE_VERIFIED, LauncherEdition.WRITE_TEST), LauncherEdition.READ_ONLY)

    /**
     * Kapılı yazma (birim testli): [allowed] false ise [send] hiç çağrılmaz ve false döner.
     */
    fun writeAll(allowed: Boolean, commands: List<ComfortCmd>, send: (ComfortCmd) -> Unit): Boolean {
        if (!allowed) return false
        commands.forEach(send)
        return true
    }

    /**
     * Park kapılı yazma (saf, birim testli): [enabled] false ise DISABLED, [parked] false ise NOT_PARKED; ikisinde de [send] çağrılmaz.
     * [parked] tembel: yalnızca kapı açıksa araçtan okunur.
     */
    fun writeParked(enabled: Boolean, parked: () -> Boolean, commands: List<ComfortCmd>, send: (ComfortCmd) -> Unit): TrunkWriteResult {
        if (!enabled) return TrunkWriteResult.DISABLED
        if (!parked()) return TrunkWriteResult.NOT_PARKED
        commands.forEach(send)
        return TrunkWriteResult.SENT
    }

    /** Park kararı: araç testi aracıyla aynı [ParkGate] (gösterge hızı 119 == 0 ve eski hız 0 değil). Arka planda çağrılır. */
    fun parkedNow(): Boolean {
        val ro = VDEventCarInfo.MODULE_READONLY_INFO
        return ParkGate.evaluate(
            CarSignals.readInt(ro, ReadOnlyID.ID_SPEED_GAUGE_DISPLAY), CarSignals.readInt(ro, ReadOnlyID.ID_CAR_SPEED),
        ).allowed
    }

    /** Bagaj aç/kapat. Doğrulanmadıysa ya da araç park halinde değilse araca dokunmaz. Arka planda çağrılır. */
    fun setTrunk(open: Boolean): TrunkWriteResult =
        writeParked(trunkEnabled, ::parkedNow, listOf(TrunkMath.command(open, TRUNK_VIA_VR)), ::sendToCar)

    /** Hareketteki bagajı durdur (CarSetting 92 ← [TRUNK_STOP_VALUE]); değer bilinmiyorsa DISABLED. Arka planda çağrılır. */
    fun stopTrunk(): TrunkWriteResult {
        val v = TRUNK_STOP_VALUE ?: return TrunkWriteResult.DISABLED
        val cmd = ComfortCmd(VDEventCarInfo.MODULE_CAR_SETTING, CarSettingID.ID_CAR_REAR_DOOR_CONTROL, v)
        return writeParked(trunkEnabled, ::parkedNow, listOf(cmd), ::sendToCar)
    }

    /** Buğu: ön/arka cam aç/kapat. Kapalıyken false döner ve araca dokunmaz. */
    fun setDefrost(front: Boolean, on: Boolean): Boolean =
        writeAll(defrostEnabled, ComfortMath.defrostCommands(front, on), ::sendToCar)

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
    val trunk: TrunkReading? = null,
)

/** Konfor sinyallerini okur (yalnızca okuma; arka plan thread'inde çağrılır). */
internal object ComfortReader {
    /** [withTrunk] yalnızca bagaj kutucuğu için (üç ek okuma); diğer konfor kutucukları okumaz. */
    fun read(withTrunk: Boolean = false): ComfortReading {
        if (LauncherDemo.enabled) return LauncherDemo.comfort()
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) return ComfortReading()
        return try {
            fun cs(id: Int) = proxy.getItemValues(VDEventCarInfo.MODULE_CAR_SETTING, id)?.firstOrNull()
            fun hv(id: Int) = proxy.getItemValues(VDEventCarInfo.MODULE_HVAC, id)?.firstOrNull()
            fun ro(id: Int) = proxy.getItemValues(VDEventCarInfo.MODULE_READONLY_INFO, id)?.firstOrNull()
            ComfortReading(
                connected = true,
                sunshade = ComfortMath.decodeSunshade(cs(CarSettingID.ID_CAR_SUNSHADE)),
                frontDefrost = ComfortMath.decodeFront(hv(HvacID.ID_AC_MAX_FRONT_DEFOREST)),
                rearDefrost = ComfortMath.decodeRear(hv(HvacID.ID_AC_REAR_DEFROST)),
                trunk = if (withTrunk) {
                    TrunkReading(ro(ReadOnlyID.ID_PLG_POSITION), ro(ReadOnlyID.ID_TRUNK_STATE), cs(CarSettingID.ID_CAR_REAR_DOOR_CONTROL))
                } else null,
            )
        } catch (e: RuntimeException) {
            ComfortReading()
        }
    }
}
