package com.mapcontrol.launcher

/**
 * Mini göstergenin navigasyon satırının veri kaynağı. Henüz gerçek bir kaynak yok (Yandex sonraki dönüş / varış okunması
 * araştırılıyor, bkz. katalog/tasarim-kararlari-4kasim.md §5); bu yüzden varsayılan [NONE] hiç ipucu vermez ve satır gizli
 * kalır. Bir kaynak (ör. bildirim dinleyicisinden ya da gösterge yansıtmasından okunan dönüş) bu arayüzü uygular ve
 * [MiniDashActivity.navSourceFactory] üzerinden verilir.
 *
 * Sözleşme: [start] görünür olunca (onStart), [stop] gizlenince (onStop) çağrılır; [stop] sonrası dinleyici çağrılmaz.
 * Dinleyici ana iş parçacığında çağrılır; null = gösterilecek ipucu yok (satır gizlenir).
 */
internal interface MiniNavSource {

    fun interface Listener {
        fun onNav(hint: NavHint?)
    }

    fun start(listener: Listener)

    fun stop()

    companion object {
        /** Kaynak yok: satır hep gizli. */
        val NONE: MiniNavSource = object : MiniNavSource {
            override fun start(listener: Listener) = listener.onNav(null)
            override fun stop() = Unit
        }

        /** Demo (ekran görüntüsü): sabit bir dönüş ve varış. Yalnızca [LauncherDemo] açıkken ve istenirse kullanılır. */
        val DEMO: MiniNavSource = object : MiniNavSource {
            override fun start(listener: Listener) = listener.onNav(NavHint("Sağa dönün · Atatürk Cd.", 350, 14 * 60 + 32))
            override fun stop() = Unit
        }
    }
}
