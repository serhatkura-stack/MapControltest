package com.mapcontrol.launcher

import com.desaysv.ivi.extra.project.carinfo.HvacID
import com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy
import com.desaysv.ivi.vdb.event.id.carinfo.VDEventCarInfo
import kotlin.math.roundToInt

/** Klima tarafı: sürücü ya da yolcu. */
internal enum class ClimateSide { DRIVER, PASSENGER }

/**
 * Klima sürüklemesinin saf hesapları (arayüzden bağımsız, birim testli): sürükleme mesafesi → adım,
 * sınırlama, ham kodlama.
 */
internal object ClimateMath {
    /**
     * Sıcaklık adımı. Geçerli aralık (OEM `VRC a/a.java`, T1E24: ham = (°C - 15,5) * 2): ham 0 = LO, 1..29 = 16,0..30,0 °C, ham 30 = HI.
     * 30 °C üstü (ham 31..33) OEM'de yoktur ve yazılmaz.
     */
    const val TEMP_STEP = 0.5

    /** LO ve HI uçları; sıcaklık hep bu ikisi arasındadır. LO = ham 0, HI = ham 30 (sıcaklık değeri değil, uç işaretidir). */
    const val LO_TEMP = 15.5
    const val HI_TEMP = 30.5
    const val RAW_LO = 0
    const val RAW_HI = 30

    const val FAN_MIN = 1
    // Araçta ölçüldü: A/C açıkken 4, maks. ön buğu çözmede 10, buğu kapanınca 1 okundu; en az 10'a kadar çıkıyor.
    // Gösterim için 10; yazma kapılı (ölçek doğrulanana kadar araca gitmez).
    const val FAN_MAX = 10

    /** Sıcaklık: dikey her 40dp yol = 1 adım (0,5 °C). */
    const val TEMP_DRAG_DP_PER_STEP = 40f

    /** Fan: yatay her 12dp yol = 1 kademe (hücre 120dp; tüm 1..10 aralığı tek harekette). */
    const val FAN_DRAG_DP_PER_STEP = 12f

    /**
     * Sürükleme mesafesinden (px) tam adım sayısı; sıfıra doğru yuvarlanır, yani yarım adım sayılmaz.
     * [delta] işaretlidir: sıcaklıkta yukarı = pozitif (çağıran `downY - y` verir), fanda sağ = pozitif.
     */
    fun stepsForDrag(delta: Float, density: Float, dpPerStep: Float): Int =
        (delta / (dpPerStep * density)).toInt()

    /** 0,5'e yuvarlar ve [LO_TEMP]..[HI_TEMP] aralığına sıkıştırır (uçlar LO / HI). */
    fun clampTemp(c: Double): Double =
        ((c / TEMP_STEP).roundToInt() * TEMP_STEP).coerceIn(LO_TEMP, HI_TEMP)

    fun isLo(c: Double): Boolean = c <= LO_TEMP
    fun isHi(c: Double): Boolean = c >= HI_TEMP

    /** [start] sıcaklığından [steps] adım sonra hedef (sınırlı). */
    fun targetTemp(start: Double, steps: Int): Double = clampTemp(start + steps * TEMP_STEP)

    /**
     * Fan hedefi. 0 (kapalı) yalnızca dokunarak ya da sola kaydırmadan gelir: sürüklemede 1..10'a sıkışır,
     * kapalıyken sola sürükleme kapalı kalır.
     */
    fun targetFan(start: Int, steps: Int): Int = when {
        steps == 0 -> start
        start == 0 && steps < 0 -> 0
        else -> (start + steps).coerceIn(FAN_MIN, FAN_MAX)
    }

    /** Ham değer: °C = 15,5 + ham/2 ([OurBottomBar.decodeTemp]'in tersi); LO = 0, HI = 30. */
    fun encodeTemp(c: Double): Int = ((clampTemp(c) - LO_TEMP) * 2).roundToInt()

    /** Klima gücü (HVAC 18): okuma 1 = açık; yazma 1 aç / 2 kapat (OEM `HvacModel.onPowerClick`). */
    const val POWER_ON = 1
    const val POWER_OFF = 2

    /**
     * OEM sesli komutu klima kapalıyken sıcaklık/fan yazmadan önce 18 ← 1 gönderir (`VRC a/b.java:1285`). Güç okunabiliyor
     * ve açık değilse önce açılır; okunamıyorsa (sinyal yok) hiçbir şey eklenmez.
     */
    fun needsPowerOn(powerRaw: Int?): Boolean = powerRaw != null && powerRaw != POWER_ON

    /**
     * Sıcaklık/fan yazmadan önce güç (18 ← 1) gönderilsin mi. [quiet] (OEM dock kimlikleri 76/78/77): HAYIR. OEM alt dock'u
     * klima kapalıyken de yalnızca dock kimliğini yazar, güç göndermez (SVSystemUI `svhvac/viewmodel/MainViewModel.java:815-870`
     * leftTempAdd/Down/Set/Up, `:633-648` addDockWindSpeed; tek kapı kontak: `car_power_on_tips`). 18 ← 1 panel yoludur
     * (`HvacModel.java:953-956` onPowerClick; panelin fan +/− kapalıyken yalnızca güç gönderir, `MainViewModel.java:1248-1267`)
     * ve 6 Kasım araçta klima kapalıyken OEM panelini açtı. Panel kimlikleri (10/13/12) ile eski yol (sesli komut gibi) korunur.
     * Bizim araçta da doğrulandı: SVSystemUI'de HVAC 18 hiç yazılmıyor, yalnız okunuyor (`model/svhvac/e/c.java:486-488` x0);
     * dock'un tek HVAC 9 yazması yukarı kaydırmadır (`model/svhvac/e/b.java:174-180`).
     */
    fun powerOnBeforeWrite(quiet: Boolean, powerRaw: Int?): Boolean = !quiet && needsPowerOn(powerRaw)

    /**
     * Fan ham değeri doğrudan kademedir (okuma da öyle); yazılan 1..10. Fanı 1'in altına indirmek klimayı KAPATMAZ ve 0 yazılmaz:
     * OEM de böyle (T1E24 dökümü, 6 Kasım). Otomatik panel çubuğu ilerleme + 1 yazar, en az 1 (`SVHvac/.../HvacPageView.java:303`,
     * `:327`; eksi düğmesi ilerleme 0'da durur `:2135-2138`); alt dock `Math.max(1, ilerleme)` ve yalnız `p > 1` iken azaltır
     * (`SVSystemUI/.../i/a/x.java:29`, `:108`); sesli komut "MIN/MINUS" alt sınır `J() = 1`'de kalır, "zaten en düşük" der
     * (`SVVrControl/.../vrcontrol/a/b.java:897`, `:925-965`, `a.java:32-34`). Kapatma yalnızca ayrı güç düğmesi: HVAC 18 ← 2
     * (`SVHvac/.../model/svhvac/f/e.java:875-880` y(); sesli "klimayı kapat" `b.java:663`).
     */
    fun encodeFan(level: Int): Int = level.coerceIn(1, FAN_MAX)
}

/**
 * Klima yazma noktası. Yazmalar her zaman açık ([LauncherEdition.WRITES_ON]); araca ulaşan tek yol
 * `LauncherEdition.sendItemValue` → [WidgetWriter] (beyaz liste + `WidgetWrite` günlüğü).
 */
internal object ClimateWriter {

    /** Araç testinde doğrulanınca true yapılır (koltuk soğutmadaki SEAT_VENT_WRITE_VERIFIED gibi). */
    const val CLIMATE_WRITE_VERIFIED = false

    /** Saf kapı: yalnızca doğrulanmışsa ve salt-okur sürüm değilse yazılır. */
    fun canWrite(verified: Boolean, readOnly: Boolean): Boolean = verified && !readOnly

    /** Arayüzün "yazılacak mı / bilgi mesajı mı" kararı için. */
    val enabled: Boolean get() = canWrite(WidgetWritePolicy.gate(CLIMATE_WRITE_VERIFIED, LauncherEdition.WRITES_ON), LauncherEdition.READ_ONLY)

    /**
     * Sıcaklık kimliği. [quiet] (ayar "fabrika paneli: Gizle"): OEM alt dock'unun kimlikleri, sürücü 76 / yolcu 78, aynı ham
     * kodlama. Bizim araçta (T1E24) doğrulandı: dock sürücü → HVAC 76 (`SVSystemUI/.../model/svhvac/e/c.java:393-400` R),
     * yolcu → 78 (`:431-438` l0), klima tipinden bağımsız (`NavigationBarView.java` tip 0/1 → `present/svhvac/b/b.java:388-397`).
     * Aksi halde OEM panelinin 10 / 13 (`SVHvac/.../model/svhvac/f/e.java:526-539`, `:866-872`).
     */
    fun tempId(side: ClimateSide, quiet: Boolean): Int = when {
        quiet && side == ClimateSide.DRIVER -> HvacID.ID_AC_DRIVER_SET_TEMPERATURE
        quiet -> HvacID.ID_AC_PASSENGER_SET_TEMPERATURE
        side == ClimateSide.DRIVER -> HvacID.ID_AC_TEMPERATURE_DRIVER
        else -> HvacID.ID_AC_TEMPERATURE_COPILOT
    }

    /**
     * Fan kimliği: [quiet] → 77, aksi halde panelin 12. Ham değer ikisinde de kademe.
     *
     * KARAR (belirsiz kanıt, mevcut davranış korunuyor): bizim aracın OEM dock'u otomatik klimada (config 4 == 1, günlükte
     * `HvacConfig =0`) fanı 77'ye DEĞİL 12'ye yazar (`SVSystemUI/.../util/u.java:95-97` l() → tip 2 → `f1.java:206-208` →
     * `j/h/b/c1.java:653-657` U0: HVAC 12). 77 bizim SystemUI'de yalnız elektrikli/manuel klima düzeninin fanıdır
     * (`model/svhvac/e/c.java:476-483` w0) ve arkadaşın dock'unun kimliğidir. 12 aynı zamanda panelin kimliği; 12 yazmasının
     * ECU'da paneli (HVAC 9 bit 0) açıp açmadığı araçta ölçülmedi, 77'nin bizim ECU'da kademeyi değiştirdiği de günlükle
     * kanıtlanmadı. Araç testi bitene kadar 77 kalır; her dock fan yazmasında karar `WidgetWrite` günlüğüne düşer.
     */
    fun fanId(quiet: Boolean): Int = if (quiet) HvacID.ID_AC_SET_BLOW_LEVEL else HvacID.ID_AC_BLOW_SPEED_LEVEL

    /** OEM dock'unun bizim araçtaki fan kimliği (otomatik klima): bilgi ve test için; yazmada kullanılmıyor (bkz. [fanId]). */
    const val OEM_DOCK_FAN_ID_T1E24 = 12

    /** Sıcaklığı yazar. Kapalıyken false döner ve araca dokunmaz. */
    fun setTemperature(side: ClimateSide, celsius: Double, quiet: Boolean): Boolean {
        if (!enabled) return false
        powerOnIfOff(quiet)
        return send(tempId(side, quiet), ClimateMath.encodeTemp(celsius))
    }

    /** Fan kademesini (1..10) yazar. Kapalıyken ya da level < 1 iken (0 yazılmaz) false döner ve araca dokunmaz. */
    fun setFan(level: Int, quiet: Boolean): Boolean {
        if (!enabled) return false
        if (level < 1) return false
        powerOnIfOff(quiet)
        if (quiet) {
            android.util.Log.i(WidgetWritePolicy.LOG_TAG, "widget=Climate fan dock-id=${fanId(true)} " +
                "(OEM T1E24 auto dock uses $OEM_DOCK_FAN_ID_T1E24; kept pending car test)")
        }
        return send(fanId(quiet), ClimateMath.encodeFan(level))
    }

    /**
     * Panel yolunda klima kapalıysa önce aç (HVAC 18 ← 1, sesli komut gibi). Dock yolunda ([quiet]) güç gönderilmez, OEM dock'u
     * gibi yalnızca 76/78/77 gider ([ClimateMath.powerOnBeforeWrite]); karar `WidgetWrite` günlüğüne yazılır. Arka planda çağrılır.
     */
    private fun powerOnIfOff(quiet: Boolean) {
        val power = CarSignals.readInt(VDEventCarInfo.MODULE_HVAC, HvacID.ID_AC_WORKING)
        if (ClimateMath.powerOnBeforeWrite(quiet, power)) {
            send(HvacID.ID_AC_WORKING, ClimateMath.POWER_ON)
        } else if (quiet && ClimateMath.needsPowerOn(power)) {
            android.util.Log.i(WidgetWritePolicy.LOG_TAG, "widget=Climate power=$power dock-path: no power write (OEM dock)")
        }
    }

    private fun send(id: Int, raw: Int): Boolean {
        val proxy = CarInfoProxy.getInstance()
        if (!proxy.isServiceConnnected) {
            android.util.Log.w("WidgetWrite", "widget=Climate id=$id value=$raw result=refused-no-service")
            return false
        }
        LauncherEdition.sendItemValue(proxy, VDEventCarInfo.MODULE_HVAC, id, raw)
        return true
    }
}
