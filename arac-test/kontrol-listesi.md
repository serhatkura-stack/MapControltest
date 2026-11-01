# Araç testi 2. tur — kontrol listesi
P konumu, kontak açık, USB bağlı. Komutlar Mac'te `cd ~/chery/arac-test` içinde. **Ben** = Mac, **Sen** = araçta. 📷 = telefonla fotoğraf.

1. **Sen:** P, kontak açık, USB bağla.
2. **Ben:** `./olcum.sh` (salt okunur ölçüm) → `python3 -I ozet.py` (alt çubuk, inset, bildirim dinleyicileri).
3. **Sen:** Panelden **Kur** → `~/chery/launcher-test.apk` (30 Ekim 02:52, commit 956db09, SHA-256 `397180d49795…`; salt okunur, yazma denetimi temiz).
3b. **Ben (yalnızca ilk kurulumda ya da kaldırıp kurduktan sonra; güncellemede izin kalır):** müzik kartı için bildirim erişimi. 30 Ekim'de bununla çalıştı: `adb shell cmd notification allow_listener com.mapcontrol.launchertest/com.mapcontrol.media.LauncherMediaNotificationListener`. Kontrol: `adb shell settings get secure enabled_notification_listeners` (FrostNav'ınki de yerinde kalmalı).
4. **Ben:** `./olcum.sh tarama` (Ctrl-C ile bitene kadar açık kalsın).
5. **Sen:** 📷 Ana ekran gündüz + gece; tema düğmesi: Otomatik → Gündüz → Gece.
6. **Sen:** 📷 Araç kartı: Tiggo 7 görseli tam görünüyor mu; menzil, yakıt çubuğu ve % tam sayı, titremiyor mu.
7. **Sen:** Kilitle/aç: alttaki "Kilitli / Kilit açık" satırı takip ediyor mu. Kapı/bagaj/cam tavan aç: kırmızı "… açık" uyarısı ve aracın köşesinde kırmızı nokta çıkıyor mu, kapatınca kayboluyor mu.
8. **Sen:** Sürüş modlarını sırayla değiştir (Eko, Normal, Spor, Kar, Çamur, Kum, Off-road). Her birinde: üst şeritteki rozet adı doğru mu; ~2 sn sonra vurgu rengi (düğmeler, yakıt çubuğu, koltuk seviyesi) yumuşakça değişiyor mu; Normal'de rozet yok mu. 📷 Spor ve Eko. Ayarlar'dan "sürüş modu rengi"ni kapat → Normal renge dönüyor mu.
8b. **Sen:** 📷 Koltuk kutucukları (sürücü sol alt, yolcu sağ alt): araçtan ısıtmayı kademe kademe aç; kutucuk gerçek kademeyi gösteriyor mu. Kalemle bir koltuk kutucuğunu 3x2 yap: seviyeler büyüyor mu.
8a. **Ben:** B planı kontrolü: launcher ayrı paket (`com.mapcontrol.launchertest`) olarak menzil, kilit, kapı, sürüş modu ve koltuk verisini okuyabiliyor mu? Kart "Veri bekleniyor" diyorsa logcat'e bakarım.
8c. **Sen:** Alt sıradaki uygulama kutucukları 4 uygulamayla doluyor mu, boşluk kalıyor mu 📷.
9. **Sen:** 📷 Yolculuk kutucuğu vs göstergedeki TripB; "Araç duruyor" yazısı; araç sağlığı "İyi", 2x1'de V · °C.
10. **Sen:** Widget ekle: Bakım (göstergeyle karşılaştır), Hava kalitesi.
11. **Sen:** 📷 Alt çubuk: OEM çubuğuyla çakışma/çift görünüm var mı; dock adı ve gece ikon halkası; müzik kartı parça adı/ilerleme; takılma var mı.
15. **Sen:** 📷 Gösterge sağ sayfaları (yol bilgisayarı, lastik, müzik) tek tek; 📷 gösterge bakım menüsü (kayıt: 3978 km / 155 gün).
16. **Sen:** Gösterge kartı için üstte çizim izni ver (komutu ben veririm, sen çalıştırırsın): `adb shell appops set com.mapcontrol.launchertest SYSTEM_ALERT_WINDOW allow`. **Ben:** göstergeye kalibrasyon çizgilerini çizerim (`--ez launcher_cluster_grid true`, salt okunur) → 📷 hem "Yansıt" kapalı hem açıkken. Amaç: tam ekran navigasyon için görünür alanı ve QNX şeritlerinin kapladığı yerleri ölçmek.
17. **Sen:** 📷 çizgiler net görünsün diye göstergeye düz karşıdan, tüm ekranı içine alarak çek (köşelerdeki kararma görünsün).
18. **Sen:** Koltuk soğutma varsa sürücü/yolcu kademe kademe aç-kapa (tarama açıkken); sürücü profili/koltuk hafızası 1-2-3 sırayla seç. **Ben:** değişen sinyalleri okurum. 📷 göstergedeki dış sıcaklık.
19. **Ben:** ⚠ Kilit komutu kodlaması (yazma testi): **yalnızca park + onayınla**.
20. **Ben:** ⚠ Koltuk soğutmaya komut göndermek (yazma testi): **yalnızca park + onayınla**.
21. **Bitiş — Ben:** taramayı Ctrl-C ile durdur, kaydı al (`./olcum.sh` tekrar isteğe bağlı). **Sen:** çalışmayan APK'ları kaldır, FrostNav "Yansıt" AÇIKSA **DURDUR**.

## 3. tur ekleri (31 Ekim sonrası)
- **Alt çubuk:** Ayarlar → Alt çubuk: Orijinal / Bizimki / Gizli; her birinde 📷. Bizimki'de kısayollar (Araç, Ayarlar, Telefon, Uygulamalar) açılıyor mu; klima kapsülündeki sıcaklık/fan araç ekranıyla aynı mı (okuma doğrulaması).
- **Tavan perdesi, okuma (yazma yok):** tarama açıkken perdeyi kendi tuşundan TAM aç → TAM kapat (süreleri ölçeriz), sonra aç → yarıda durdur → kapat. Ben `CarSetting.ID_CAR_SUNSHADE (10)` değerlerini (1 kapalı / 2 açılıyor / 4 kapanıyor / 5 açık / 6 yarıda) ve sürelerini kaydederim.
- **Konfor yazma testi (araca YAZAR — yalnız P, hız 0, onayınla; test aracı hazırlanınca komutlar buraya):** perde aç + "dur" adayları, ön/arka buğu aç-kapa, direksiyon ısıtma aç-kapa.
  - Kurulum: `adb install -r launchertest/build/outputs/apk/debug/launchertest-debug.apk`; günlük: `adb logcat -s ComfortTest` (ayrı terminalde). Her komut sonrası 3 sn geri okuma, geçişler günlükte.
  - `H=com.mapcontrol.launchertest/com.mapcontrol.comfort.ComfortTestActivity`; `A="--es ack PARKED"` (her komutta şart, park kapısı her yazmadan önce: hız 0 değilse reddeder). Bir komut bitmeden (~8 sn) sonrakini başlatma.
  - Önce salt-okur: `adb shell am start -n $H --es c info $A`
  - Perde süresi (yazmaz, 30 sn): `adb shell am start -n $H --es c shade_time $A` sonra perdeyi elle oynat.
  - Perde: `--es c shade_open $A`, `--es c shade_close $A`; yedek VR yolu (öntanımlı değil): `shade_open_vr`, `shade_close_vr`.
  - Perde dur adayları (aç, bekle, dur, 6 sn izle): `--es c shade_open_stop --ei after_ms 2000 --ei stop 3 $A`; sonra `--ei stop 6`, sonra `--ei stop 0` (hareketteki perdeye tek başına: `--es c shade_stop --ei stop 3 $A`).
  - Ön buğu: `--es c defrost_front --ei v 1 $A` / `--ei v 0`; sadece maks: `--es c defrost_front_max --ei v 1 $A` / `--ei v 0`.
  - Arka cam: `--es c defrost_rear --ei v 1 $A` / `--ei v 0`; ikisi birden (HV 80 + CS 159): `--es c defrost_rear_both --ei v 1 $A` / `--ei v 0`.
  - Direksiyon ısıtma: `--es c wheel_heat --ei v 1 $A` / `--ei v 0`.
  - Kapanışta açtıklarını 0 ile geri kapat; OEM ekranda simge yandı mı fotoğrafla.

## Tur 5: bagaj kapağı (elektrikli) testi
**ARACA YAZAR (hepsi DOĞRULANMADI ADAY). Araç P'de (hız 0), el freni çekili; sen bagaj kapağının yanından/arkasından UZAKTA, açılma alanının dışında dur. Aynı anda TEK komut; her komut için sohbette açık "tamam"ın gerekir.** Park kapısı her gönderimden önce hızı okur (0 değilse reddeder). Başlatan paket yalnız shell, `--es ack PARKED` şart (salt-okur `trunk_info` dahil). Her yazma komutu sonrası 10 sn boyunca PLG 98 / TRUNK 19 / CS 92 geçişleri zaman damgasıyla günlüğe yazılır; bitmeden (~10 sn) sonrakini başlatma (başka test sürerken istek yok sayılır). Sonda geri alma yok: kapağı sonraki adım ya da elle kapatır.
`C=com.mapcontrol.launchertest/com.mapcontrol.comfort.ComfortTestActivity` · günlük: `adb logcat -s ComfortTest` (ayrı terminalde)

  1. **Okuma (yazmaz):** `adb shell am start -n $C --es c trunk_info --es ack PARKED` → PLG 98 (kapalıyken 0), TRUNK 19 (0), CS 92 (2) beklenir; park kapısı `true` mu? 📷
  2. **VR açma:** `adb shell am start -n $C --es c trunk_open_vr --es ack PARKED` (VR 327696 / 121 ← 1) → kapak açılıyor mu? Günlükte CS 92 → 3, PLG 98 artıyor mu? 📷 + açılma süresi.
  3. **Hiçbir şey olmadıysa CS açma:** `adb shell am start -n $C --es c trunk_open_cs --es ack PARKED` (CS 327681 / 92 ← 3). Kapak zaten VR ile açıldıysa bu adımı ATLA.
  4. **Kapatma (kapak tam açıkken, arkada kimse yok):** `adb shell am start -n $C --es c trunk_close_vr --es ack PARKED` (VR 121 ← 2) → kapanıyor mu (CS 92 → 4, PLG 98 azalır)? Olmadıysa: `adb shell am start -n $C --es c trunk_close_cs --es ack PARKED` (CS 92 ← 4).
  5. **Durdurma adayı (hareket sırasında):** önce kapağı elle ya da adım 2/3 ile açtır; hareket sürerken ayrı komut başlatılamayacağı için tek komutta aç-bekle-durdur: `adb shell am start -n $C --es c trunk_open_stop_cs --ei after_ms 2000 --es ack PARKED` (CS 92 ← 3, 2 sn sonra CS 92 ← 2) → kapak ortada durdu mu, PLG 98 sabitlendi mi, CS 92 → 2 mi? 📷 Tek başına durdurma: `adb shell am start -n $C --es c trunk_stop_cs --es ack PARKED` (yalnızca kapak hareket halindeyken anlamlı; önceki test bittikten sonra ancak elle başlatılmış harekette denenir).
  - Sonuç yazılacak yerler: çalışan yol → `ComfortWriter.TRUNK_VIA_VR` (VR 121 çalışırsa true, CS 92 çalışırsa false); `TRUNK_WRITE_VERIFIED` → true; durdurma çalışırsa `TRUNK_STOP_VALUE` = 2. Bunlar yapılana dek bagaj kutucuğu salt-okur kalır.
  - Gözle: kapağın gerçek hareketi, ölü bölge/ses/uyarı (kapanırken sıkışma koruması), logcat'te `SEND module=... id=... value=...` ve varsa `REFUSED` nedeni.

Notlar: ekran görüntüsü kapalı, hepsi fotoğraf. Başka araç: `SERIAL=xxxx ./olcum.sh`. Çıktılar: `~/chery/katalog/loglar/tur2-<tarih>/`.
