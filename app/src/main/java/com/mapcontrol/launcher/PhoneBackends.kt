package com.mapcontrol.launcher

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.desaysv.ivi.vdb.IVDBusNotify
import com.desaysv.ivi.vdb.client.VDBus
import com.desaysv.ivi.vdb.client.bind.VDServiceDef
import com.desaysv.ivi.vdb.client.listener.VDBindListener
import com.desaysv.ivi.vdb.event.VDEvent
import com.desaysv.ivi.vdb.event.id.bt.VDEventBT
import com.desaysv.ivi.vdb.event.id.bt.VDValueBT
import com.desaysv.ivi.vdb.event.id.bt.bean.VDBTContacts
import com.desaysv.ivi.vdb.event.id.bt.bean.VDBTDial
import com.desaysv.ivi.vdb.event.id.bt.bean.VDBTDialInfo
import com.desaysv.ivi.vdb.event.id.bt.bean.VDBTRecord
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.Executor

/*
 * Telefon arka uçları. Bütün yöntemler PhoneSource'un io kuyruğunda çağrılır (ana iş parçacığında asla); binder ya da
 * yayın iş parçacığından gelen geri çağrılar önce io'ya, oradan ana iş parçacığına aktarılır.
 *
 * GİZLİLİK: günlüğe yalnızca arka uç adı, durum, sayı ve istisna SINIFI yazılır; isim/numara/adres asla. OEM
 * bean'lerinin toString'i kullanılmaz.
 */

/** Okunan rehber alt kümesi ve arama geçmişi (yalnızca bellekte). */
internal data class PhoneData(val contacts: List<PhoneContact>, val recents: List<PhoneRecent>) {
    override fun toString(): String = "PhoneData(contacts=${contacts.size}, recents=${recents.size})"
}

internal interface CallBackend {
    val name: String
    fun available(): Boolean
    fun dial(number: String): Boolean
    fun answer(): Boolean
    fun reject(): Boolean
    fun hangUp(): Boolean
    /** false = desteklenmiyor. */
    fun setMute(on: Boolean): Boolean
    /** Arama durumu dinlemesini başlatır. Geri çağrılar herhangi bir iş parçacığında gelebilir. */
    fun open(onState: (CallKind, String?, String?) -> Unit, onConnected: (Boolean, String?) -> Unit)
    fun close()
}

internal interface DataBackend {
    val name: String
    fun available(): Boolean
    /** [pinnedIds] sabitlenmiş kişileri bulmak için (yalnızca onlar, yıldızlılar ve geçmişteki numaralar tutulur). */
    fun read(pinnedIds: List<String>): PhoneData?
}

internal interface AssistantBackend {
    fun available(): Boolean
    /** Eşzamansız: sonuç [done] ile (io kuyruğunda) gelir. */
    fun start(done: (Boolean) -> Unit)
}

private const val TAG = "PhoneSource"

private fun granted(ctx: Context, perm: String): Boolean =
    ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

/**
 * Gizli API muafiyeti (BluetoothHeadsetClient yansıması için). Yalnızca HFP istemci yolu ilk gerektiğinde bir kez
 * çağrılır; sistem ayarına dokunmaz (yalnızca bu süreç). Sonuç günlüğe başarı/başarısızlık olarak yazılır.
 */
internal object HiddenApi {
    @Volatile private var result: Boolean? = null

    @Synchronized
    fun exempt(): Boolean {
        result?.let { return it }
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("L") else true
        } catch (t: Throwable) {
            Log.w(TAG, "gizli API muafiyeti: ${t.javaClass.simpleName}")
            false
        }
        Log.i(TAG, "gizli API muafiyeti: ${if (ok) "tamam" else "başarısız"}")
        result = ok
        return ok
    }
}

/** Mikrofon kapatma (AudioManager). Araç testinde SCO mikrofonunu gerçekten kapattığı doğrulanmalı. */
internal object MicMute {
    fun available(ctx: Context): Boolean = ctx.getSystemService(Context.AUDIO_SERVICE) is AudioManager

    fun set(ctx: Context, on: Boolean): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) false else {
            am.isMicrophoneMute = on
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "mikrofon: ${t.javaClass.simpleName}")
        false
    }
}

/**
 * HFP istemci (profil 16, HEADSET_CLIENT) vekili: bağlı telefon adı, asistan ve HFP arama yolu bunu paylaşır.
 * Vekil geri çağrıları ana iş parçacığında gelir.
 */
internal object HfpLink {
    const val PROFILE_HEADSET_CLIENT = 16
    const val ACTION_CONNECTION = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    const val ACTION_CALL = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
    const val EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL"

    private val main = Handler(Looper.getMainLooper())

    @Volatile var proxy: BluetoothProfile? = null
        private set

    @Volatile private var requested = false

    /** Vekil bağlanınca/kopunca (ana iş parçacığında). */
    @Volatile var onProxyChanged: (() -> Unit)? = null

    private class Waiter(val executor: Executor, val cb: (BluetoothProfile?) -> Unit) {
        var timeout: Runnable? = null
    }

    private val waiters = ArrayList<Waiter>()

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            if (profile != PROFILE_HEADSET_CLIENT) return
            proxy = p
            Log.i(TAG, "HFP istemci vekili bağlandı")
            flush(p)
            onProxyChanged?.invoke()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != PROFILE_HEADSET_CLIENT) return
            proxy = null
            requested = false
            Log.i(TAG, "HFP istemci vekili koptu")
            onProxyChanged?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (t: Throwable) {
        null
    }

    /** Vekili ister (zaten açıksa/isteniyorsa bir şey yapmaz). Bağdaştırıcı yoksa false. */
    fun open(context: Context): Boolean {
        if (proxy != null || requested) return true
        val a = adapter() ?: return false
        requested = try {
            a.getProfileProxy(context.applicationContext, listener, PROFILE_HEADSET_CLIENT)
        } catch (t: Throwable) {
            Log.w(TAG, "HFP istemci vekili: ${t.javaClass.simpleName}")
            false
        }
        return requested
    }

    fun close() {
        val p = proxy
        proxy = null
        requested = false
        if (p != null) runCatching { adapter()?.closeProfileProxy(PROFILE_HEADSET_CLIENT, p) }
        flush(null)
    }

    /** Vekil hazırsa hemen, değilse en çok [timeoutMs] bekleyip (bloklamadan) [cb]'yi [executor]'da çağırır. */
    fun withProxy(context: Context, timeoutMs: Long, executor: Executor, cb: (BluetoothProfile?) -> Unit) {
        proxy?.let { p -> executor.execute { cb(p) }; return }
        if (!open(context)) {
            executor.execute { cb(null) }
            return
        }
        val w = Waiter(executor, cb)
        val t = Runnable {
            val removed = synchronized(waiters) { waiters.remove(w) }
            if (removed) executor.execute { cb(proxy) }
        }
        w.timeout = t
        synchronized(waiters) { waiters.add(w) }
        main.postDelayed(t, timeoutMs)
        // Bu arada bağlanmış olabilir.
        proxy?.let { flush(it) }
    }

    private fun flush(p: BluetoothProfile?) {
        val list = synchronized(waiters) { ArrayList(waiters).also { waiters.clear() } }
        for (w in list) {
            w.timeout?.let { main.removeCallbacks(it) }
            w.executor.execute { w.cb(p) }
        }
    }

    /** Bağlı ilk telefon (vekil yoksa null). */
    fun connectedDevice(): BluetoothDevice? = try {
        proxy?.connectedDevices?.firstOrNull()
    } catch (t: Throwable) {
        null
    }

    /** HFP istemci bağlantısı (vekil gerekmez). */
    fun isConnected(): Boolean = try {
        adapter()?.getProfileConnectionState(PROFILE_HEADSET_CLIENT) == BluetoothProfile.STATE_CONNECTED
    } catch (t: Throwable) {
        false
    }

    /** Bağlı telefonun adı: yalnızca ekranda gösterilir, günlüğe yazılmaz. */
    fun deviceName(): String? = try {
        connectedDevice()?.name?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}

/** android.bluetooth.BluetoothHeadsetClient gizli yöntemleri (yansıma). Her çağrı Throwable yakalar. */
internal object HfpClientApi {
    private val callClass: Class<*>? by lazy {
        runCatching { Class.forName("android.bluetooth.BluetoothHeadsetClientCall") }.getOrNull()
    }
    private val methods = HashMap<String, Method?>()

    private fun method(target: Any, name: String, vararg types: Class<*>): Method? = synchronized(methods) {
        val key = target.javaClass.name + "#" + name + "/" + types.size
        if (methods.containsKey(key)) return methods[key]
        val m = runCatching { target.javaClass.getMethod(name, *types) }.getOrNull()
        methods[key] = m
        m
    }

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Result<Any?> = try {
        HiddenApi.exempt()
        val m = method(target, name, *types) ?: throw NoSuchMethodException(name)
        Result.success(m.invoke(target, *args))
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        Log.w(TAG, "hfp $name: ${cause.javaClass.simpleName}")
        Result.failure(cause)
    }

    private val DEV = BluetoothDevice::class.java

    fun dial(proxy: Any, device: BluetoothDevice, number: String): Boolean =
        invoke(proxy, "dial", arrayOf(DEV, String::class.java), device, number).getOrNull() != null

    fun accept(proxy: Any, device: BluetoothDevice): Boolean =
        invoke(proxy, "acceptCall", arrayOf(DEV, Int::class.javaPrimitiveType!!), device, 0).getOrNull() == true

    fun reject(proxy: Any, device: BluetoothDevice): Boolean =
        invoke(proxy, "rejectCall", arrayOf(DEV), device).getOrNull() == true

    fun terminate(proxy: Any, device: BluetoothDevice): Boolean {
        val cc = callClass ?: return false
        return invoke(proxy, "terminateCall", arrayOf(DEV, cc), device, null).getOrNull() == true
    }

    fun startVoiceRecognition(proxy: Any, device: BluetoothDevice): Boolean =
        invoke(proxy, "startVoiceRecognition", arrayOf(DEV), device).getOrNull() == true

    /** Bir BluetoothHeadsetClientCall'ın kimliği, durumu, numarası, giden mi. */
    data class CallInfo(val id: Int, val state: Int, val number: String?, val outgoing: Boolean) {
        override fun toString(): String = "CallInfo(id=$id, state=$state, outgoing=$outgoing)"
    }

    fun callInfo(call: Any): CallInfo? {
        val id = invoke(call, "getId", emptyArray()).getOrNull() as? Int ?: return null
        val state = invoke(call, "getState", emptyArray()).getOrNull() as? Int ?: return null
        val number = invoke(call, "getNumber", emptyArray()).getOrNull() as? String
        val outgoing = invoke(call, "isOutgoing", emptyArray()).getOrNull() as? Boolean ?: false
        return CallInfo(id, state, number, outgoing)
    }
}

/**
 * HFP istemci arama yolu (gizli BluetoothHeadsetClient). Durum: AG_CALL_CHANGED yayını (her arama ayrı gelir; kimliğe
 * göre birleştirilir). Sessiz: AudioManager mikrofonu.
 */
internal class HfpClientCallBackend(private val ctx: Context, private val io: Executor) : CallBackend {
    override val name: String = PhoneBackendName.HFP_CLIENT
    private var onState: ((CallKind, String?, String?) -> Unit)? = null
    private var registered = false
    /** io kuyruğunda: kimlik → arama. */
    private val calls = LinkedHashMap<Int, HfpClientApi.CallInfo>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != HfpLink.ACTION_CALL) return
            val call: Parcelable? = try {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(HfpLink.EXTRA_CALL)
            } catch (t: Throwable) {
                Log.w(TAG, "hfp çağrı yayını: ${t.javaClass.simpleName}")
                null
            }
            if (call != null) io.execute { onCall(call) }
        }
    }

    override fun available(): Boolean = HfpLink.proxy != null

    private inline fun withDevice(block: (Any, BluetoothDevice) -> Boolean): Boolean {
        val p = HfpLink.proxy ?: return false
        val d = HfpLink.connectedDevice() ?: return false
        return block(p, d)
    }

    override fun dial(number: String): Boolean = withDevice { p, d -> HfpClientApi.dial(p, d, number) }
    override fun answer(): Boolean = withDevice { p, d -> HfpClientApi.accept(p, d) }
    override fun reject(): Boolean = withDevice { p, d -> HfpClientApi.reject(p, d) }
    override fun hangUp(): Boolean = withDevice { p, d -> HfpClientApi.terminate(p, d) }

    override fun setMute(on: Boolean): Boolean = MicMute.set(ctx, on)

    override fun open(onState: (CallKind, String?, String?) -> Unit, onConnected: (Boolean, String?) -> Unit) {
        this.onState = onState
        if (registered) return
        registered = try {
            ctx.registerReceiver(receiver, IntentFilter(HfpLink.ACTION_CALL))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "hfp alıcı: ${t.javaClass.simpleName}")
            false
        }
    }

    override fun close() {
        onState = null
        calls.clear()
        if (registered) runCatching { ctx.unregisterReceiver(receiver) }
        registered = false
    }

    private fun onCall(call: Any) {
        val info = HfpClientApi.callInfo(call) ?: return
        calls[info.id] = info
        val list = calls.values.toList()
        val lite = list.map { DialInfoLite(it.state, it.outgoing) }
        val kind = PhoneStateMap.fromVdDialInfos(lite)
        val src = list.getOrNull(PhoneStateMap.relevantIndex(lite))
        // Biten aramalar listeden çıkar (durum bir kez bildirildikten sonra).
        calls.entries.removeAll { it.value.state == PhoneStateMap.TERMINATED }
        onState?.invoke(kind, null, src?.number?.takeIf { it.isNotBlank() })
    }
}

/**
 * Telecom yolu: yalnızca arama başlatır (HfpClientConnectionService hesabı varsa). Cevap/ret/kapat desteklenmez;
 * durum dinlemez (VDBus ya da HFP yayını verir).
 */
internal class TelecomCallBackend(private val ctx: Context) : CallBackend {
    override val name: String = PhoneBackendName.TELECOM

    private fun handle(): PhoneAccountHandle? {
        if (!granted(ctx, Manifest.permission.CALL_PHONE)) return null
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
            tm.callCapablePhoneAccounts?.firstOrNull { it.componentName?.packageName == "com.android.bluetooth" }
        } catch (t: Throwable) {
            // READ_PHONE_STATE yoksa SecurityException.
            null
        }
    }

    override fun available(): Boolean = handle() != null

    override fun dial(number: String): Boolean {
        val h = handle() ?: return false
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(Uri.fromParts("tel", number, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
            })
            true
        } catch (t: Throwable) {
            Log.w(TAG, "telecom placeCall: ${t.javaClass.simpleName}")
            false
        }
    }

    override fun answer(): Boolean = false
    override fun reject(): Boolean = false
    /** TelecomManager.endCall ANSWER_PHONE_CALLS ister; istenmiyor. */
    override fun hangUp(): Boolean = false
    override fun setMute(on: Boolean): Boolean = false
    override fun open(onState: (CallKind, String?, String?) -> Unit, onConnected: (Boolean, String?) -> Unit) {}
    override fun close() {}
}

/**
 * Fabrika VDBus "BT" modülü (com.desaysv.ivi.vds.bt): arama/cevap/kapat (DIAL) ve durum (DIAL_INFO_LIST). VDBus
 * uygulamanın başka parçalarıyla paylaşılır: kapanışta unbindService ÇAĞRILMAZ.
 */
internal class VdbusCallBackend(private val io: Executor, private val onAvailability: () -> Unit) : CallBackend {
    override val name: String = PhoneBackendName.VDBUS
    @Volatile var bus: VDBus? = null
        private set
    private var bindRegistered = false
    private var event: VDEvent? = null
    @Volatile private var onState: ((CallKind, String?, String?) -> Unit)? = null

    private val notify = object : IVDBusNotify.Stub() {
        override fun onVDBusNotify(e: VDEvent?) {
            if (e == null || e.id != VDEventBT.DIAL_INFO_LIST) return
            handle(e)
        }
    }

    private val bindListener = object : VDBindListener {
        override fun onVDConnected(serviceType: VDServiceDef.ServiceType) {
            if (serviceType != VDServiceDef.ServiceType.BT) return
            io.execute {
                if (onState != null) subscribe()
                onAvailability()
            }
        }

        override fun onVDDisconnected(serviceType: VDServiceDef.ServiceType) {
            if (serviceType != VDServiceDef.ServiceType.BT) return
            io.execute {
                event = null
                onAvailability()
            }
        }
    }

    /** Ana iş parçacığında (FavoriteKey gibi): VDBus init. */
    fun prepare(context: Context) {
        bus = try {
            VDBus.getDefault().also { it.init(context.applicationContext) }
        } catch (t: Throwable) {
            Log.w(TAG, "VDBus başlatılamadı: ${t.javaClass.simpleName}")
            null
        }
    }

    /** io: BT servisine bağlanır (bağlıysa bir şey yapmaz). */
    fun connect() {
        val b = bus ?: return
        if (!bindRegistered) {
            runCatching { b.registerVDBindListener(bindListener) }
            bindRegistered = true
        }
        if (!available()) runCatching { b.bindService(VDServiceDef.ServiceType.BT) }
            .onFailure { Log.w(TAG, "vdbus BT bind: ${it.javaClass.simpleName}") }
    }

    /** io: bağlanma dinleyicisini bırakır (servis bağı paylaşıldığı için açık kalır). */
    fun disconnect() {
        close()
        if (bindRegistered) runCatching { bus?.unregisterVDBindListener(bindListener) }
        bindRegistered = false
    }

    override fun available(): Boolean = try {
        bus?.isServiceConnected(VDServiceDef.ServiceType.BT) == true
    } catch (t: Throwable) {
        false
    }

    private fun send(ctrl: Int, number: String?): Boolean {
        val b = bus ?: return false
        if (!available()) return false
        return try {
            // Adres (MAC) boş bırakılır: araç testinde boş adresle çalıştığı doğrulanmalı (gerekirse HFP cihaz adresi).
            b.set(VDBTDial.createEvent(VDEventBT.DIAL, VDBTDial().apply {
                if (number != null) putNumber(number)
                putDialCtrl(ctrl)
            }))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "vdbus DIAL($ctrl): ${t.javaClass.simpleName}")
            false
        }
    }

    override fun dial(number: String): Boolean = send(VDValueBT.DialCtrl.DIAL, number)
    override fun answer(): Boolean = send(VDValueBT.DialCtrl.ACCEPT, null)
    override fun reject(): Boolean = send(VDValueBT.DialCtrl.HUNG_UP, null)
    override fun hangUp(): Boolean = send(VDValueBT.DialCtrl.HUNG_UP, null)

    /**
     * DIAL_MIC_MODE (262547) yük biçimi bilinmiyor (VDValueBT.MicMode MUTE 2 / UNMUTE 1 var, anahtar yok) →
     * desteklenmiyor; PhoneSource AudioManager'a düşer. TODO araç testi.
     */
    override fun setMute(on: Boolean): Boolean = false

    override fun open(onState: (CallKind, String?, String?) -> Unit, onConnected: (Boolean, String?) -> Unit) {
        this.onState = onState
        subscribe()
        // Şu anki durum (abonelik yalnızca değişiklikleri getirir).
        val b = bus ?: return
        if (!available()) return
        val now = try {
            b.getOnce(VDEventBT.DIAL_INFO_LIST)
        } catch (t: Throwable) {
            Log.w(TAG, "vdbus DIAL_INFO_LIST: ${t.javaClass.simpleName}")
            null
        }
        if (now != null) handle(now)
    }

    override fun close() {
        onState = null
        val e = event
        if (e != null) runCatching { bus?.unsubscribe(e, notify) }
        event = null
    }

    private fun subscribe() {
        val b = bus ?: return
        if (event != null || !available()) return
        try {
            val e = VDEvent(VDEventBT.DIAL_INFO_LIST, Bundle())
            b.subscribe(e, notify)
            event = e
        } catch (t: Throwable) {
            Log.w(TAG, "vdbus subscribe: ${t.javaClass.simpleName}")
        }
    }

    /** Binder ya da io iş parçacığında: listeyi çözüp durumu io'ya aktarır. */
    private fun handle(e: VDEvent) {
        val list: List<VDBTDialInfo> = try {
            VDBTDialInfo.getList(e)?.filterNotNull() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "vdbus DialInfo: ${t.javaClass.simpleName}")
            return
        }
        val lite = list.map { DialInfoLite(it.state, it.isOutGoing) }
        val kind = PhoneStateMap.fromVdDialInfos(lite)
        val src = list.getOrNull(PhoneStateMap.relevantIndex(lite))
        val name = src?.name?.takeIf { it.isNotBlank() }
        val number = src?.number?.takeIf { it.isNotBlank() }
        io.execute { onState?.invoke(kind, name, number) }
    }
}

/** VDBus rehber ve geçmiş (yıldız bilgisi yok: favoriler yalnızca sıklıktan). */
internal class VdbusDataBackend(private val call: VdbusCallBackend) : DataBackend {
    override val name: String = PhoneBackendName.VDBUS

    override fun available(): Boolean = call.available()

    private fun once(id: Int, cacheId: Int, parse: (VDEvent) -> List<*>?): List<*>? {
        val b = call.bus ?: return null
        for (eventId in intArrayOf(id, cacheId)) {
            val list = try {
                b.getOnce(VDEvent(eventId, Bundle()))?.let(parse)
            } catch (e: RuntimeException) {
                Log.w(TAG, "vdbus getOnce($eventId): ${e.javaClass.simpleName}")
                null
            }
            if (list != null) return list
        }
        return null
    }

    override fun read(pinnedIds: List<String>): PhoneData? {
        if (!available()) return null
        return try {
            val records = once(VDEventBT.PHONE_BOOK_RECORD_LIST, VDEventBT.PHONE_BOOK_RECORD_LIST_CACHE) { VDBTRecord.getList(it) }
                ?.filterIsInstance<VDBTRecord>().orEmpty()
            val recents = records.mapNotNull { r ->
                val type = PhoneStateMap.recentTypeFromVd(r.recordType) ?: return@mapNotNull null
                val number = r.number?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PhoneRecent(type, r.name?.takeIf { it.isNotBlank() }, number, PhoneStateMap.parseVdDateTime(r.dateTime) ?: 0L)
            }
            val wanted = recents.map { FavoritesRanker.normalizeNumber(it.number) }.toHashSet()
            val pinned = pinnedIds.toHashSet()
            val raw = once(VDEventBT.PHONE_BOOK_CONTACTS_LIST, VDEventBT.PHONE_BOOK_CONTACTS_LIST_CACHE) { VDBTContacts.getList(it) }
                ?.filterIsInstance<VDBTContacts>().orEmpty()
            val contacts = ArrayList<PhoneContact>()
            for (c in raw) {
                val name = c.name?.takeIf { it.isNotBlank() } ?: continue
                val id = "v:" + (c.id ?: continue)
                val numbers = buildList {
                    c.number?.let { add(it) }
                    c.cardList?.forEach { card -> card?.number?.let { add(it) } }
                }.filter { it.isNotBlank() }
                val keep = numbers.firstOrNull { FavoritesRanker.normalizeNumber(it) in wanted }
                    ?: numbers.firstOrNull()?.takeIf { id in pinned }
                    ?: continue
                contacts.add(PhoneContact(id, name, keep))
            }
            PhoneData(contacts, joinNames(recents, contacts))
        } catch (e: RuntimeException) {
            Log.w(TAG, "vdbus veri: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** Geçmişte ismi olmayan satırlara rehberden isim ekler (numara eşleşmesiyle). */
internal fun joinNames(recents: List<PhoneRecent>, contacts: List<PhoneContact>): List<PhoneRecent> {
    if (contacts.isEmpty()) return recents
    val byNumber = HashMap<String, String>()
    for (c in contacts) byNumber.putIfAbsent(FavoritesRanker.normalizeNumber(c.number), c.name)
    return recents.map { r -> if (r.name != null) r else byNumber[FavoritesRanker.normalizeNumber(r.number)]?.let { r.copy(name = it) } ?: r }
}

/**
 * Android rehberi (PBAP istemcisinin doldurduğu `com.android.bluetooth.pbapsink` hesabı) ve CallLog. Tüm rehber
 * tutulmaz: yalnızca yıldızlılar, sabitlenenler ve geçmişte geçen numaralar.
 */
internal class ContactsDataBackend(private val ctx: Context) : DataBackend {
    override val name: String = PhoneBackendName.CONTACTS_PROVIDER

    override fun available(): Boolean = granted(ctx, Manifest.permission.READ_CONTACTS)

    override fun read(pinnedIds: List<String>): PhoneData? {
        if (!available()) return null
        val recents = if (granted(ctx, Manifest.permission.READ_CALL_LOG)) readCallLog() else emptyList()
        val wanted = recents.map { FavoritesRanker.normalizeNumber(it.number) }.toHashSet()
        val contacts = readContacts(wanted, pinnedIds.toHashSet()) ?: return null
        return PhoneData(contacts, joinNames(recents, contacts))
    }

    private fun readCallLog(): List<PhoneRecent> {
        val out = ArrayList<PhoneRecent>()
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", LIMIT.toString()).build()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < LIMIT) {
                    val type = PhoneStateMap.recentType(c.getInt(0)) ?: continue
                    val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    out.add(PhoneRecent(type, c.getString(2)?.takeIf { it.isNotBlank() }, number, c.getLong(3)))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "arama geçmişi: ${t.javaClass.simpleName}")
        }
        return out
    }

    private fun readContacts(wanted: Set<String>, pinned: Set<String>): List<PhoneContact>? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        fun query(selection: String?, args: Array<String>?): List<PhoneContact>? = try {
            val out = ArrayList<PhoneContact>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = "c:" + c.getLong(0)
                    val starred = c.getInt(3) != 0
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    // Akış halinde okunur; yalnızca gerekenler bellekte kalır.
                    if (!starred && id !in pinned && FavoritesRanker.normalizeNumber(number) !in wanted) continue
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: number
                    out.add(PhoneContact(id, name, number, starred))
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "rehber: ${t.javaClass.simpleName}")
            null
        }
        val pbap = query(ContactsContract.RawContacts.ACCOUNT_TYPE_AND_DATA_SET + " LIKE ?", arrayOf("$PBAP_ACCOUNT%"))
        // Sütun/seçim desteklenmiyorsa ya da hesap türü farklıysa (boş sonuç) seçimsiz denenir.
        return if (pbap.isNullOrEmpty()) query(null, null) ?: pbap else pbap
    }

    private companion object {
        const val LIMIT = 100
        const val PBAP_ACCOUNT = "com.android.bluetooth.pbapsink"
    }
}

/** Sesli asistan: bağlı telefonda HFP sesli tanıma (BVRA → Siri / Google). Vekil hazır değilse 1,5 sn bekler. */
internal class HfpAssistant(private val ctx: Context, private val io: Executor) : AssistantBackend {
    override fun available(): Boolean = HfpLink.proxy != null

    override fun start(done: (Boolean) -> Unit) {
        HfpLink.withProxy(ctx, PROXY_WAIT_MS, io) { p ->
            val d = if (p != null) HfpLink.connectedDevice() else null
            done(p != null && d != null && HfpClientApi.startVoiceRecognition(p, d))
        }
    }

    private companion object {
        const val PROXY_WAIT_MS = 1_500L
    }
}
