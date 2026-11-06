package com.mapcontrol.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.client.bind.VDServiceDef
import com.desaysv.ivi.vdb.event.id.bt.VDEventBT
import com.desaysv.ivi.vdb.event.id.bt.bean.VDBTMusicPlay
import com.desaysv.ivi.vdb.event.id.media.VDEventMedia
import com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlay
import com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlayActionInfo
import com.desaysv.ivi.vdb.event.id.media.bean.VDMediaType
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

    /**
     * OEM ses merkezi (radyo/USB). Bizim araç (T1E24, svaudioapp T19C V_20251114): `com.desaysv.svaudioapp`
     * (dumpsys media_session: RadioMediaSessionBrowserService / MediaPlaybackService; OEM SVLauncher AudioSourceManager.java:227).
     * Arkadaşın aracı (T18FL4): `com.desaysv.audioapp` (ImpAudioSourceManager.java:52).
     */
    const val OEM_AUDIO_PKG = "com.desaysv.svaudioapp"
    const val OEM_AUDIO_PKG_OLD = "com.desaysv.audioapp"

    class Cand(val pkg: String?, val playing: Boolean, val hasTitle: Boolean)

    fun isBluetooth(pkg: String?) = pkg == BT_PKG

    fun isOemAudio(pkg: String?) = pkg == OEM_AUDIO_PKG || pkg == OEM_AUDIO_PKG_OLD

    /**
     * Seçilecek oturumun sırası; liste boşsa -1. [current] seçili oturumun sırası (yoksa -1).
     * 1) Çalan ilk oturum. 2) Seçili oturum korunur (duraklatılan müzik atlamaz) — seçili oturum OEM radyosuysa ve BT oturumu
     * varsa korunmaz. 3) Başlığı olan ilk oturum; o OEM radyosuysa ve BT varsa BT. 4) BT. 5) İlk oturum.
     */
    fun pick(cands: List<Cand>, current: Int): Int {
        if (cands.isEmpty()) return -1
        cands.indexOfFirst { it.playing }.let { if (it >= 0) return it }
        val bt = cands.indexOfFirst { isBluetooth(it.pkg) }
        if (current in cands.indices && !(bt >= 0 && isOemAudio(cands[current].pkg))) return current
        val titled = cands.indexOfFirst { it.hasTitle }
        if (titled >= 0 && !(bt >= 0 && isOemAudio(cands[titled].pkg))) return titled
        if (bt >= 0) return bt
        return if (titled >= 0) titled else 0
    }

    /** Oynat'tan önce OEM kaynağı Bluetooth müziğine geçirilmeli mi: kaynak okunamadıysa (null) da geçirilir (OEM gibi zararsız). */
    fun needsBtSwitch(currentSourceType: Int?): Boolean = currentSourceType != OemAudioSource.BT_MUSIC

    /**
     * Oynat'ta izlenecek yol. BT servisi bağlıysa kaynak 13 olsa bile `MUSIC_PLAY` gider: `CURRENT_SOURCE_TYPE` OEM'in son
     * seçtiği kaynaktır, odak 42'nin şu anki sahibi değil (navigasyon anonsu, başka uygulama ya da biten HFP araması sonrası
     * 13 kalır ama odak bizde değildir). Komut zararsızdır: OEM BT servisi odağı yalnızca tutmuyorsa ister (`j/e.java:822-833`).
     */
    enum class Path {
        /** OEM kaynağı zaten BT ve BT servisi yok: yalnızca BT oturumuna oynat (OPEN_SOURCE atlanır). */
        ALREADY_BT,
        /** VDBus BT `MUSIC_PLAY{playCtrl=1, playType=0}`: OEM BT servisi odak 42'yi alır ve çalar (svaudioapp'in yolu). */
        BT_PLAY,
        /** VDBus MEDIA `OPEN_SOURCE` reason=8: medya servisi svaudioapp BTMusicService'i başlatır, o da BT_PLAY gönderir. */
        OPEN_SOURCE,
        /** VDBus yok: yalnızca BT oturumuna oynat (odak 42 değilse A2dpMediaBrowserService bunu yok sayar). */
        PLAIN,
    }

    fun path(currentSourceType: Int?, btBus: Boolean, mediaBus: Boolean): Path = when {
        btBus -> Path.BT_PLAY
        !needsBtSwitch(currentSourceType) -> Path.ALREADY_BT
        mediaBus -> Path.OPEN_SOURCE
        else -> Path.PLAIN
    }
}

/**
 * OEM ses kaynağını Bluetooth müziğine geçirme (bizim araç T1E24 dökümü, 6 Kasım):
 *
 * - Araçta ses kaynağını müzik odağının sahibi belirler. BT müziğin odağını (usage=1, content=2, legacyStreamType=42)
 *   yalnızca OEM BT servisi `com.desaysv.ivi.vds.bt` ister: `MUSIC_PLAY` (262948) → `h/h.java:102-104` → `h/g.java:227-235`
 *   → `h/b.java:180-187` `play()` = `g0(1, true)` → `j/e.java:822-833` → `d/c.java:317-344` `u()`.
 * - `com.android.bluetooth` oturumuna giden play/next/prev, odak 42 değilse yok sayılır (`A2dpMediaBrowserService.java:81-89,313`).
 *   Bu yüzden ilk yol svaudioapp'in kendi yolu: `BTMusicManager.java:364-370,524-526` `MUSIC_PLAY{1,0}` (yol BT_PLAY).
 * - Yedek: OEM başlatıcı kartının yolu `AudioSourceManager.java:719-727` `set(MEDIA_SOURCE_PLAY_STATUS, VDMediaPlay{13, 6,
 *   info{reason=8}})`. Medya servisi (`svmediasourceinfo/d/b.java:164-188,326-336`) bunu svaudioapp `BTMusicService`'e
 *   iletir; o yalnızca reason 2/6/8'e tepki verir (`BTMusicService.java:99-127`). Arkadaşın aracındaki reason=13
 *   (REASON_SOURCE_LIST) bizim svaudioapp'te yok sayılır. disPlayView=2 ile OEM ekranı açılmaz (`:128`).
 * - Geçerli kaynak `CURRENT_SOURCE_TYPE` (393217), yük `media.bean.VDMediaType` (`SourceTypeEventHandle.java:35-42`).
 *
 * Yalnızca kullanıcı Oynat'a bastığında çalışır; binder çağrıları arka plandadır. Günlük etiketi `MediaSource`
 * (yalnızca kaynak numarası, yol ve süre; başlık/ad/adres yok).
 */
internal object OemAudioSource {

    const val TAG = "MediaSource"

    const val BT_MUSIC = 13
    const val PLAY_ACTION_OPEN_SOURCE = 6
    /** VDValueMedia REASON_CARDS (SVVDSMedia VDValueMedia.java:151): OEM kart ve SystemUI yolu, iki araçta da var. */
    const val REASON_CARDS = 8
    const val DISPLAY_BACKGROUND = 2
    const val INTENT_DEFAULT_VIEW = 1

    /** VDBTMusicPlay playCtrl=1 (PLAY), playType=0 (svaudioapp BTMusicManager.setPlayOperation). */
    const val BT_PLAY_CTRL_PLAY = 1
    const val BT_PLAY_TYPE_DEFAULT = 0

    /** Komut gittikten sonra BT oturumuna yedek oynat göndermeden önce beklenen süre (odak el değiştirsin). */
    private const val SETTLE_MS = 800L
    private const val BIND_WAIT_MS = 1_500L

    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "oem-audio-source").apply { isDaemon = true } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var bus: VDBus? = null

    /** Yedek yol: OEM kartıyla aynı yük, ama arka planda (OEM medya ekranı öne gelmez). */
    fun switchPayload(type: Int = BT_MUSIC): VDMediaPlay = VDMediaPlay().apply {
        putMediaType(type)
        putPlayAction(PLAY_ACTION_OPEN_SOURCE)
        putMediaPlayActionInfo(VDMediaPlayActionInfo().apply {
            putReason(REASON_CARDS)
            putDisPlayView(DISPLAY_BACKGROUND)
            putIntent(INTENT_DEFAULT_VIEW)
        })
    }

    /** İlk yol: svaudioapp `BTMusicManager.play()` ile aynı yük. */
    fun btPlayPayload(): VDBTMusicPlay = VDBTMusicPlay().apply {
        putPlayCtrl(BT_PLAY_CTRL_PLAY)
        putPlayType(BT_PLAY_TYPE_DEFAULT)
    }

    /**
     * Kullanıcı Oynat'a bastı ve bir Bluetooth oturumu var: OEM BT servisine her zaman çal komutu gider (odak 42 alınır,
     * kaynak 13 olsa da; bkz. [MediaSourcePick.Path]); [play] yedek olarak ana iş parçacığında biraz sonra çalışır
     * (zararsız: oturum zaten çalıyorsa onPlay bir şey yapmaz). VDBus yoksa yalnızca [play].
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
            val t0 = SystemClock.elapsedRealtime()
            // İki servisin bağlanması aynı anda başlar: bekleme en çok BIND_WAIT_MS (art arda ~3 sn değil).
            startBind(VDServiceDef.ServiceType.MEDIA)
            startBind(VDServiceDef.ServiceType.BT)
            val media = connect(VDServiceDef.ServiceType.MEDIA)
            val current = if (media == null) null else try {
                VDMediaType.getValue(media.getOnce(VDEventMedia.CURRENT_SOURCE_TYPE))?.mediaType
            } catch (t: Throwable) {
                Log.w(TAG, "source read: ${t.javaClass.simpleName}")
                null
            }
            val btBus = connect(VDServiceDef.ServiceType.BT)
            val path = MediaSourcePick.path(current, btBus != null, media != null)
            val sent = try {
                when (path) {
                    MediaSourcePick.Path.BT_PLAY ->
                        btBus?.set(VDBTMusicPlay.createEvent(VDEventBT.MUSIC_PLAY, btPlayPayload()))
                    MediaSourcePick.Path.OPEN_SOURCE ->
                        media?.set(VDMediaPlay.createEvent(VDEventMedia.MEDIA_SOURCE_PLAY_STATUS, switchPayload()))
                    else -> Unit
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "play: path=$path failed ${t.javaClass.simpleName}")
                false
            }
            val ms = SystemClock.elapsedRealtime() - t0
            val commanded = sent && (path == MediaSourcePick.Path.BT_PLAY || path == MediaSourcePick.Path.OPEN_SOURCE)
            if (commanded) {
                Log.i(TAG, "play: oemSource=$current path=$path sent ${ms}ms, session play in ${SETTLE_MS}ms")
                main.postDelayed(play, SETTLE_MS)
            } else {
                Log.i(TAG, "play: oemSource=$current path=$path ${ms}ms, session play")
                main.post(play)
            }
        }
    }

    /** io: [type] servisi bağlı değilse bağlanmayı başlatır, beklemez. */
    private fun startBind(type: VDServiceDef.ServiceType) {
        val b = bus ?: return
        try {
            if (!b.isServiceConnected(type)) b.bindService(type)
        } catch (t: Throwable) {
            Log.w(TAG, "bind $type: ${t.javaClass.simpleName}")
        }
    }

    /** io: [startBind] sonrası [type] servisinin bağlanmasını bekler (en çok [BIND_WAIT_MS]); bağlanamazsa null. */
    private fun connect(type: VDServiceDef.ServiceType): VDBus? {
        val b = bus ?: return null
        return try {
            // Bağlanma [startBind] ile başladı; burada yalnızca beklenir (bindService iki kez çağrılmaz).
            val until = SystemClock.elapsedRealtime() + BIND_WAIT_MS
            while (!b.isServiceConnected(type) && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(100)
            }
            if (b.isServiceConnected(type)) b else null
        } catch (t: Throwable) {
            Log.w(TAG, "bind $type: ${t.javaClass.simpleName}")
            null
        }
    }
}
