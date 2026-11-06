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
import com.desaysv.ivi.vdb.client.listener.VDBindListener
import com.desaysv.ivi.vdb.client.listener.VDNotifyListener
import java.util.concurrent.CopyOnWriteArraySet
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
     * svaudioapp'in kendi çaldığı OEM kaynak türleri (VDValueMedia.MediaType: LOCAL_MUSIC 2, USB0 3, USB1 4, FM_AM 8, FM 9,
     * AM 10, DAB 24). Hepsi OEM başlatıcı kartının izlediği listede (SVLauncher `d/g/b/g/f/k.java:31`) ve medya servisinde
     * denetçisi var (SVVDSMedia `svmediasourceinfo/b/c.java:142-200`; tanımsız türde `PlayStatusEventHandle` NPE verir).
     */
    private val OEM_OWN_SOURCES = setOf(2, 3, 4, 8, 9, 10, 24)

    fun isOemOwnSource(type: Int?) = type != null && type in OEM_OWN_SOURCES

    /** Oturum üçüncü taraf bir uygulamanınki mi (BT ya da OEM ses uygulaması değil). */
    private fun isThirdParty(pkg: String?) = pkg != null && !isBluetooth(pkg) && !isOemAudio(pkg)

    /**
     * Seçilecek oturumun sırası; liste boşsa -1. [current] seçili oturumun sırası (yoksa -1). [follow]: kartın izleyeceği OEM
     * kaynağı ([OemAudioSource.followSource]; yoksa null).
     * 1) Çalan ilk oturum. 2) Seçili oturum üçüncü taraf değilse OEM kaynağı izlenir: 13 → BT oturumu; svaudioapp kaynağı
     * (radyo/USB, yalnızca canlı geçişten sonra) → OEM ses oturumu. 3) Seçili oturum korunur (duraklatılan müzik atlamaz) —
     * seçili oturum OEM radyosuysa ve BT oturumu varsa korunmaz. 4) Başlığı olan ilk oturum; o OEM radyosuysa ve BT varsa BT.
     * 5) BT. 6) İlk oturum.
     */
    @JvmOverloads
    fun pick(cands: List<Cand>, current: Int, follow: Int? = null): Int {
        if (cands.isEmpty()) return -1
        cands.indexOfFirst { it.playing }.let { if (it >= 0) return it }
        val bt = cands.indexOfFirst { isBluetooth(it.pkg) }
        if (follow != null && !(current in cands.indices && isThirdParty(cands[current].pkg))) {
            if (follow == OemAudioSource.BT_MUSIC && bt >= 0) return bt
            if (isOemOwnSource(follow)) cands.indexOfFirst { isOemAudio(it.pkg) }.let { if (it >= 0) return it }
        }
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

    /** İleri/Geri'de izlenecek yol. */
    enum class SkipPath {
        /** `MediaController.skipToNext/Previous` (üçüncü taraf uygulama ya da VDBus yok). */
        SESSION,
        /** VDBus BT `MUSIC_PLAY{playCtrl=5|4, playType=0}`: OEM BT servisi odak 42'yi ister ve atlar (svaudioapp'in yolu). */
        BT_CTRL,
        /** VDBus MEDIA `MEDIA_SOURCE_PLAY_STATUS{mediaType=geçerli kaynak, playAction=5|4}`: OEM başlatıcı kartının yolu. */
        MEDIA_CTRL,
    }

    /**
     * [activePkg]: kartın oturumu (yoksa null). [oemSource]: `CURRENT_SOURCE_TYPE` (bilinmiyorsa null).
     * BT oturumu → BT servisi bağlıysa BT_CTRL (`com.android.bluetooth` oturumuna giden next/prev odak 42 yokken no-op,
     * `A2dpMediaBrowserService.java:100-114`). OEM ses oturumu ya da oturum yok, kaynak svaudioapp'in ya da 13 → MEDIA_CTRL.
     * Gerisi SESSION (üçüncü taraf uygulamanın kendi odağı var; OEM kaynağına dokunulmaz).
     */
    fun skipPath(activePkg: String?, oemSource: Int?, btBus: Boolean, mediaBus: Boolean): SkipPath = when {
        isBluetooth(activePkg) -> if (btBus) SkipPath.BT_CTRL else SkipPath.SESSION
        (activePkg == null || isOemAudio(activePkg)) && mediaBus &&
            (isOemOwnSource(oemSource) || oemSource == OemAudioSource.BT_MUSIC) -> SkipPath.MEDIA_CTRL
        else -> SkipPath.SESSION
    }

    /** Kartın izleyeceği OEM kaynağı: 13 her zaman; svaudioapp kaynağı yalnızca canlı geçişle geldiyse (bkz. [pick]). */
    fun follow(source: Int?, switchedLive: Boolean): Int? = when {
        source == OemAudioSource.BT_MUSIC -> source
        isOemOwnSource(source) && switchedLive -> source
        else -> null
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

    /**
     * İleri/Geri: VDBTMusicPlay playCtrl 5/4 (svaudioapp `BTMusicManager.java:596-602`; OEM BT servisi `VB/h/g.java:254-259`
     * 4 → skipToPrevious, 5 → skipToNext) ve VDMediaPlay playAction 5/4 (SVLauncher `AudioSourceManager.java:734-762`
     * playNext/playPre → `playPerOrNext(5|4)`; SVVDSMedia `PlayStatusEventHandle.java:59-64` 4 → önceki, 5 → sonraki).
     */
    const val CTRL_NEXT = 5
    const val CTRL_PREV = 4

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
    fun btPlayPayload(): VDBTMusicPlay = btCtrlPayload(BT_PLAY_CTRL_PLAY)

    /** svaudioapp `BTMusicManager.setPlayOperation(ctrl)` ile aynı yük (playType 0). */
    fun btCtrlPayload(ctrl: Int): VDBTMusicPlay = VDBTMusicPlay().apply {
        putPlayCtrl(ctrl)
        putPlayType(BT_PLAY_TYPE_DEFAULT)
    }

    /**
     * OEM başlatıcı kartının İleri/Geri yükü (`AudioSourceManager.java:334-349`); kart tıklaması bilgi eklemez
     * (`NormalMediaCard.java:358-378` playNext()/playPre() argümansız).
     */
    fun skipPayload(type: Int, next: Boolean): VDMediaPlay = VDMediaPlay().apply {
        putMediaType(type)
        putPlayAction(if (next) CTRL_NEXT else CTRL_PREV)
    }

    /**
     * Kullanıcı Oynat'a bastı ve bir Bluetooth oturumu var: OEM BT servisine her zaman çal komutu gider (odak 42 alınır,
     * kaynak 13 olsa da; bkz. [MediaSourcePick.Path]); [play] yedek olarak ana iş parçacığında biraz sonra çalışır
     * (zararsız: oturum zaten çalıyorsa onPlay bir şey yapmaz). VDBus yoksa yalnızca [play].
     */
    fun playBluetooth(context: Context, play: Runnable) {
        ensureBus(context)
        io.execute {
            val t0 = SystemClock.elapsedRealtime()
            // İki servisin bağlanması aynı anda başlar: bekleme en çok BIND_WAIT_MS (art arda ~3 sn değil).
            startBind(VDServiceDef.ServiceType.MEDIA)
            startBind(VDServiceDef.ServiceType.BT)
            val media = connect(VDServiceDef.ServiceType.MEDIA)
            val current = media?.let { readSource(it) }
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

    /**
     * Kart İleri/Geri ([next]); [activePkg] kartın oturumu. Yol [MediaSourcePick.skipPath]: BT oturumu → OEM BT servisine
     * `MUSIC_PLAY{5|4}`; OEM ses oturumu (radyo/USB) → OEM kartı gibi `MEDIA_SOURCE_PLAY_STATUS{kaynak, 5|4}`; gerisi
     * ya da komut gidemezse [session] (ana iş parçacığında). Yalnızca kullanıcı dokunuşuyla çağrılır.
     */
    fun skip(context: Context, next: Boolean, activePkg: String?, session: Runnable) {
        val bt = MediaSourcePick.isBluetooth(activePkg)
        if (!bt && activePkg != null && !MediaSourcePick.isOemAudio(activePkg)) {
            session.run() // üçüncü taraf uygulama: OEM kaynağına dokunulmaz, VDBus beklenmez
            return
        }
        if (ensureBus(context) == null) {
            session.run()
            return
        }
        io.execute {
            val t0 = SystemClock.elapsedRealtime()
            val type = if (bt) VDServiceDef.ServiceType.BT else VDServiceDef.ServiceType.MEDIA
            startBind(type)
            val b = connect(type)
            var source = currentSource
            if (!bt && b != null) source = readSource(b) ?: source
            val path = MediaSourcePick.skipPath(activePkg, source, bt && b != null, !bt && b != null)
            val ctrl = if (next) CTRL_NEXT else CTRL_PREV
            val sent = try {
                when (path) {
                    MediaSourcePick.SkipPath.BT_CTRL ->
                        b?.set(VDBTMusicPlay.createEvent(VDEventBT.MUSIC_PLAY, btCtrlPayload(ctrl)))
                    MediaSourcePick.SkipPath.MEDIA_CTRL ->
                        b?.set(VDMediaPlay.createEvent(VDEventMedia.MEDIA_SOURCE_PLAY_STATUS, skipPayload(source ?: 0, next)))
                    MediaSourcePick.SkipPath.SESSION -> Unit
                }
                path != MediaSourcePick.SkipPath.SESSION
            } catch (t: Throwable) {
                Log.w(TAG, "skip: path=$path failed ${t.javaClass.simpleName}")
                false
            }
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "skip: ctrl=$ctrl oemSource=$source path=$path sent=$sent ${ms}ms")
            if (!sent) main.post(session)
        }
    }

    /** Kart, OEM kaynağı değişince yeniden seçim yapsın diye (ana iş parçacığında çağrılır). */
    fun interface SourceListener {
        fun onOemSourceChanged(type: Int)
    }

    private val sourceListeners = CopyOnWriteArraySet<SourceListener>()

    /** Son bilinen `CURRENT_SOURCE_TYPE` (abonelik ya da okuma; bilinmiyorsa null). */
    @Volatile var currentSource: Int? = null
        private set

    /** [currentSource] bu süreçte gözlenen bir geçişle mi geldi (ilk okuma değil). */
    @Volatile private var switchedLive = false
    @Volatile private var watching = false

    /** Kartın izleyeceği kaynak ([MediaSourcePick.follow]). */
    fun followSource(): Int? = MediaSourcePick.follow(currentSource, switchedLive)

    private val notifyListener = VDNotifyListener { event, _ ->
        if (event == null || event.id != VDEventMedia.CURRENT_SOURCE_TYPE) return@VDNotifyListener
        val type = try {
            VDMediaType.getValue(event)?.mediaType
        } catch (t: Throwable) {
            Log.w(TAG, "source notify: ${t.javaClass.simpleName}")
            null
        }
        if (type != null) update(type, live = true)
    }

    private val bindListener = object : VDBindListener {
        override fun onVDConnected(serviceType: VDServiceDef.ServiceType?) {
            // Medya servisi (yeniden) bağlandı: abonelik VDConnector'da yenilenir, değer yeniden okunur.
            if (serviceType == VDServiceDef.ServiceType.MEDIA && watching) io.execute { bus?.let { readSource(it) } }
        }

        override fun onVDDisconnected(serviceType: VDServiceDef.ServiceType?) = Unit
    }

    /**
     * `CURRENT_SOURCE_TYPE` (393217) aboneliği, OEM başlatıcısı gibi (`AudioSourceManager.java:231-246`, bildirim
     * `:121-145`). Abonelik süreç boyunca tek; [listener] [unwatchSource] ile çıkarılır. Ana iş parçacığından çağrılır.
     */
    fun watchSource(context: Context, listener: SourceListener) {
        sourceListeners.add(listener)
        if (watching) return
        val b = ensureBus(context) ?: return
        watching = true
        io.execute {
            try {
                b.registerVDBindListener(bindListener)
                b.registerVDNotifyListener(notifyListener)
                b.addSubscribe(VDEventMedia.CURRENT_SOURCE_TYPE)
                b.subscribeCommit()
            } catch (t: Throwable) {
                Log.w(TAG, "subscribe: ${t.javaClass.simpleName}")
            }
            startBind(VDServiceDef.ServiceType.MEDIA)
            connect(VDServiceDef.ServiceType.MEDIA)?.let { readSource(it) }
        }
    }

    fun unwatchSource(listener: SourceListener) {
        sourceListeners.remove(listener)
    }

    /** io: `getOnce(CURRENT_SOURCE_TYPE)`; okunursa önbellek güncellenir. */
    private fun readSource(b: VDBus): Int? {
        val type = try {
            VDMediaType.getValue(b.getOnce(VDEventMedia.CURRENT_SOURCE_TYPE))?.mediaType
        } catch (t: Throwable) {
            Log.w(TAG, "source read: ${t.javaClass.simpleName}")
            null
        }
        if (type != null) update(type, live = false)
        return type
    }

    @Synchronized
    private fun update(type: Int, live: Boolean) {
        val old = currentSource
        if (old == type) return
        currentSource = type
        // İlk değer (old == null) geçiş sayılmaz: BT yeniden bağlanınca radyoda kalan kaynak kartı radyoya çekmesin (6 Kasım).
        // Sonraki her değişim (bildirim ya da aboneliğin kaçırdığını yakalayan okuma) geçiştir.
        switchedLive = old != null
        Log.i(TAG, "source: $old -> $type live=$live")
        main.post { sourceListeners.forEach { it.onOemSourceChanged(type) } }
    }

    /** VDBus init ana iş parçacığında (PhoneBackends/FavoriteKey gibi); bağlanma ve binder çağrıları arka planda. */
    private fun ensureBus(context: Context): VDBus? {
        bus?.let { return it }
        return try {
            VDBus.getDefault().also { it.init(context.applicationContext) }.also { bus = it }
        } catch (t: Throwable) {
            Log.w(TAG, "VDBus init: ${t.javaClass.simpleName}")
            null
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
