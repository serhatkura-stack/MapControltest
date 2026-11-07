package com.mapcontrol.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Araç servisinin (Spi2Ethenet, MCU köprüsü) logcat'e yazdığı MCU çerçevelerinden motor devri (saf kısım; JVM'de sınanır).
 *
 * Satır biçimi (7 Kasım araç kaydı): `0029801327 | Read: 03 73 53 10 00 FE 42 02 07 88 FE 43 02 00 64 FE 8B 02 43 75`.
 * `03 73 53` grubundan sonra uzunluk bayt'ı LL ve `00` gelir; ardından LL-1 bayt TLV: `<tür> <kimlik> <uzunluk> <veri…>`
 * (çoğu `FE`, veri büyük uçlu). Sinyal 0x8D (2 bayt) sürüşte ~7/sn güncellenir; araç dururken ortancası 765 (rölanti),
 * giderken 2651, aralık 662..4094: ham değer doğrudan dev/dk.
 *
 * Satır başına iş: tek `indexOf` (öteki satırlar orada elenir), sonra elle onaltılık çözme; düzenli ifade ve ayırma yok.
 */
internal object SpiRpmParse {
    /** Devir sinyalinin kimliği (`FE 8D 02 hi lo`). */
    const val RPM_ID = 0x8D
    const val TYPE_FE = 0xFE
    const val MAX_RPM = 9_000
    private const val MARK = "Read: 03 73 53 "

    /** Satırdaki devir (dev/dk); satır `03 73 53` çerçevesi değilse, 0x8D yoksa ya da bozuksa -1. */
    fun rpm(line: String): Int {
        val m = line.indexOf(MARK)
        if (m < 0) return -1
        var i = m + MARK.length
        val ll = hexByte(line, i)
        if (ll < 1) return -1
        if (hexByte(line, i + 3) != 0) return -1
        i += 6
        var left = ll - 1
        while (left >= 3) {
            val type = hexByte(line, i)
            val id = hexByte(line, i + 3)
            val len = hexByte(line, i + 6)
            if (type < 0 || id < 0 || len < 0 || 3 + len > left) return -1
            val data = i + 9
            if (type == TYPE_FE && id == RPM_ID) {
                if (len != 2) return -1
                val hi = hexByte(line, data)
                val lo = hexByte(line, data + 3)
                if (hi < 0 || lo < 0) return -1
                val v = (hi shl 8) or lo
                return if (v <= MAX_RPM) v else -1
            }
            i = data + len * 3
            left -= 3 + len
        }
        return -1
    }

    /** [i]'deki iki onaltılık rakam (ardından boşluk ya da satır sonu); değilse -1. */
    fun hexByte(s: String, i: Int): Int {
        if (i < 0 || i + 1 >= s.length) return -1
        if (i + 2 < s.length && s[i + 2] != ' ' && s[i + 2] != '\r') return -1
        val h = hex(s[i])
        val l = hex(s[i + 1])
        return if (h < 0 || l < 0) -1 else (h shl 4) or l
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}

/**
 * Hızlı devir: spor ekranı görünürken `logcat -s Spi2Ethenet:I` alt sürecini okur ([SpiRpmParse]) ve son devri varış
 * anıyla yayınlar. READ_LOGS yoksa (`adb shell pm grant <paket> android.permission.READ_LOGS`) hiç başlamaz; devir o zaman
 * yavaş kaynaktan (ReadOnly 62). [stop] alt süreci öldürür. [onRpm] okuyucu iş parçacığında, yalnız devir [BAND_RPM]'den
 * çok değişince çağrılır. Ölçüm: `adb logcat -s SportRpm` ("frame n=… ort=…ms", 10 sn'de bir).
 */
internal class RpmFrameReader(private val context: Context, private val onRpm: () -> Unit) {
    @Volatile var rpm = -1
        private set
    @Volatile var rpmAt = 0L
        private set
    @Volatile private var running = false
    /** Her [start] yeni kuşak: eski iş parçacığı [stop]tan sonra hemen yeniden başlatılırsa ikinci süreç açmaz. */
    @Volatile private var gen = 0
    @Volatile private var proc: Process? = null
    private var thread: Thread? = null
    private var sentRpm = -1

    /** Son devir [FRESH_MS] içinde geldi mi. */
    fun live(now: Long = SystemClock.elapsedRealtime()): Boolean = rpm >= 0 && now - rpmAt < FRESH_MS

    fun start() {
        if (running) return
        if (!hasPermission(context)) {
            if (!warned) { warned = true; Log.i(TAG, "READ_LOGS yok: hızlı devir kapalı") }
            return
        }
        running = true
        sentRpm = -1
        val my = ++gen
        val t = Thread({ loop(my) }, "sport-rpm").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        thread = t
        t.start()
    }

    fun stop() {
        if (!running) return
        running = false
        gen++
        proc?.destroy()
        proc = null
        thread?.interrupt()
        thread = null
    }

    private fun alive(my: Int): Boolean = running && gen == my

    private fun loop(my: Int) {
        var tries = 0
        while (alive(my) && tries < MAX_TRIES) {
            tries++
            val p = try {
                ProcessBuilder("logcat", "-v", "raw", "-T", "1", "-s", "$SOURCE_TAG:I").redirectErrorStream(true).start()
            } catch (t: Throwable) {
                Log.w(TAG, "logcat başlatılamadı: ${t.javaClass.simpleName}")
                return
            }
            if (!alive(my)) { p.destroy(); return }
            proc = p
            if (!alive(my)) { p.destroy(); return }
            try {
                read(my, BufferedReader(InputStreamReader(p.inputStream), 8_192))
            } catch (_: Throwable) {
            } finally {
                p.destroy()
            }
            if (alive(my)) try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun read(my: Int, r: BufferedReader) {
        var winAt = SystemClock.elapsedRealtime()
        var winCpu = Debug.threadCpuTimeNanos()
        var frames = 0
        var lines = 0
        while (alive(my)) {
            val line = r.readLine() ?: return
            lines++
            val v = SpiRpmParse.rpm(line)
            val now = SystemClock.elapsedRealtime()
            if (v >= 0) {
                frames++
                rpm = v
                rpmAt = now
                if (sentRpm < 0 || kotlin.math.abs(v - sentRpm) >= BAND_RPM) {
                    sentRpm = v
                    onRpm()
                }
            }
            if (now - winAt >= SUMMARY_MS) {
                val cpu = Debug.threadCpuTimeNanos()
                Log.i(TAG, summaryLine(now - winAt, frames, lines, rpm, (cpu - winCpu) / 1_000_000L))
                winAt = now
                winCpu = cpu
                frames = 0
                lines = 0
            }
        }
    }

    companion object {
        const val TAG = "SportRpm"
        const val SOURCE_TAG = "Spi2Ethenet"
        const val PERMISSION = "android.permission.READ_LOGS"
        /** Son devirden bu süre içinde canlı sayılır. */
        const val FRESH_MS = 1_000L
        /** Ana iş parçacığına haber veren en küçük devir değişimi. */
        const val BAND_RPM = 25
        const val SUMMARY_MS = 10_000L
        const val MAX_TRIES = 3
        const val RETRY_MS = 2_000L
        @Volatile private var warned = false

        fun hasPermission(context: Context): Boolean = try {
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        } catch (_: RuntimeException) {
            false
        }

        /** 10 sn özeti (yalnız sayı): devir çerçevesi adedi, ortalama aralık, okunan satır, son devir, okuyucu CPU'su. */
        fun summaryLine(spanMs: Long, frames: Int, lines: Int, rpm: Int, cpuMs: Long): String = String.format(Locale.US,
            "frame n=%d ort=%sms satır=%d son=%d cpu=%dms/%ds", frames,
            if (frames > 0) (spanMs / frames).toString() else "-", lines, rpm, cpuMs, spanMs / 1000)
    }
}

/**
 * Spor ana kadranının düzeni: hızlı devir canlıysa RPM önce (büyük ibre devir, ortada dijital hız), değilse hız önce
 * (saf, JVM'de sınanır). Titremesin diye kare başına karar yok: kaynak [STABLE_MS] boyunca kesintisiz canlı (ya da
 * kesintisiz kayıp) kalınca düzen değişir. Girişteki düzen bu süreçte en son yerleşen düzendir ([SportRpmLayout.last]).
 */
class RpmLayoutGate(initial: Boolean) {
    var rpmFirst = initial
        private set
    private var since = -1L

    /** [now] anında kaynak [live] mi; düzen değiştiyse true. */
    fun update(now: Long, live: Boolean): Boolean {
        if (live == rpmFirst) { since = -1L; return false }
        if (since < 0L) since = now
        if (now - since < STABLE_MS) return false
        rpmFirst = live
        since = -1L
        return true
    }

    /** Görünüm yeniden açıldı: sayaç baştan (düzen korunur). */
    fun restart(rpmFirst: Boolean) {
        this.rpmFirst = rpmFirst
        since = -1L
    }

    companion object {
        const val STABLE_MS = 3_000L
    }
}

/** Bu süreçte en son yerleşen spor düzeni (girişte bitmap'i doğru düzende çizmek için). */
internal object SportRpmLayout {
    @Volatile var last = false
}
