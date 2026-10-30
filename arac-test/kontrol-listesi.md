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
12. **Sen:** Yandex: Kur (`Yandex_Maps_27.0.2.apk`) → aç, rota başlat. 📷 Göstergede/HUD'da ok var mı. **Ben:** kayıttan navigasyon olaylarına bakarım.
13. **Sen:** FrostNav "Yansıt" ile Yandex'i göstergeye al, 📷.
14. **Sen:** Google Maps: önce `1_Google_Maps_GSF_0.1.0.apk`, sonra `2_Google_Maps_Automotive_10.52.5.E.apk` Kur → rota. 📷 HUD/gösterge oku. **Ben:** ok hangi kanaldan/hangi sayılarla gitti, bildirimi kim okuyor. "Yansıt" 📷.
15. **Sen:** 📷 Gösterge sağ sayfaları (yol bilgisayarı, lastik, müzik) tek tek; 📷 gösterge bakım menüsü (kayıt: 3978 km / 155 gün).
16. **Sen:** Gösterge kartı için üstte çizim izni ver (komutu ben veririm, sen çalıştırırsın): `adb shell appops set com.mapcontrol.launchertest SYSTEM_ALERT_WINDOW allow`. **Ben:** göstergeye kalibrasyon çizgilerini çizerim (`--ez launcher_cluster_grid true`, salt okunur) → 📷 hem "Yansıt" kapalı hem açıkken. Amaç: tam ekran navigasyon için görünür alanı ve QNX şeritlerinin kapladığı yerleri ölçmek.
17. **Sen:** 📷 çizgiler net görünsün diye göstergeye düz karşıdan, tüm ekranı içine alarak çek (köşelerdeki kararma görünsün).
18. **Sen:** Koltuk soğutma varsa sürücü/yolcu kademe kademe aç-kapa (tarama açıkken); sürücü profili/koltuk hafızası 1-2-3 sırayla seç. **Ben:** değişen sinyalleri okurum. 📷 göstergedeki dış sıcaklık.
19. **Ben:** ⚠ Kilit komutu kodlaması (yazma testi): **yalnızca park + onayınla**.
20. **Ben:** ⚠ Koltuk soğutmaya komut göndermek (yazma testi): **yalnızca park + onayınla**.
21. **Bitiş — Ben:** taramayı Ctrl-C ile durdur, kaydı al (`./olcum.sh` tekrar isteğe bağlı). **Sen:** çalışmayan APK'ları kaldır, FrostNav "Yansıt" AÇIKSA **DURDUR**.

## 3. tur ekleri (31 Ekim sonrası)
- **Alt çubuk:** Ayarlar → Alt çubuk: Orijinal / Bizimki / Gizli; her birinde 📷. Bizimki'de kısayollar (Araç, Ayarlar, Telefon, Uygulamalar) açılıyor mu; klima kapsülündeki sıcaklık/fan araç ekranıyla aynı mı (okuma doğrulaması).
- **HUD testi (araca YAZAR — yalnız P, hız 0, senin onayınla):** `H=com.mapcontrol.launchertest/com.mapcontrol.hud.HudTestActivity`
  1. Salt okuma: `adb shell am start -n $H --es hud info --es ack PARKED` → logcat `HudNav` (gear26/handbrake75 değerlerini not al).
  2. `--es hud demo --es ack PARKED` → HUD'da "250 m, Test Cad." 📷. Görünmezse `--es hud alt --es ack PARKED` (50/53 ailesi).
  3. Ok numaraları: `--es hud icon --ei icon N --ei dist 500 --es ack PARKED` (N = 2, 3, 4, 5, 8, 9 …) her birinde 📷.
  4. Bitiş: `--es hud clear --es ack PARKED`.

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

## Tur 4: hız limiti ve TurboDog testi
Hepsi **adb ile, yalnız P (hız 0), her adım için senin sohbette açık "tamam"ınla**. Park kapısı her gönderimden önce hızı okur; 0 değilse reddeder. Başlatan paket yalnız shell, `--es ack PARKED` şart. Günlük: `adb logcat -s HudNav` (ayrı terminalde). Bir adım bitmeden (~10 sn) sonrakini başlatma.
`H=com.mapcontrol.launchertest/com.mapcontrol.hud.HudTestActivity`

**A. Hız limiti tabelası (ARACA YAZAR: modül 327682, komut 123 ve 125; her saniye yeniden gönderir, ~8 sn)**
  1. `adb shell am start -n $H --es hud limit --ei kmh 50 --es ack PARKED` → HUD'da 50 tabelası, göstergede hız limiti simgesi? 📷 (HUD + gösterge). Tabela sonda kalır (temizlenmez), kalıp kalmadığına da bak.
  2. `adb shell am start -n $H --es hud limit --ei kmh 100 --es ack PARKED` → 100'e döner mi? 📷
  3. `adb shell am start -n $H --es hud limit_off --es ack PARKED` → tabela kalkar mı? (durum 0 / limit 0 / tabela 0). 📷
  - İsteğe bağlı: `--ei status S` (öntanımlı 1), `--ez clear true` (sonunda kendisi temizler). Tabloda olmayan hızlarda (örn. 51) tabela indeksi 0 olur.
  - Gözle: HUD hız limiti işareti, göstergedeki limit simgesi, logcat'te `SEND module=327682 cmd=123/125` satırları ve `REFUSED` varsa nedeni.

**B. TurboDog yayını (araca YAZMAZ; Desay `com.desaysv.ivi.vds.navi` dinliyorsa HUD/göstergede ok görünebilir; ~10 sn, 1 sn aralık, mesafe 20 m azalır)**
  1. `adb shell am start -n $H --es hud turbodog --ei icon 2 --es ack PARKED` → HUD/gösterge 📷. Hiç tepki yoksa aynısı paket hedefli: `... --es hud turbodog --ei icon 2 --ez explicit true --es ack PARKED`.
  2. `adb shell am start -n $H --es hud turbodog --ei icon 3 --ei dist 400 --es ack PARKED` → ok değişti mi? 📷
  3. AMap varyantı: `adb shell am start -n $H --es hud amap --ei icon 2 --es ack PARKED` (gerekirse `--ez explicit true`) → 📷
  - Gözle: Desay navi uygulaması/HUD/gösterge tepkisi; logcat'te her `BROADCAST action=...` satırı. Sıra: limit 50 → limit 100 → limit_off; turbodog (ikon 2, sonra 3) → amap.
  - Tepki yoksa "yayın alınmadı" demek kesin değil (alıcı olmayabilir); sonucu 📷 ile kaydet, bir sonraki adıma geçmeden sana sorarım.

Notlar: ekran görüntüsü kapalı, hepsi fotoğraf. Başka araç: `SERIAL=xxxx ./olcum.sh`. Çıktılar: `~/chery/katalog/loglar/tur2-<tarih>/`.
