package com.mapcontrol.launcher

/** Taramadan gelen ham bir ağ: [ssid] tırnaksız, [level] 0..4, [capabilities] ScanResult.capabilities. */
internal data class WifiScan(val ssid: String, val level: Int, val capabilities: String?) {
    val secured: Boolean get() = WifiListShaper.isSecured(capabilities)
}

/** Panelde gösterilen bir satır. */
internal data class WifiRow(val scan: WifiScan, val saved: Boolean)

/** Panelin Wi-Fi'ye dokunan tek yüzü; uygulama sürümü FrostNav'ın WifiController'ını, test sürümü sınırlı WifiManager'ı kullanır. */
internal interface WifiBackend {
    /** Yakındaki ağlar taranıp bağlanılabiliyor mu (test sürümünde false: yalnızca aç/kapa + geçerli ağ). */
    val canList: Boolean
    fun isEnabled(): Boolean
    /** Arka planda açar/kapatır, bitince ana iş parçacığında [done] çağrılır. */
    fun setEnabled(on: Boolean, done: () -> Unit)
    fun hasLocationPermission(): Boolean
    fun startScan(): Boolean
    fun scanResults(): List<WifiScan>
    /** Bağlı ağ: (ad ya da bilinmiyorsa null, seviye); bağlı değilse null. */
    fun current(): Pair<String?, Int>?
    fun isSaved(ssid: String): Boolean
    /** Bağlanmayı başlatır; [password] yalnızca yeni şifreli ağda. Başlatıldıysa true. Arka planda çağrılır. */
    fun connect(scan: WifiScan, password: String?): Boolean
    fun disconnect(): Boolean
}

/** Liste şekillendirme (saf, JVM'de sınanır). */
internal object WifiListShaper {
    /** Sürüşte ve park halinde aynı anda görünen en çok satır. */
    const val MAX_VISIBLE = 6

    fun isSecured(capabilities: String?): Boolean =
        capabilities != null && (capabilities.contains("WPA") || capabilities.contains("WEP"))

    fun cleanSsid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
        return s
    }

    /**
     * Aynı adlı ağları tek satıra indirir (en güçlüsü kalır), boş adları atar, bağlı olanı çıkarır (üstte ayrı satırdır),
     * en güçlüden zayıfa sıralar (eşitte ada göre). [driving] iken en çok [MAX_VISIBLE] satır (kaydırma yok), park halinde hepsi.
     */
    fun shape(scans: List<WifiScan>, connectedSsid: String?, saved: (String) -> Boolean, driving: Boolean): List<WifiRow> {
        val best = LinkedHashMap<String, WifiScan>()
        for (s in scans) {
            val name = cleanSsid(s.ssid) ?: continue
            val prev = best[name]
            if (prev == null || s.level > prev.level) best[name] = s.copy(ssid = name)
        }
        val rows = best.values
            .filter { it.ssid != connectedSsid }
            .sortedWith(compareByDescending<WifiScan> { it.level }.thenBy { it.ssid.lowercase() })
            .map { WifiRow(it, saved(it.ssid)) }
        return if (driving) rows.take(MAX_VISIBLE) else rows
    }

    /** Şifre girişi yalnızca park halinde: Sürüş düzeni etkin değil ve araç hareket etmiyor ([driving] ikisini birleştirir). */
    fun passwordAllowed(driving: Boolean): Boolean = !driving

    /** Kaydırma yalnızca park halinde (sürüşte kaydırma yok). */
    fun scrollAllowed(driving: Boolean): Boolean = !driving
}
