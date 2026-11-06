package com.mapcontrol.launcher

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import java.lang.ref.WeakReference
import java.util.Locale

/*
 * Telefon özelliğinin ortak sözleşmesi (UI ve arka uç paketleri buna göre derlenir).
 *
 * GİZLİLİK: Bu dosyadaki isim ve numaralar yalnızca ekranda gösterilir. Hiçbir zaman günlüğe (Log), dosyaya,
 * SharedPreferences'a (sabitlenmiş favori KİMLİKLERİ hariç), çökme metnine yazılmaz; diske önbelleğe alınmaz.
 * Bu yüzden veri sınıflarının toString'i isim/numara içermez.
 *
 * Arama, cevaplama, kapatma ve sesli asistan YALNIZCA kullanıcının açık bir eylemiyle (dokunuş ya da direksiyon tuşu)
 * çağrılır. Araç yapılandırmasına hiçbir yazma yoktur.
 */

/** Rehberden (PBAP) ya da arama geçmişinden türetilen bir kişi. [id] kaynağa göre kararlıdır (sabitleme bununla). */
data class PhoneContact(
    val id: String,
    val name: String,
    val number: String,
    val starred: Boolean = false,
    val initials: String = PhoneText.initials(name),
) {
    override fun toString(): String = "PhoneContact(starred=$starred)"
}

enum class RecentType { MISSED, INCOMING, OUTGOING }

/** Son arama. [name] rehberde yoksa null (UI numarayı gösterir). [timeMs] duvar saati (System.currentTimeMillis). */
data class PhoneRecent(
    val type: RecentType,
    val name: String?,
    val number: String,
    val timeMs: Long,
) {
    override fun toString(): String = "PhoneRecent(type=$type)"
}

enum class CallKind { NONE, INCOMING, DIALING, ACTIVE, ENDED }

/**
 * Şu anki arama. [startedAtMs] arama ACTIVE olduğu andaki SystemClock.elapsedRealtime() (sayaç bundan hesaplanır;
 * ACTIVE değilse 0). [muted]: mikrofon kapalı. ENDED kısa süre (arka uç ~2 sn) gösterilip NONE'a döner.
 */
data class CallState(
    val kind: CallKind = CallKind.NONE,
    val name: String? = null,
    val number: String? = null,
    val startedAtMs: Long = 0L,
    val muted: Boolean = false,
) {
    val inCall: Boolean get() = kind == CallKind.INCOMING || kind == CallKind.DIALING || kind == CallKind.ACTIVE
    override fun toString(): String = "CallState(kind=$kind, muted=$muted)"

    companion object {
        val IDLE = CallState()
    }
}

/** Seçilen arka uçların adları (günlüğe yalnızca bunlar yazılır). */
object PhoneBackendName {
    const val NONE = "none"
    const val DEMO = "demo"
    const val VDBUS = "vdbus"
    const val TELECOM = "telecom"
    const val HFP_CLIENT = "hfp_client"
    const val CONTACTS_PROVIDER = "contacts_provider"
}

/**
 * Ekranın tek veri kaynağı. Değişmez; her değişiklikte yeni örnek gelir (UI eşitlik karşılaştırmasıyla gereksiz
 * çizimi atlar).
 *
 * - [connected]: HFP ile bağlı bir telefon var.
 * - [deviceName]: bağlı telefonun adı (yoksa null).
 * - [backend]: arama/durum için seçilen arka uç ([PhoneBackendName]); veri arka ucu [dataBackend].
 * - [permissionNeeded]: rehber/geçmiş için izin eksik ve başka veri yolu da yok (UI "İzin ver" düğmesi gösterir).
 * - [loaded]: veri en az bir kez okundu (false iken UI "yükleniyor"/boş durum ayırt eder).
 * - [canDial]: arama başlatabilen bir yol var (yoksa UI telefon düğmesinde OEM uygulamasını açar).
 * - [canAssistant]: sesli asistan yolu var. [canMute]: mikrofon kapatma yolu var (yoksa "Sessiz" gizlenir).
 */
data class PhoneSnapshot(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val backend: String = PhoneBackendName.NONE,
    val dataBackend: String = PhoneBackendName.NONE,
    val permissionNeeded: Boolean = false,
    val loaded: Boolean = false,
    val canDial: Boolean = false,
    val canAssistant: Boolean = false,
    val canMute: Boolean = false,
    val favorites: List<PhoneContact> = emptyList(),
    val recents: List<PhoneRecent> = emptyList(),
    val call: CallState = CallState.IDLE,
) {
    override fun toString(): String =
        "PhoneSnapshot(connected=$connected, backend=$backend, data=$dataBackend, perm=$permissionNeeded, loaded=$loaded, " +
            "fav=${favorites.size}, recents=${recents.size}, call=$call)"

    companion object {
        val EMPTY = PhoneSnapshot()
    }
}

/** Bir eylemin neden yapılamadığı; UI kendi metnine çevirir (arka uç metin kaynağı eklemez). */
enum class PhoneError { NO_PHONE, NOT_SUPPORTED, FAILED, ASSISTANT_UNAVAILABLE, PERMISSION }

/** Dinleyici: her iki çağrı da ANA iş parçacığında gelir. */
interface PhoneListener {
    fun onPhoneChanged(snapshot: PhoneSnapshot)
    fun onPhoneError(error: PhoneError) {}
}

/**
 * Telefon durumu ve eylemleri. UI yalnızca bunu çağırır. Arka uçlar PhoneBackends.kt'de (VDBus BT, Telecom, HFP
 * istemci, rehber/geçmiş), seçim kuralı PhoneLogic.kt'de ([PhoneBackendChooser]), demo PhoneDemo.kt'de.
 *
 * Ömür: [start]/[stop] ekran görünürlüğüne bağlıdır (HomeLauncherActivity.onStart/onStop; telefon paneli sayfası da
 * açılınca start, kapanınca stop çağırabilir). Birden çok sahip olabilir; son sahip [stop] edince dinleme ve
 * okumalar durur (görünmezken sorgu yok). Direksiyon ses tuşu dinleyicisi ayrı ömürlüdür: [syncVoiceKey] /
 * [stopVoiceKey] (etkinlik ömrü, FavoriteKey gibi).
 *
 * Eylemler ana iş parçacığından çağrılır, hemen döner; Binder/VDBus işi arka plan kuyruğundadır. Dönüş değeri
 * "bir yol isteği kabul etti" demektir; sonradan başarısızlık [PhoneListener.onPhoneError] ile gelir.
 */
object PhoneSource {
    /** HomeLauncherActivity.onRequestPermissionsResult bu kodu [onPermissionsResult]'a iletir. */
    const val REQ_PERMISSIONS = 4711

    private const val TAG = "PhoneSource"
    private const val ENDED_MS = 2_000L
    private const val REREAD_DEBOUNCE_MS = 1_000L
    private const val SETTLE_MS = 300L
    private const val VDBUS_WAIT_MS = 3_000L
    private const val ASSISTANT_IDLE_CLOSE_MS = 10_000L
    private const val MAX_FAVORITES = 6

    private val io = LauncherIo.serial()
    private val main = Handler(Looper.getMainLooper())

    // Yalnızca ana iş parçacığında.
    private val owners = ArrayList<WeakReference<Any>>()
    private val listeners = ArrayList<PhoneListener>()
    private var receiverRegistered = false
    private var observerRegistered = false

    @Volatile private var snap = PhoneSnapshot.EMPTY
    @Volatile private var started = false
    @Volatile private var appContext: Context? = null
    @Volatile private var caps = PhoneBackendChooser.Caps()

    // Arka uçlar (ilk kullanımda kurulur; durumları yalnızca io kuyruğunda değişir).
    private val demo = DemoBackend()
    private var vdbus: VdbusCallBackend? = null
    private var telecom: TelecomCallBackend? = null
    private var hfp: HfpClientCallBackend? = null
    private var contactsData: ContactsDataBackend? = null
    private var vdbusData: VdbusDataBackend? = null
    private var hfpAssistant: HfpAssistant? = null

    // Yalnızca io kuyruğunda.
    private var stateSource: CallBackend? = null
    private var lastDataBackend: String? = null
    private var mutedByMic = false
    /** Bellekte: numara → isim (HFP durumunda isim yok; ekranda göstermek için). Diske yazılmaz. */
    private var nameByNumber: Map<String, String> = emptyMap()

    private val endedTask = Runnable {
        io.execute {
            if (snap.call.kind != CallKind.ENDED) return@execute
            update { it.copy(call = CallState.IDLE) }
            // Arama geçmişi değişti.
            if (started) readData()
        }
    }
    private val rereadTask = Runnable { io.execute { if (started) readData() } }
    private val reprobeTask = Runnable { io.execute { if (started) { reprobe(); readData() } } }
    private val vdbusCheck = Runnable {
        io.execute {
            if (!started) return@execute
            if (vdbus?.available() != true) Log.i(TAG, "vdbus BT ${VDBUS_WAIT_MS / 1000} sn içinde bağlanmadı")
            reprobe()
        }
    }
    private val idleClose = Runnable { if (!started) HfpLink.close() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            // Bağlantı yayını durum oturmadan gelebilir: kısa gecikmeyle yeniden yoklanır ve veri okunur.
            main.removeCallbacks(reprobeTask)
            main.postDelayed(reprobeTask, SETTLE_MS)
        }
    }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(rereadTask)
            main.postDelayed(rereadTask, REREAD_DEBOUNCE_MS)
        }
    }

    private fun demoActive(): Boolean = LauncherDemo.enabled && PhoneDemo.active

    /** Ana iş parçacığında: arka uç nesnelerini kurar (hafif; bağlanma yok). */
    private fun ensureBackends(app: Context) {
        if (vdbus != null) return
        val vd = VdbusCallBackend(io) { if (started) reprobe() }
        vdbus = vd
        telecom = TelecomCallBackend(app)
        hfp = HfpClientCallBackend(app, io)
        contactsData = ContactsDataBackend(app)
        vdbusData = VdbusDataBackend(vd)
        hfpAssistant = HfpAssistant(app, io)
    }

    private fun callBackendFor(name: String): CallBackend? = when (name) {
        PhoneBackendName.DEMO -> demo
        PhoneBackendName.VDBUS -> vdbus
        PhoneBackendName.TELECOM -> telecom
        PhoneBackendName.HFP_CLIENT -> hfp
        else -> null
    }

    private fun dataBackendFor(name: String): DataBackend? = when (name) {
        PhoneBackendName.DEMO -> demo
        PhoneBackendName.CONTACTS_PROVIDER -> contactsData
        PhoneBackendName.VDBUS -> vdbusData
        else -> null
    }

    /** Görünür sahip ekler; ilk sahipte arka uçlar seçilir ve okuma başlar. [owner] genelde Activity ya da panel. */
    fun start(context: Context, owner: Any = context) {
        val app = context.applicationContext
        appContext = app
        owners.removeAll { it.get() == null }
        if (owners.any { it.get() === owner }) return
        owners.add(WeakReference(owner))
        if (started) return
        started = true
        ensureBackends(app)
        main.removeCallbacks(idleClose)
        val vd = vdbus!!
        if (vd.bus == null) vd.prepare(app)
        registerReceiver(app)
        registerObservers(app)
        HfpLink.onProxyChanged = { io.execute { if (started) reprobe() } }
        HfpLink.open(app)
        io.execute {
            if (!started) return@execute
            vd.connect()
            reprobe()
            applyDemoCall()
        }
        main.postDelayed(vdbusCheck, VDBUS_WAIT_MS)
    }

    /** Sahibi çıkarır; sahip kalmazsa dinleme/okuma durur. */
    fun stop(owner: Any) {
        owners.removeAll { it.get() == null || it.get() === owner }
        if (owners.isNotEmpty() || !started) return
        started = false
        main.removeCallbacks(vdbusCheck)
        main.removeCallbacks(rereadTask)
        main.removeCallbacks(reprobeTask)
        main.removeCallbacks(endedTask)
        val app = appContext
        if (app != null) {
            unregisterReceiver(app)
            unregisterObservers(app)
        }
        HfpLink.onProxyChanged = null
        // Asistan az önce kullanıldıysa vekil biraz daha açık kalır.
        main.removeCallbacks(idleClose)
        main.postDelayed(idleClose, if (assistantRecently()) ASSISTANT_IDLE_CLOSE_MS else 0L)
        io.execute {
            if (started) return@execute
            stateSource?.close()
            stateSource = null
            vdbus?.disconnect()
            unmuteIfNeeded()
            caps = PhoneBackendChooser.Caps()
            lastDataBackend = null
            // Son veri bellekte kalır (diske yazılmaz); arama durumu sıfırlanır.
            update { it.copy(call = CallState.IDLE) }
            Log.i(TAG, "durdu")
        }
    }

    fun snapshot(): PhoneSnapshot = snap

    /** Eklenince güncel durum hemen (ana iş parçacığında) bir kez bildirilir. */
    fun addListener(listener: PhoneListener) {
        if (listener in listeners) return
        listeners.add(listener)
        main.post { if (listener in listeners) listener.onPhoneChanged(snap) }
    }

    fun removeListener(listener: PhoneListener) {
        listeners.remove(listener)
    }

    /** Numarayı arar. Bağlı telefon ya da yol yoksa false. */
    fun dial(number: String): Boolean {
        val n = number.filterNot { it.isWhitespace() }
        if (n.isEmpty()) return false
        return act(PhoneAction.DIAL) { it.dial(n) }
    }

    /** Gelen aramayı cevaplar. */
    fun answer(): Boolean = act(PhoneAction.ANSWER) { it.answer() }

    /** Gelen aramayı reddeder. */
    fun reject(): Boolean = act(PhoneAction.REJECT) { it.reject() }

    /** Aktif/çıkan aramayı kapatır. */
    fun hangUp(): Boolean = act(PhoneAction.HANG_UP) { it.hangUp() }

    /**
     * Ortak eylem yolu: bağlı telefon yoksa NO_PHONE, yol yoksa NOT_SUPPORTED (false). Varsa io'da sırayla denenir;
     * ilki başarısızsa bir sonraki bir kez; hepsi başarısızsa FAILED.
     */
    private fun act(action: PhoneAction, op: (CallBackend) -> Boolean): Boolean {
        if (!snap.connected) {
            fail(PhoneError.NO_PHONE)
            return false
        }
        val order = PhoneBackendChooser.fallbackOrder(caps, action)
        if (order.isEmpty()) {
            fail(PhoneError.NOT_SUPPORTED)
            return false
        }
        val label = action.name.lowercase()
        io.execute {
            for ((i, name) in order.withIndex()) {
                val ok = try {
                    callBackendFor(name)?.let(op) == true
                } catch (t: Throwable) {
                    Log.w(TAG, "$label: $name ${t.javaClass.simpleName}")
                    false
                }
                if (ok) {
                    Log.i(TAG, "$label: $name")
                    return@execute
                }
                val next = order.getOrNull(i + 1)
                if (next != null) Log.i(TAG, "$label: $name başarısız, $next deneniyor")
            }
            Log.i(TAG, "$label: başarısız")
            fail(PhoneError.FAILED)
        }
        return true
    }

    /** Mikrofonu kapatır/açar ([CallState.muted] değişir). */
    fun toggleMute(): Boolean {
        val s = snap
        if (!s.call.inCall) return false
        if (!s.canMute) {
            fail(PhoneError.NOT_SUPPORTED)
            return false
        }
        io.execute {
            val target = !snap.call.muted
            val be = callBackendFor(PhoneBackendChooser.callBackend(caps))
            val app = appContext
            var ok = be?.setMute(target) == true
            // VDBus mikrofon olayının yükü bilinmiyor: AudioManager'a düşülür.
            if (!ok && be != null && be.name != PhoneBackendName.DEMO && app != null) ok = MicMute.set(app, target)
            if (ok && be?.name != PhoneBackendName.DEMO) mutedByMic = target
            if (ok) update { it.copy(call = it.call.copy(muted = target)) } else fail(PhoneError.NOT_SUPPORTED)
            Log.i(TAG, "sessiz: ${if (ok) target else "başarısız"}")
        }
        return true
    }

    @Volatile private var assistantAt = 0L

    private fun assistantRecently(): Boolean =
        assistantAt != 0L && SystemClock.elapsedRealtime() - assistantAt < ASSISTANT_IDLE_CLOSE_MS

    /**
     * Bağlı telefonun sesli asistanını (Siri / Google, HFP BVRA) başlatır. Launcher görünmezken de çalışır (direksiyon
     * tuşu): HFP vekili gerekirse açılır, sahip yoksa kullanımdan 10 sn sonra kapanır.
     */
    fun startVoiceAssistant(): Boolean {
        val app = appContext
        if (app == null) {
            fail(PhoneError.ASSISTANT_UNAVAILABLE)
            return false
        }
        val backend: AssistantBackend
        if (demoActive()) {
            backend = demo
        } else {
            val connected = if (started) snap.connected else HfpLink.isConnected()
            if (!connected) {
                Log.i(TAG, "asistan: bağlı telefon yok")
                fail(PhoneError.NO_PHONE)
                return false
            }
            ensureBackends(app)
            backend = hfpAssistant!!
        }
        assistantAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(idleClose)
        io.execute {
            backend.start { ok ->
                Log.i(TAG, if (ok) "asistan başladı" else "asistan başlatılamadı")
                if (!ok) fail(PhoneError.ASSISTANT_UNAVAILABLE)
                if (!started) {
                    main.removeCallbacks(idleClose)
                    main.postDelayed(idleClose, ASSISTANT_IDLE_CLOSE_MS)
                }
            }
        }
        return true
    }

    /**
     * Rehber/geçmiş/arama izinlerini ister (READ_CONTACTS, READ_CALL_LOG, CALL_PHONE; Telecom hesabını okumak için
     * aynı gruptaki READ_PHONE_STATE). Araçta sistem penceresi yoksa `adb shell pm grant` ile verilir.
     */
    fun requestPermissions(activity: Activity) {
        try {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                ),
                REQ_PERMISSIONS,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "izin isteği: ${t.javaClass.simpleName}")
            fail(PhoneError.PERMISSION)
        }
    }

    /** İzin sonucu geldi: veri yeniden okunur. */
    fun onPermissionsResult(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!started) return
        unregisterObservers(app)
        registerObservers(app)
        io.execute {
            if (!started) return@execute
            reprobe()
            readData()
        }
    }

    /** Direksiyon ses tuşu (VR) uzun basış dinleyicisini ayara göre açar/kapatır ([PhonePrefs.assistantOnVoiceKey]). */
    fun syncVoiceKey(context: Context) {
        appContext = context.applicationContext
        PhoneKeys.sync(context)
    }

    /** Ana ekrana dönüşte ses tuşu aboneliğini tazeler (uyku sonrası düşmüş olabilir). */
    fun refreshVoiceKey() {
        PhoneKeys.refresh()
    }

    /** Etkinlik kapanırken: yalnızca dinleyiciyi son başlatan örnek kapatabilir. */
    fun stopVoiceKey(owner: Any) {
        PhoneKeys.stop(owner)
    }

    /** Demo telefon ekstraları değişti (LauncherDemo.applyPreview, ana iş parçacığı): durum yeniden üretilir. */
    internal fun demoChanged() {
        io.execute {
            if (!started) return@execute
            reprobe()
            applyDemoCall()
            readData()
        }
    }

    // ---- io kuyruğu ----

    /** Yetenekleri yoklar, arka uçları seçer, anlık görüntünün yetenek alanlarını günceller. */
    private fun reprobe() {
        val app = appContext ?: return
        val vdOk = vdbus?.available() == true
        val c = PhoneBackendChooser.Caps(
            demo = demoActive(),
            vdbusBt = vdOk,
            telecomHfpAccount = telecom?.available() == true,
            hfpClientProxy = HfpLink.proxy != null,
            contactsPermission = contactsData?.available() == true,
            vdbusData = vdOk,
        )
        val call = PhoneBackendChooser.callBackend(c)
        val data = PhoneBackendChooser.dataBackend(c)
        val asst = PhoneBackendChooser.assistantBackend(c)
        if (c != caps) Log.i(TAG, "arka uç: arama=$call veri=$data asistan=$asst")
        caps = c

        val wantState = PhoneBackendChooser.stateBackend(c)
        if (stateSource?.name != wantState) {
            val leavingDemo = stateSource?.name == PhoneBackendName.DEMO
            stateSource?.close()
            stateSource = callBackendFor(wantState)
            if (leavingDemo || stateSource == null) update { it.copy(call = CallState.IDLE) }
            // Bağlantı PhoneSource'un kendi yayın alıcısından izlenir.
            stateSource?.open(::onRawState) { _, _ -> }
        }

        val connected = c.demo || HfpLink.isConnected()
        val deviceName = when {
            c.demo -> PhoneDemo.DEVICE_NAME
            connected -> HfpLink.deviceName()
            else -> null
        }
        val canMute = c.demo ||
            ((call == PhoneBackendName.VDBUS || call == PhoneBackendName.HFP_CLIENT) && MicMute.available(app))
        update {
            it.copy(
                connected = connected,
                deviceName = deviceName,
                backend = call,
                dataBackend = data,
                permissionNeeded = PhoneBackendChooser.permissionNeeded(c),
                canDial = call != PhoneBackendName.NONE,
                canAssistant = asst != PhoneBackendName.NONE,
                canMute = canMute,
            )
        }
        if (data != lastDataBackend) readData()
    }

    /** Veri arka ucundan bir kez okur; favorileri sıralar, son aramaları biçimler. */
    private fun readData() {
        val app = appContext ?: return
        val name = PhoneBackendChooser.dataBackend(caps)
        lastDataBackend = name
        val backend = dataBackendFor(name)
        if (backend == null) {
            nameByNumber = emptyMap()
            update { it.copy(loaded = false, favorites = emptyList(), recents = emptyList()) }
            return
        }
        val pinned = PhonePrefs.pinnedIds(app)
        val data = try {
            backend.read(pinned)
        } catch (t: Throwable) {
            Log.w(TAG, "veri ($name): ${t.javaClass.simpleName}")
            null
        }
        if (data == null) {
            update { it.copy(loaded = true) }
            return
        }
        val names = HashMap<String, String>()
        for (c in data.contacts) names.putIfAbsent(FavoritesRanker.normalizeNumber(c.number), c.name)
        for (r in data.recents) r.name?.let { names.putIfAbsent(FavoritesRanker.normalizeNumber(r.number), it) }
        nameByNumber = names
        val favorites = FavoritesRanker.rank(data.contacts, data.recents, pinned, MAX_FAVORITES, System.currentTimeMillis())
        val recents = RecentsShaper.shape(data.recents, 5)
        Log.d(TAG, "veri: $name kişi=${data.contacts.size} geçmiş=${data.recents.size} favori=${favorites.size}")
        update { it.copy(loaded = true, favorites = favorites, recents = recents) }
    }

    /** Arka uçtan ham durum (herhangi bir iş parçacığı) → io. */
    private fun onRawState(kind: CallKind, name: String?, number: String?) {
        io.execute { applyState(kind, name, number) }
    }

    private fun applyState(raw: CallKind, name: String?, number: String?) {
        if (!started) return
        val prev = snap.call
        val kind = PhoneStateMap.effectiveKind(prev.kind, raw)
        val shownName = name ?: number?.let { nameByNumber[FavoritesRanker.normalizeNumber(it)] }
        val next = PhoneStateMap.nextCall(prev, kind, shownName, number, SystemClock.elapsedRealtime())
        if (!next.inCall) unmuteIfNeeded()
        if (next.kind != prev.kind) Log.i(TAG, "arama: ${prev.kind} → ${next.kind}")
        update { it.copy(call = next) }
        if (kind == CallKind.ENDED && prev.kind != CallKind.ENDED) {
            main.removeCallbacks(endedTask)
            main.postDelayed(endedTask, ENDED_MS)
        }
    }

    /** Demo isteğindeki arama durumunu uygular (gelen / 83 sn'dir süren / yok). */
    private fun applyDemoCall() {
        if (!caps.demo) return
        val who = PhoneDemo.caller
        when (PhoneDemo.call) {
            CallKind.INCOMING -> update { it.copy(call = CallState(CallKind.INCOMING, who.name, who.number)) }
            CallKind.ACTIVE -> update {
                it.copy(call = CallState(CallKind.ACTIVE, who.name, who.number, SystemClock.elapsedRealtime() - 83_000L))
            }
            else -> if (snap.call.inCall) update { it.copy(call = CallState.IDLE) }
        }
    }

    private fun unmuteIfNeeded() {
        if (!mutedByMic) return
        mutedByMic = false
        appContext?.let { MicMute.set(it, false) }
    }

    /** Yeni anlık görüntü eskisinden farklıysa yayınlar (ana iş parçacığında dinleyicilere). */
    private fun update(f: (PhoneSnapshot) -> PhoneSnapshot) {
        val old = snap
        val new = f(old)
        if (new == old) return
        snap = new
        main.post { for (l in ArrayList(listeners)) l.onPhoneChanged(new) }
    }

    private fun fail(e: PhoneError) {
        main.post { for (l in ArrayList(listeners)) l.onPhoneError(e) }
    }

    // ---- ana iş parçacığı: yayın alıcısı ve içerik gözlemcileri (yalnızca başlatılmışken) ----

    private fun registerReceiver(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = try {
            app.registerReceiver(receiver, IntentFilter().apply {
                addAction(HfpLink.ACTION_CONNECTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            })
            true
        } catch (t: Throwable) {
            Log.w(TAG, "alıcı: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun unregisterReceiver(app: Context) {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun registerObservers(app: Context) {
        if (observerRegistered) return
        val cr = app.contentResolver
        var any = false
        if (app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            any = runCatching { cr.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }.isSuccess || any
        }
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            any = runCatching { cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }.isSuccess || any
        }
        observerRegistered = any
    }

    private fun unregisterObservers(app: Context) {
        main.removeCallbacks(rereadTask)
        if (!observerRegistered) return
        observerRegistered = false
        runCatching { app.contentResolver.unregisterContentObserver(observer) }
    }
}

/** Telefon tercihleri (profil başına, launcher_settings içinde). Yalnız açık/kapalı ve kişi KİMLİKLERİ yazılır. */
object PhonePrefs {
    private const val PREFS_NAME = "launcher_settings"
    private const val KEY_ASSISTANT = "phone_voice_key_assistant"
    private const val KEY_PINNED = "phone_pinned_ids"

    /** "Ses tuşu uzun basış: Telefon asistanı" (varsayılan Açık). */
    fun assistantOnVoiceKey(context: Context): Boolean =
        prefs(context).getBoolean(Profiles.key(context, KEY_ASSISTANT), true)

    fun setAssistantOnVoiceKey(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(Profiles.key(context, KEY_ASSISTANT), on).apply()
    }

    /** Sabitlenmiş favori kişi kimlikleri, sırayla. */
    fun pinnedIds(context: Context): List<String> =
        prefs(context).getString(Profiles.key(context, KEY_PINNED), null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    fun setPinnedIds(context: Context, ids: List<String>) {
        val clean = ids.map { it.replace(",", "").trim() }.filter { it.isNotEmpty() }.distinct()
        prefs(context).edit().putString(Profiles.key(context, KEY_PINNED), clean.joinToString(",")).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Saf metin yardımcıları (JVM testli). */
object PhoneText {
    private val TR = Locale("tr", "TR")

    /**
     * Avatar baş harfleri: ilk iki kelimenin ilk harfi, Türkçe büyük harf ("ayşe yılmaz" -> "AY"). Harf yoksa
     * (numara ya da boş) "#".
     */
    fun initials(name: String?): String {
        val words = name.orEmpty().trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
        if (words.isEmpty()) return "#"
        return words.take(2).joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase(TR) }
    }
}
