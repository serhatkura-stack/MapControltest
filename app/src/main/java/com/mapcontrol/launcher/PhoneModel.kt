package com.mapcontrol.launcher

import android.app.Activity
import android.content.Context
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
 * Telefon durumu ve eylemleri. UI yalnızca bunu çağırır; arka uç paketi gövdeleri doldurur (bu sürüm iskelettir:
 * hepsi güvenli varsayılanla boş işlem).
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

    /** Görünür sahip ekler; ilk sahipte arka uçlar seçilir ve okuma başlar. [owner] genelde Activity ya da panel. */
    fun start(context: Context, owner: Any = context) {}

    /** Sahibi çıkarır; sahip kalmazsa dinleme/okuma durur. */
    fun stop(owner: Any) {}

    fun snapshot(): PhoneSnapshot = PhoneSnapshot.EMPTY

    /** Eklenince güncel durum hemen (ana iş parçacığında) bir kez bildirilir. */
    fun addListener(listener: PhoneListener) {}

    fun removeListener(listener: PhoneListener) {}

    /** Numarayı arar. Bağlı telefon ya da yol yoksa false. */
    fun dial(number: String): Boolean = false

    /** Gelen aramayı cevaplar. */
    fun answer(): Boolean = false

    /** Gelen aramayı reddeder. */
    fun reject(): Boolean = false

    /** Aktif/çıkan aramayı kapatır. */
    fun hangUp(): Boolean = false

    /** Mikrofonu kapatır/açar ([CallState.muted] değişir). */
    fun toggleMute(): Boolean = false

    /** Bağlı telefonun sesli asistanını (Siri / Google, HFP BVRA) başlatır. */
    fun startVoiceAssistant(): Boolean = false

    /** Rehber/geçmiş/arama izinlerini ister (READ_CONTACTS, READ_CALL_LOG, CALL_PHONE). */
    fun requestPermissions(activity: Activity) {}

    /** İzin sonucu geldi: veri yeniden okunur. */
    fun onPermissionsResult(context: Context) {}

    /** Direksiyon ses tuşu (VR) uzun basış dinleyicisini ayara göre açar/kapatır ([PhonePrefs.assistantOnVoiceKey]). */
    fun syncVoiceKey(context: Context) {}

    /** Ana ekrana dönüşte ses tuşu aboneliğini tazeler (uyku sonrası düşmüş olabilir). */
    fun refreshVoiceKey() {}

    /** Etkinlik kapanırken: yalnızca dinleyiciyi son başlatan örnek kapatabilir. */
    fun stopVoiceKey(owner: Any) {}
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
