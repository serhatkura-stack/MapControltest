package com.mapcontrol.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.client.bind.VDServiceDef
import com.desaysv.ivi.vdb.event.id.media.VDEventMedia
import com.desaysv.ivi.vdb.event.id.media.VDMediaType
import com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlay
import com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlayActionInfo
import java.util.concurrent.Executors

/**
 * Müzik kartı / mini gösterge seçim kuralları (saf, JVM'de sınanır).
 *
 * 6 Kasım araç: BT yeniden bağlanınca OEM ses kaynağı radyoda kaldı (svaudioapp odak tutuyor), kart radyonun boş "am"
 * oturumunu gösterdi ve BT çalması 1-2 sn sonra durdu. Kurallar: hiçbir oturum çalmıyorsa ve bir Bluetooth oturumu varsa
 * OEM ses uygulamasının (radyo) oturumu karta seçilmez.
 */
internal object MediaSourcePick {

    /** A2DP sink oturumu (DsvBluetooth = com.android.bluetooth, a2dpsink/mbs/A2dpMediaBrowserService). */
    const val BT_PKG = "com.android.bluetooth"

    /** OEM ses merkezi (radyo/USB; SVLauncher ImpAudioSourceManager.java:52 mAudioPagePkg). */
    const val OEM_AUDIO_PKG = "com.desaysv.audioapp"

    class Cand(val pkg: String?, val playing: Boolean, val hasTitle: Boolean)

    fun isBluetooth(pkg: String?) = pkg == BT_PKG

    /**
     * Seçilecek oturumun sırası; liste boşsa -1. [current] seçili oturumun sırası (yoksa -1).
     * 1) Çalan ilk oturum. 2) Seçili oturum korunur (duraklatılan müzik atlamaz) — seçili oturum OEM radyosuysa ve BT oturumu
     * varsa korunmaz. 3) Başlığı olan ilk oturum; o OEM radyosuysa ve BT varsa BT. 4) BT. 5) İlk oturum.
     */
    fun pick(cands: List<Cand>, current: Int): Int {
        if (cands.isEmpty()) return -1
        cands.indexOfFirst { it.playing }.let { if (it >= 0) return it }
        val bt = cands.indexOfFirst { isBluetooth(it.pkg) }
        if (current in cands.indices && !(bt >= 0 && cands[current].pkg == OEM_AUDIO_PKG)) return current
        val titled = cands.indexOfFirst { it.hasTitle }
        if (titled >= 0 && !(bt >= 0 && cands[titled].pkg == OEM_AUDIO_PKG)) return titled
        if (bt >= 0) return bt
        return if (titled >= 0) titled else 0
    }

    /** Oynat'tan önce OEM kaynağı Bluetooth müziğine geçirilmeli mi: kaynak okunamadıysa (null) da geçirilir (OEM gibi zararsız). */
    fun needsBtSwitch(currentSourceType: Int?): Boolean = currentSourceType != OemAudioSource.BT_MUSIC
}

/**
 * OEM ses kaynağını Bluetooth müziğine geçirme, OEM başlatıcının kaynak listesindeki yolla birebir:
 * `SVLauncher/.../common/model/media/ImpAudioSourceManager.java:553-571 changeMeidaSource` → VDBus `set(MEDIA_SOURCE_PLAY_STATUS,
 * VDMediaPlay{mediaType, playAction=6 OPEN_SOURCE, info{reason=13 REASON_SOURCE_LIST, disPlayView=2 BACKGROUND, intent=1}})`
 * (çağıran `MediaViewModel.java:378-396`, BT_MUSIC = 13: `vdbus/.../media/VDValueMedia.java:194`). Geçerli kaynak
 * `CURRENT_SOURCE_TYPE` (393217) ile okunur (ImpAudioSourceManager.java:61-63, 232-238). Yalnızca kullanıcı Oynat'a
 * bastığında çalışır; binder çağrıları arka plandadır. Günlük etiketi `MediaSource` (kişisel veri yok).
 */
internal object OemAudioSource {

    const val TAG = "MediaSource"

    const val BT_MUSIC = 13
    const val PLAY_ACTION_OPEN_SOURCE = 6
    const val REASON_SOURCE_LIST = 13
    const val DISPLAY_BACKGROUND = 2
    const val INTENT_DEFAULT_VIEW = 1

    /** Kaynak değiştikten sonra Oynat göndermeden önce beklenen süre (OEM ses merkezinin odağı bırakması için). */
    private const val SETTLE_MS = 800L
    private const val BIND_WAIT_MS = 1_500L

    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "oem-audio-source").apply { isDaemon = true } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var bus: VDBus? = null

    /** OEM ile aynı yük: BT müziğini arka planda aç (OEM medya ekranı öne gelmez). */
    fun switchPayload(type: Int = BT_MUSIC): VDMediaPlay = VDMediaPlay().apply {
        putMediaType(type)
        putPlayAction(PLAY_ACTION_OPEN_SOURCE)
        putMediaPlayActionInfo(VDMediaPlayActionInfo().apply {
            putReason(REASON_SOURCE_LIST)
            putDisPlayView(DISPLAY_BACKGROUND)
            putIntent(INTENT_DEFAULT_VIEW)
        })
    }

    /**
     * Kullanıcı Oynat'a bastı ve bir Bluetooth oturumu var: OEM kaynağı BT değilse önce BT'ye geçirir, sonra [play]'i ana
     * iş parçacığında çalıştırır. Medya servisi yoksa yalnızca [play].
     */
    fun playBluetooth(context: Context, play: Runnable) {
        // VDBus init ana iş parçacığında (PhoneBackends/FavoriteKey gibi); bağlanma ve binder çağrıları arka planda.
        if (bus == null) bus = try {
            VDBus.getDefault().also { it.init(context.applicationContext) }
        } catch (t: Throwable) {
            Log.w(TAG, "VDBus init: ${t.javaClass.simpleName}")
            null
        }
        io.execute {
            val b = connect()
            if (b == null) {
                Log.i(TAG, "play: media service unavailable, plain play")
                main.post(play)
                return@execute
            }
            val current = try {
                VDMediaType.getValue(b.getOnce(VDEventMedia.CURRENT_SOURCE_TYPE))?.mediaType
            } catch (t: Throwable) {
                null
            }
            if (!MediaSourcePick.needsBtSwitch(current)) {
                Log.i(TAG, "play: oemSource=$current already BT_MUSIC, play")
                main.post(play)
                return@execute
            }
            try {
                b.set(VDMediaPlay.createEvent(VDEventMedia.MEDIA_SOURCE_PLAY_STATUS, switchPayload()))
                Log.i(TAG, "play: oemSource=$current -> switch BT_MUSIC($BT_MUSIC) sent, play in ${SETTLE_MS}ms")
            } catch (t: Throwable) {
                Log.w(TAG, "play: switch failed ${t.javaClass.simpleName}, plain play")
                main.post(play)
                return@execute
            }
            main.postDelayed(play, SETTLE_MS)
        }
    }

    /** io: medya servisine bağlanır (en çok [BIND_WAIT_MS] bekler). */
    private fun connect(): VDBus? {
        val b = bus ?: return null
        return try {
            if (!b.isServiceConnected(VDServiceDef.ServiceType.MEDIA)) {
                b.bindService(VDServiceDef.ServiceType.MEDIA)
                val until = SystemClock.elapsedRealtime() + BIND_WAIT_MS
                while (!b.isServiceConnected(VDServiceDef.ServiceType.MEDIA) && SystemClock.elapsedRealtime() < until) {
                    SystemClock.sleep(100)
                }
            }
            if (b.isServiceConnected(VDServiceDef.ServiceType.MEDIA)) b else null
        } catch (t: Throwable) {
            Log.w(TAG, "media bind: ${t.javaClass.simpleName}")
            null
        }
    }
}
