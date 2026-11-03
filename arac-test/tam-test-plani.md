# Tiggo 7 araç testi planı: dinleme ve komut (feat/frostnav-embed)

> **Hazırlık durumu (3 Kasım akşamı):**
> - Yapıldı: Z2 (fan 0 yazılmaz), Z3 (test aracında yazma için vites P şartı), Z5 (`yarin.sh` yeni APK'yı gösteriyor), F1 (hidden_watch 46 değer; camlar CS 100/101/103 yerine CS 169 kilit durumu eklendi), F2 (`--ei watch_s 10..600`).
> - İptal: Z1 (Kilit widget'ı kalıyor, yalnız 3.8'de tek deneme).
> - Yapılmadı: Z4 (komutlar kodda duruyor ama kullanılmaz), Z6 commit, Z7 (klima tuşları bu turda test edilmez), F3–F7, G listesi.
> - Kurulacak APK: `~/chery/launcher-test-3kasim-c.apk`.

Bu plan iki kişi içindir:
- **Sen** arabadasın.
- **Ben** Mac başındayım. adb komutlarını yalnız ben gönderirim.

Komutlar tek tek gider. İki komut aynı anda gitmez.

Kısaltmalar:
```
# ANDROID_SERIAL bu dokümanın dışında, terminalde ayarlanır (seri numarası notlara yazılmaz).
ADB="$HOME/.local/toolchains/android-sdk/platform-tools/adb"
LT=com.mapcontrol.launchertest
CT="$LT/com.mapcontrol.comfort.ComfortTestActivity"
LOG=~/chery/katalog/loglar/tur5-<tarih>     # özetler buraya
RAW=$LOG/raw                                 # ham dökümler; yedeğe girmez
```

İşaretler:
- **[OKUR]**: yalnız okur. Onay gerekmez.
- **[EKRAN - onay]**: araca değer yazmaz. OEM ekranı açar veya kalıcı bir Android ayarını değiştirir. Önce kısa bir "tamam" gerekir.
- **[YAZAR - onay]**: araca değer yazar. Her biri için şunlar gerekir:
  - Hemen öncesinde `info` ile okunan `GATE` satırında `speed119=0 gear26=1 handbrake75=1` görülür.
  - Sen sohbette açıkça "tamam" dersin.
  - Tek komut gider. Ardından geri okuma, sonra geri alma yapılır.
- **[2 YAZMA]**: tek dokunuş veya tek komut iki değer yazar. Onay iki değer için birlikte istenir.

---

## Asla yazılmayacaklar

Bunlara widget'tan da, adb'den de dokunulmaz.

- **Gövde ve güvenlik:**
  - merkezi kilit CS 169: adb'den asla. Kilit widget'ı yalnız 3.8'deki tek denemede (park + onay).
  - cam kilidi CS 168
  - sunroof (CS 6, VR 122/123)
  - **FrostNav Otomasyon → Sunroof anahtarı**
  - anahtarsız giriş CS 42
  - alarm CS 240
  - vites kilidi 327682/132–134
- **Sürüş dinamiği:** ESP CS 151, HDC CS 150.
- **Diğer:**
  - evcil hayvan modu
  - HUD hız limiti 327682 cmd 123/125 (kontak kapanana kadar silinmiyor)
  - EOL config
- **Bagaj:**
  - Yalnız widget ile açılıp kapanır: CS 92 ← 1 (aç) veya ← 2 (kapat). Bunlar OEM değerleri.
  - Kurallar Bölüm 3.6'da.
  - CT komutları `trunk_open_vr`, `trunk_close_vr` (VR 121) ve `trunk_open_cs`, `trunk_close_cs`, `trunk_stop_cs`, `trunk_open_stop_cs` (CS 92 ← 3/4/2) **yasak**.
- **Raporlarda "denenmez" denenler:**
  - HVAC 9, CS 159, NE 88, EPB NE 85
  - CabinLan/VDCL, MODULE_QUERY 327698
  - EngMode fanı ve EngMode sayfaları, TaskListService, Exhibition modu
- **OEM değeri olmayan CT komutları:**
  - `shade_stop`, `shade_stop_vr`, `shade_open_stop` (3/6/0 aday değerler)
  - `shade_open`, `shade_close` (CS 10; araç bunları yok sayıyor)
  - `defrost_rear --ei v 0` ve `--ei v 2` (tahmin değerler)
- **Doğrulanmamış OEM intent'leri:**
  - HvacService broadcast yedeği
  - SettingService action dizgileri

---

## Süre tahmini

| Bölüm | Süre |
|---|---|
| 0 Hazırlık (Mac, evde) | 2–3 sa (Z2–Z6 kod düzeltmeleri; hazırlandı) |
| 1 Kurulum ve güvenlik | 25–35 dk |
| 2 Dinleme | 70–90 dk |
| 3 Widget kontrolleri (öncelik 1) | 60–75 dk (her satırda Park kontrolü ve anahtar aç/kapa var) |
| 4 Test aracıyla komutlar | 35–45 dk |
| 5 Gizli özellikler | 5 dk (yalnız okuma; yazmalar için araç yok) |
| 6 Kadran / navigasyon alanı | 15 dk (yalnız dinleme) |
| 7 Bitiş | 20 dk |
| İsteğe bağlı kısa sürüş (2.12) | 15 dk |
| **Toplam arabada** | **yaklaşık 4–5 saat** |

Zaman kısalırsa sıra şöyle olur: 1 → 2.1 → 2.3 → 3 → 2'nin geri kalanı → 4 → 6 → 5.
- 2.3 atlanırsa 3.2 (koltuk havalandırma) da atlanır.
- 3.4'teki fader dinlemesi yapılmazsa 3.4 yazması da atlanır.
- 2.7 (OEM pencereleri) atlanırsa OEM pencerelerinin widget'ları kapatıp kapatmadığı bilinmez. Bunu not et.

---

## 0. Hazırlık (Mac, yola çıkmadan)

### 0.1 Durum
- `/Users/serhat/chery-embed` içinde `hidden_info` ve `hidden_watch` değişiklikleri **staged, ama commit edilmedi**. Değişen dosyalar:
  - `ComfortPlan.kt`
  - `ComfortPlanTest.kt`
  - `ComfortTestActivity.kt`
- Eldeki `-b` APK'lar:
  - `~/chery/launcher-test-3kasim-b.apk`
  - `~/chery/frostnav-launcher-3kasim-b.apk`
- Bu `-b` APK'larda fan 0 yazılabiliyordu. Z2 ile düzeltildi, yeni APK (`-c`) derlenir. `-b` kullanılmaz.
- `~/chery/launcher-perf2.apk` eski. `yarin.sh` ve `frostnav-degistir.sh` hâlâ bunu gösteriyor.

### 0.2 Kod düzeltmeleri

**Zorunlu (bunlar olmadan Bölüm 3 ve 4 yapılmaz):**

| # | Ne | Dosya | Boyut |
|---|---|---|---|
| Z1 | **İptal.** Kilit widget'ı senin tasarladığın bir kontrol, kaldırılmadı. Bu turda yalnız 3.8'de park + onayla tek deneme yapılır. | – | – |
| Z2 | Fan 0 yazılmasın. `ClimateMath.encodeFan` aralığı 1..10 olsun. İzin listesinde fan 1..10 olsun. Dokunarak kapatma (`ClimateControl.kt:118-122` → `setFan(0)`) hiçbir şey yazmasın. | `ClimateWriter.kt`, `ClimateControl.kt`, `WidgetWritePolicy.kt` | ~5 satır |
| Z3 | ParkGate vitesi de zorlasın: yazma yalnız `gear26 == 1` (P) iken gitsin. Bugün yalnız hız kontrol ediliyor. | `launchertest/.../comfort/ComfortSender.kt` | ~5 satır |
| Z4 | Kod değişmedi; bu komutlar bu turda **kullanılmaz** (Asla listesinde). Eski öneri: OEM değeri olmayan CT komutlarını ComfortPlan'dan çıkar: VR 121, CS 92 ← 3/4/2 ve `defrost_rear` için 0 ile 2. **Yerine yeni bagaj komutu ekleme.** | `app/.../comfort/ComfortPlan.kt:56-72, 281-298` | ~15 satır |
| Z5 | `yarin.sh` içindeki APK yolunu yeni dosyaya çevir (`APK=` satırı, satır 17). | `arac-test/yarin.sh` | 1 satır |
| Z6 | Commit şimdilik yok (sen isteyince). launchertest APK'sını yeniden derle. SHA-256 ve commit numarasını aşağıya yaz. | – | – |

APK bilgileri (Z6 sonrası doldurulacak):
- launchertest APK: `~/chery/launcher-test-3kasim-c.apk` (FrostNav gömülü: `~/chery/frostnav-launcher-3kasim-c.apk`, SHA-256 `ab4a60eaba5bbadc…`)
- SHA-256: `eeb0950d518688ae10db73db24dd1fc9e6ef32289eee4bcaea9757f7ff799ebd`
- commit: `7e70412` + commit edilmemiş değişiklikler (feat/frostnav-embed, push yok)

**Önerilen (Bölüm 4.8 bunsuz yapılamaz):**

| # | Ne | Boyut |
|---|---|---|
| Z7 | Klima tuşları (A/C HVAC 1, Auto HVAC 4, iç/dış hava HVAC 8, güç HVAC 18) için bir yazma yolu ekle. İki seçenek var: (a) ClimateKeys widget'ını geri koy (yazıcı ve izin listesi zaten var), (b) ComfortPlan'daki klima kapatma dizisini (1←2, 18←2, 4←2) kullanan bir CT komutu ekle. **Z7 yapılmazsa bu turun ana hedeflerinden biri test edilmemiş olur.** Bu, plan içinde açıkça yazılır. | (a) ~30 satır, (b) ~40–60 satır |

**Faydalı, küçük (zaman varsa):**

| # | Ne | Boyut |
|---|---|---|
| F1 | `hidden_watch` listesine yalnız okumak için şunları ekle: RO 15–19 (kapılar ve bagaj), RO 47, CS 44, CS 92, CS 160, CS 237 (güneşlik kilidi), CS 7 (ambiyans), CS 100/101/103 (camlar), HVAC 1/4/8/18, HVAC 9, HVAC 24/65/80, HVAC 10/12/13. | ~5 satır |
| F2 | `hidden_watch` süresi ekstra ile ayarlanabilsin (`--ei watch_s 180`). 60 saniye OEM menüsünde gezmeye yetmiyor. | ~5 satır |
| F3 | `yarin.sh` düzeltmeleri. `gunluk` modu `logcat -c` ile cihaz tamponunu siler. Bunu bil. | script |
| | – `gunluk` moduna şu etiketleri ekle: `ComfortTest FavoriteKey KeyEventAdapter DataCheck RearSpeaker ClimateKeys SVHvac SVSystemUI CarInfoProxy SVSetting` | |
| | – `PKG` için ortam değişkeni ekle | |
| F4 | `olcum.sh` ham dökümleri `$RAW` altına yazsın. `getprop`, `settings list` ve `logcat -d` bunlara dahil. Özet dosyalarından VIN, `ro.serialno`, `ro.boot.serialno`, android_id, BT/Wi-Fi MAC ve adları, telefon numaraları ve kişi adları silinsin (redaksiyon filtresi). | script |
| F5 | `~/chery-notlar/yedekle.sh` scripti `katalog/loglar/*/raw/` klasörünü yedeğe almasın. | 1 satır |
| F6 | `frostnav-degistir.sh kontrol`: 4. adımdaki `pm install` adımını kaldır (başlık "değiştirmez" diyor). APK yolunu düzelt. `-s` için `$ANDROID_SERIAL` kullan. | script |
| F7 | `widget-testi.md` güncellemesi: | doküman |
| | – Kilit satırı: "yalnız park + onayla tek deneme" | |
| | – "park-only" cümlesini kaldır (sürüş kilidi yok; Park kuralı yalnız test prosedürü) | |
| | – Klima tuşları, direksiyon ve cam ısıtma için "widget yok, CT ile veya Z7" yaz | |

**Büyük (bu tura yetişmezse sonraki tura kalır, GAP olarak işaretli):**

| # | Ne | Boyut |
|---|---|---|
| G1 | `nav_area --ei v 9\|10\|0`: CAR_LAN 721702 yazar, 721705 isteğini okur. Geri alma: 0. | ~60–100 satır (VDBus VDEvent payload) |
| G6 | `hidden_set --ei id --ei v`: yalnız Bölüm 5'teki (id, değer) çiftlerine izin verir. | ~40 satır |
| G7/G11 | `screen_info` veya `eol_dump` (yalnız okuma). Şunları okur: 196622 parlaklık, 196624 gece/gündüz, 196610/196620, CarLan 720899, IS_NULL bayrağı, 917510 EOL config blokları (config 120 direksiyon ısıtma, 100/101 ambiyans, 33 kablosuz şarj, 59). | ~50–80 satır |
| G5 | 196620 `INJECT_APP_EVENT`: ekran koruyucu ve ekran kapatma. | ~40 satır |
| G8 | VR (ses tanıma) id'lerini `hidden_watch` veya probe listesine ekle. | ~20 satır |
| G12 | FrostNav derlemesindeki widget yazmaları için log ekle. | ~10 satır |
| G13 | Güneşlik ve iki buğu çözme için widget yazıcısı (VR 124, HVAC 24, HVAC 80). | ~60 satır |

### 0.3 Yanına al
- USB kablosu ve yedeği.
- Mac şarj aleti ve inverter.
- Telefon (fotoğraf için).
- Kablosuz şarj testi için ikinci bir telefon. Bu telefonun bildirimleri sessizde olsun. Kişi adı ve numara log'a girmesin.
- Kâğıt ve kalem.
- **Bagaj testi için ikinci bir kişi** (3.6). Kimse yoksa 3.6 kendi kuralıyla yapılır.
- Aracın aküsü iyi olsun.
- **Test açık havada veya iyi havalanan bir yerde yapılır. Kapalı garajda motor asla çalışmaz.**

---

## 1. Kurulum ve güvenlik (arabada)

**Araç durumu:**
- P'de, el freni çekili.
- Kontak açık.
- Motor yalnız klima ve koltuk yazmalarında çalışır (Bölüm 3 ve 4). Diğer bölümlerde kontak açık, motor kapalı olur. Akü RO 76 / 10 = volt ile izlenir.
- USB bağlı.
- FrostNav → Web Yönetimi açık.

| # | Sen | Ben | Beklenen | Kayıt |
|---|---|---|---|---|
| 1.1 | Web Yönetimi açık kalsın | `$ADB devices`, sonra `./yarin.sh baglan` | cihaz `device` görünür, `panel http 200` gelir | – |
| 1.2 | – | Klasör aç: `mkdir -p $LOG $RAW` | – | – |
| 1.3 | – | Yükle. Z5 yapıldıysa `./yarin.sh yukle`. Yapılmadıysa elle: `$ADB forward tcp:7462 tcp:7462`, sonra `shasum -a 256 <apk>`, sonra `curl -s -m 120 -F "file=@<apk>;type=application/vnd.android.package-archive" http://localhost:7462/upload`. Önce `$ADB install -r ~/chery/launcher-test-3kasim-c.apk` bir kez denenir (29 Ekim'de engellenmişti, sen daha önce çalıştığını söyledin). Hata verirse panel yolu. | Panel cevap verir | SHA-256, 0.2'deki değerle aynı olmalı |
| 1.4 | FrostNav → Uygulamalar → İndirilen dosyalar → **Kur** | – | Kurulum tamam | – |
| 1.5 | – | **[EKRAN - onay]** (kalıcı Android ayarı, yalnız ilk kurulumda). `$ADB shell cmd notification allow_listener com.mapcontrol.launchertest/com.mapcontrol.media.LauncherMediaNotificationListener`, kontrol: `$ADB shell settings get secure enabled_notification_listeners`. Geri alma: `$ADB shell cmd notification disallow_listener com.mapcontrol.launchertest/com.mapcontrol.media.LauncherMediaNotificationListener` | Listede bizimki **ve** `com.frostnav/com.frostnav.media.LauncherMediaNotificationListener` var | çıktı |
| 1.6 | Home → launchertest | `./olcum.sh` (yalnız okuma). Ham çıktı `$RAW` altına gider (F4). F4 yapılmadıysa bu klasör yedeklenmez ve sohbete yapıştırılmaz. | `olcum-*` klasörü oluşur | klasör adı |
| 1.7 | Ana ekranı aç. Kilit kartının yerini fotoğrafla. **Kilit kartına 3.8'e kadar dokunma.** | – | Kilit kartı durumu doğru gösteriyor | fotoğraf |

**Geri dönüş (bir şey ters giderse):**
- launchertest bozulursa: `$ADB uninstall com.mapcontrol.launchertest`. Bu komut çalışıyor. Ardından Home'u OEM launcher'a döndür.
- OEM'in açtığı bir pencere kapanmazsa: `$ADB shell am broadcast -a com.desaysv.systemui.ACTION_DIALOG_CLOSE` **[EKRAN - onay]**. Bu da işe yaramazsa pencereyi elle kapat.
- Yazılan bir değer geri alınamazsa: OEM ekranından elle düzelt. Son çare: P'de kontak kapat ve aç.
- adb düşerse (kontak döngüsü, USB): 1.1'i tekrarla. Sonra `info` çalıştır, GATE satırına bak. Yazma anahtarının kapalı olduğunu kontrol et (yeniden başlatmada kendiliğinden kapanır).
- **FrostNav'ı bu turda değiştirmiyoruz** (yarin-plani §2b ayrı bir iş).
  - FrostNav derlemesinde koltuk ve kilit widget'ları log yazmadan doğrudan araca yazıyor.
  - Widget komut testi **yalnız launchertest'te** yapılır.
  - FrostNav widget'ları §2b'ye kalır: EQ, Otomasyon, Bölünmüş ekran, Göstergeye yansıt, Web paneli QR.

---

## 2. DİNLEME (yalnız okuma)

**Kurallar:**
- Ünite zayıf. **Aynı anda yalnız bir log penceresi veya döngü açık olur.** Bir sonrakini açmadan önce öncekini Ctrl-C ile kapatırım. Ölçüm sırasında başka komut gitmez.
- `hidden_watch` 60 saniye sürer (F2 yapıldıysa daha uzun). Her grubu "başla" deyince yaparsın. Yaptığın her şeyi sesli söylersin, ben zamanı not ederim.
- Her ComfortTest komutundan sonra 8–10 saniye bekle. Etkinlik yaklaşık 4 saniye sonra kendini kapatır.
- **VIN, seri numarası, telefon numarası ve kişi adları log'a da nota da yazılmaz. Ham log satırları sohbete yapıştırılmaz.**
- getOnce her id'ye cevap veriyor. Bir değerin 0 okunması "destekleniyor" demek değildir. **Destek, yalnız fiziksel bir işlemden sonra değer değişirse kanıtlanır.**
- **OEM menüsünde bir şeyi değiştirdiysen 2.1'deki başlangıç değerine geri getirirsin.** "Aç sonra kapat" demek değildir. Önceki hali neyse o.

ComfortTest komutları için log penceresi (yalnız o komutlar çalışırken açık):
`$ADB logcat -v time -s ComfortTest:I | tee -a $LOG/comforttest.log`

### 2.1 Başlangıç değerleri [OKUR] (geri almalar için şart)
| Sen | Ben | Beklenen | Kayıt |
|---|---|---|---|
| P, kontak açık, hiçbir şeye dokunma | `$ADB shell am start -n $CT --es c info --es ack PARKED` | `GATE speed119=0 speed0=0 gear26=1 handbrake75=1` ve INFO satırları (sunshade10, hvac65, hvac24, hvac80, cs159, cs160) | GATE satırı, sunshade10 değeri |
| – | `... --es c hidden_info --es ack PARKED` | 23 INFO satırı (CS 0, 1, 22, 23, 41, 42, 45, 47, 76, 77, 78, 117, 161, 177, 204, 205, 240; HVAC 69, 70, 97; RO 14, 26, 74) | **Hepsini kaydet: geri alma değerleri bunlar** |
| – | `... --es c trunk_info --es ack PARKED` | RO 98 = 0, RO 19 = 0, CS 92 = 2 | – |
| launchertest Ayarlar → Veri kontrolü → **Kaydet** | `$ADB logcat -v time -s DataCheck` (yalnız bu sırada) | `SAVED <dosya>` satırı | **Klima ve koltuk başlangıcı:** HVAC 1/4/6/8/10/12/13/18, CS 93/94/95/96, CS 7, CS 10, CS 169 (1 = açık kilit) |
| – | Ek olarak kısa bir tarama: `./olcum.sh tarama`. İlk `SCAN-DONE` satırı gelince Ctrl-C. | Tüm modüllerin `SCAN` satırları. RO 140, 150–153, 131–132, HVAC 111/112 da burada | `$RAW/tarama-baslangic` |
| – | Tek tek: `settings get system settings.system.car.model.code` (3 bekleniyor), `settings get system settings.system.car.country.code`, `getprop ro.desay.model`, `settings get global com.desaysv.vehiclesetting.key_custom_button`, `settings get global com.desaysv.vehiclesetting.key_custom_button.keycode`, `settings get global com.desaysv.setting.limiter`, `settings get system power.side.pedal.config`, `pm list packages \| grep -i yandex` | Model kodu 3. Ülke kodu bilinmiyor. key_custom_button 30 Ekim'de 9 okunmuştu | **key_custom_button ve .keycode değerlerini mutlaka kaydet** |
| – | EOL props (yalnız okuma, çıktı `$RAW`'a): `getprop vehicle.persist.project.ext.configs3`, ayrıca `configs4`, `configs5`, `vehicle.persist.combo.config` | Boş gelebilir (SELinux). Boşsa G7/G11 GAP | yalnız "boş / dolu" ve uzunluk |

**RO 14 (IGN) 2 veya 3 değilse klima ve koltuk yazma testine geçilmez.** OEM de bu durumda reddediyor. Bizim yazıcılarımız RO 14'e bakmıyor. Bu yüzden kontrolü ben elle yaparım.

### 2.2 Gizli ayarlar: hidden_watch ile OEM menüsü [OKUR]
Her grup için: `$ADB shell am start -n $CT --es c hidden_watch --es ack PARKED`. Değişiklikleri `T+…ms sig -> v` olarak yazar.

| Grup | Sen (OEM Ayarlar'da bir adım değiştir → 3 sn bekle → başlangıç değerine geri getir, adını söyle) | Kayıt |
|---|---|---|
| A | Karşılama ışığı, karşılama kokusu, kabin havalandırma/temizleme, hava temizleme otomatik, tazeleme | Değişen id ve değerler. Ters kodlamayı kontrol et: 22/23/76/77/78 okumada 0 = açık olabilir |
| B | Ambiyans müzik ritmi, yağmur sensörü hassasiyeti, sürüş modu hafızası | 117, 161, 177 |
| C | Ayna otomatik katlama, kilit geri bildirimi (ışık/korna), ADAS ses stili | 1, 0, 205 |
| D | Kablosuz şarj aç/kapat, telefonu unutma hatırlatıcısı | 45 (okumada 1 = açık), 47 |
| E | Kontak ve motor döngüsü: **2.11'e taşındı** (Bölüm 2'nin sonunda) | – |

- **Anahtarsız giriş (42) ve alarm (240) ayarlarına OEM menüsünden de dokunma.** Bunların yalnız pasif değerini izliyoruz.
- Değişmeyen id'ler "bu araçta yok veya bilinmiyor" diye not edilir.
- CS 160 `hidden_watch` listesinde yok (liste 161'i içeriyor). F1 yapılmadıysa direksiyon ısıtma burada görünmez.

### 2.3 OEM klima ve koltuk ekranları [OKUR]
- **Ben:** `./olcum.sh tarama`. LauncherProbe tüm modülleri 2 saniyede bir tarar. Log etiketi `LauncherProbe`, `CHANGE` satırlarına bakılır.
- 2 saniye kısa dokunuşları kaçırabilir. Her adımdan sonra 3 saniye bekle.
- **Sen**, OEM klima ekranında sırayla şunları yaparsın:
  1. Klima kapat ve aç (HVAC 18)
  2. A/C (HVAC 1)
  3. Auto (HVAC 4)
  4. İç/dış hava (HVAC 8)
  5. Dual/sync (HVAC 3)
  6. Her hava yönü (HVAC 6: 0 yüz … 4 cam, varsa 5/6)
  7. Sürücü sıcaklığı +1 ve −1 (HVAC 10)
  8. Yolcu sıcaklığı +1 ve −1 (HVAC 13)
  9. Fan 1'den 10'a ve geri (HVAC 12)
  10. Sürücü koltuk ısıtma döngüsü: kapalı → 3 → 2 → 1 → kapalı (CS 95)
  11. Aynısı yolcu koltuğu için (CS 96)
  12. Koltuk havalandırma tuşu varsa onu da (CS 93/94)
  13. Bitince hepsini 2.1'deki başlangıç değerlerine getir
- **Beklenen:**
  - °C = 15.5 + raw/2.
  - Koltuk okuma değeri = seviye (0..3).
  - HVAC 4 bu araçta 1/2 mi? Not et.
- **Kayıt:**
  - Her işlemin CHANGE satırı.
  - Sıcaklık 10/13 üzerinden mi geliyor, 43 mü?
  - Koltuk havalandırma donanımı var mı? CS 93/94 bugüne kadar hep 0 okudu.
- Bitince Ctrl-C.

İkinci geçiş (tarama kapalıyken, OEM log etiketleri):
`$ADB logcat -v time -s SVHvac SVSystemUI CarInfoProxy SVSetting | tee $RAW/oem-etiketler.log`
- Aynı adımlardan 3–4 tanesini tekrarla. OEM uygulamasının hangi id'ye ne yazdığını görmek istiyoruz.
- Bitince Ctrl-C.

### 2.4 Sesli komutlar: klima, koltuk, güneşlik [OKUR]
- OEM ses yolu HVAC 10/13'e bizim widget'ımızla aynı şekilde yazıyor. Bölüm 3.3 için en iyi OEM değer kaynağı burası.
- **Ben:** `./olcum.sh tarama` açık. VR etiketlerinin adları bilinmiyor (G8).
- **Sen**, sesli komutla sırayla şunları söylersin. Her biri arasında 5 saniye beklersin:
  1. "klimayı kapat", sonra "klimayı aç"
  2. "sıcaklığı 22 yap", sonra başlangıç değerini söyle
  3. "koltuk ısıtmayı aç", sonra "koltuk ısıtmayı kapat"
  4. "güneşliği aç", sonra açılma bitince "güneşliği kapat". Kimsenin eli tavanda olmasın.
- **Kayıt:** her komutta hangi id'nin hangi değere geçtiği.
- Tarama kapatıldıktan sonra en fazla 1 dakikalık filtresiz bir kayıt alınabilir: `$ADB logcat -v time > $RAW/vr-ham.log`. Komutlardan biri tekrarlanır. grep ile Mac'te bakılır. Ham dosya yedeğe ve sohbete girmez.

### 2.5 Kablosuz şarj [OKUR]
- **Sen:**
  1. Şarj pedi boşken bekle.
  2. İkinci telefonu koy, şarj başlasın.
  3. Varsa "dolu" durumuna geçmesini bekle.
  4. OEM menüsünden kablosuz şarjı kapat, sonra **başlangıçtaki CS 45 değerine** geri getir.
- **Ben:** F1 yapıldıysa `hidden_watch` yeter (CS 44 ve 45). Yapılmadıysa iki ayrı izleme var, sırayla:
  - CS 45 için `hidden_watch`,
  - CS 44 için `./olcum.sh tarama`.
- **Beklenen:**
  - CS 44: 0 boş, 1 şarj oluyor, 2 dolu, 3 hata.
  - CS 45 okuması: 1 = açık.
  - Üst bardaki şarj ikonu yalnız CS 45 == 1 iken görünür.
- **Kayıt:** değerler ve üst bar ikonunun fotoğrafı.

### 2.6 Favori tuş ve direksiyon tuşları [OKUR]
- **Sen, launchertest'te:** Ayarlar → Favori tuşu → Yandex Navi **aç**. Bu yalnız uygulama içi bir ayar, araca yazmaz. Bölüm 7'de kapatılır (varsayılan kapalı).
- **Ben:** `$ADB logcat -v time -s FavoriteKey:D KeyEventAdapter`. Önce `dinleme başladı` ve `SMS tuş olayına abone olundu` satırları gelmeli.
- **Sen:**
  - Favori tuşa kısa bas, sonra uzun bas.
  - Bunu Yandex öndeyken ve başka bir uygulama öndeyken tekrarla.
  - Sonra **her direksiyon tuşuna tek tek** bas ve adını söyle: ses +/−, ileri/geri, mod/kaynak, sesli komut, telefon, sessiz.
- **Beklenen:**
  - Kısa basış: `type=19`, action 0/3.
  - Uzun basış: action 0/1/4.
  - Kısa basışta Yandex açılır.
- **Kayıt:**
  - Tuş → type/action tablosu.
  - Fabrikanın kendi işlevi de çalıştı mı?
  - Android arka plandan başlatmayı engelledi mi?
- FavoriteKey'e gelmeyen tuşlar burada görünmez. Görünmeyenleri "kapsanmadı" diye yaz.
- Fabrika işlevi de çalışıyorsa bu turda adb ile susturulmaz. `key_custom_button 13` değeri için OEM kaynağı bulunmadı ve kalıcı bir ayar. Bkz. 4.2.

### 2.7 OEM paneller ve popup durumu [OKUR]
- **Ben:** adb shell döngüsü, 1 saniyede bir. Başka log veya tarama açıkken çalışmaz.
  `while true; do $ADB shell 'for k in hvacShow panelShow statusPopShow hvac_main_display_status hvac_chair_display_status; do echo -n "$k=$(settings get system $k) "; done; echo'; sleep 1; done`
  - `seatShow` kullanılmaz.
- **Sen**, elle:
  - OEM klima panelini aç ve kapat.
  - Koltuk panelini aç ve kapat.
  - Aşağı çekilen paneli aç ve kapat.
  - Ön cam buğu çözmeye OEM tuşundan bas, sonra tekrar basıp kapat. Bu araçta OEM menüsü açılıyordu.
- **Kayıt:** hangi anahtar hangi panelde 0/1 oluyor. Bu sayede launcher OEM pencerelerini algılayabilir.
- Bitince döngüyü Ctrl-C ile durdururum.

### 2.8 Uyarı lambaları [OKUR]
- **Ben:** `./olcum.sh tarama`. MeterMalfunction 327697 satırlarına bakılır.
- **Sen:**
  - Sürücü kapısını aç ve kapat.
  - Emniyet kemerini çöz, sonra tak (araç P'de).
  - Ayağın frende iken el frenini bırak, sonra **tekrar çek**.
  - Mümkünse cam suyu düşük uyarısını oluştur.
  - Kadrandaki her lambanın fotoğrafını çek.
- **Bitişte:** `info` çalıştırırım. `handbrake75=1` okunmadan sonraki adıma geçilmez.
- **Çözümleme (Mac'te, sonra):** `word = ((v[1]&0xFF)<<8)+(v[0]&0xFF)`, bit b → indeks `cmdId*16+b`.
- **Beklenen:** şimdiye kadar hep [0,0] okundu. Değişirse uyarı widget'ı yapılabilir.

### 2.9 Jestler ve OEM launcher [OKUR]
- **Ben:** önce `$ADB logcat -v time -s CarSystemGesture`. Sonra bunu kapatıp `$ADB shell ps -A | grep com.desaysv.launcher`.
- **Sen:** 2, 3, 4 ve 5 parmakla kaydırma, kenardan geri kaydırma, 5 parmakla Home.
- **Kayıt:** OEM launcher süreci Home bizimkiyken de çalışıyor mu? Ham jest kodunu loglamak GAP.
- Soğuk başlatma ile `ps` kontrolü 2.11'deki kontak döngüsünden sonra tekrarlanır.

### 2.10 Arayüz kontrolleri (2–3 Kasım maddeleri) [OKUR]
- **Sen**, her birini fotoğrafla:
  - Park ↔ Sürüş etiketi
  - Tiggo 7 profili
  - Araç rengi
  - Birleşik koltuk seçici (yalnız aç ve kapat, seviye seçme)
  - Sahne kapı animasyonunun sırası
- **Ben:** performans için `./yarin.sh olc ONCE`. Bu komut uygulamayı yeniden başlatır ve ölçüm yapar. Ölçüm sırasında başka komut gitmez. Ayrıca Sahne için gfxinfo alınır.

### 2.11 Kontak ve motor döngüsü [OKUR] (Bölüm 2'nin sonu)
- Araç P'de ve el freni çekili kalır.
- **Ben:** `hidden_watch` başlatırım.
- **Sen:** kontağı ACC'ye al → kapat → aç. Motoru çalıştır → kapat → kontak açık bırak.
- Kayıt: RO 14 ve RO 74. RO 26 = 1 kalmalı.
- **Ardından kurtarma:**
  1. `$ADB devices`. Gerekirse 1.1.
  2. `info`. GATE satırında `gear26=1 handbrake75=1` olmalı. RO 14 tekrar 2 veya 3 olmalı.
  3. Home yine launchertest'e geldi mi?
  4. `settings get secure enabled_notification_listeners` ile dinleyici hâlâ var mı?
  5. Yazma anahtarı kapalı mı? (Yeniden başlatmada kendiliğinden kapanır.)
  6. `ps -A | grep com.desaysv.launcher` (2.9'un soğuk başlatma sonrası hali).

### 2.12 Kısa sürüş [OKUR] (isteğe bağlı)
- Yalnız okuma. Hiçbir yazma ve widget dokunuşu yok. Sürücü ekranla uğraşmaz.
- **Ben:** `./olcum.sh tarama`.
- **Kayıt:** RO 29 sürüş süresi, RO 26 vites 5/6/7, hız, tüketim, Sürüş modu ve AWD kartlarının değerleri.
- Dönüşte araç P'de ve el freni çekili olur. `info` ile kontrol edilir.

### 2.6b Sesli komut ve kadran
Bölüm 6'da.

---

## 3. WIDGET KONTROLLERİ (öncelik 1)

**Ön koşullar:**
- Z2 (fan 0 yok) ve Z3 (test aracında vites P şartı) yapıldı. `-c` APK'sı kurulu ve SHA eşleşiyor.
- 2.1 başlangıç değerleri ve DataCheck kaydı alındı.
- Motor çalışıyor. Araç P'de, el freni çekili.
- Alt bar **"Bizimki"** olmalı. Klima kapsülü yalnız orada var.

**Log (Bölüm 3 boyunca tek pencere):**
`$ADB logcat -v time -s WidgetWrite RearSpeaker DataCheck | tee -a $LOG/widgetwrite.log`

Park kontrolü sırasında bu pencereyi kapatıp `info` çalıştırırım, sonra tekrar açarım.

**Her satır için akış:**
1. **Ben:** `info` çalıştırırım. `speed119=0 gear26=1 handbrake75=1` görürüm. RO 14 2 veya 3 olmalı (son okuma).
2. **Sen:** launchertest Ayarlar → **"Widget'lardan araca yaz (test)" = Açık**.
3. Ben "şimdi X'e bas" derim. Sen "tamam" dersin.
4. Sen **bir kez** basarsın ve ne gördüğünü söylersin. Ben log'u okurum.
5. Geri alma yapılır. Bu da aynı onay akışıyla, tek dokunuşla olur.
6. **Sen:** yazma anahtarı = **Kapalı**.
7. Sonraki satır.

**Bir yazma için "tamam" görünüşü:**
1. `widget=… module=… id=… value=… result=sent before=…` satırı geldi.
2. 3 saniye içinde `readback +Nms sig=…` satırı geldi ve değer beklenen değer.
3. OEM ekranı veya fiziksel sonuç aynı şeyi gösteriyor.

**Hata görünüşü:** `refused-not-whitelisted`, `refused-no-service` veya `no change`. Bunlardan biri gelirse dur ve not et. Aynı dokunuşu tekrarlama.

**Kilit kartı ve Sahne kilit çipi:** yalnız 3.8'de. FrostNav Otomasyon kartı launchertest'te yok.

### 3.1 Koltuk ısıtma (Sürücü / Yolcu koltuğu kartı) [YAZAR - onay]
- Kodlama: yazma 1..4 (kapalı = 1), okuma 0..3. Kaynak: `katalog/oem-komut-kodlamalari.md:66`. Araçta hiç denenmedi.
- **İlk geri okuma beklenenden farklıysa döngü durur.** Hemen OEM ekranından kapatılır.
- Koltuk havalandırması açıksa, ısıtmaya geçiş önce havalandırmayı kapatır (`SeatChoice.plan`). Bu **[2 YAZMA]** olur. 2.1'de havalandırma 0 okunduysa bu olmaz.

| # | Sen | Yazılan | Beklenen geri okuma | Geri alma |
|---|---|---|---|---|
| 3.1a | Sürücü koltuğu kartı → ısıtma, bir dokunuş (kapalı → 3) | CS 95 ← 4 | `cs95_…` = 3, OEM ekranında seviye 3 | – |
| 3.1b | Tekrar dokun (3 → 2) | CS 95 ← 3 | 2 | – |
| 3.1c | Tekrar (2 → 1) | CS 95 ← 2 | 1 | – |
| 3.1d | Tekrar (1 → kapalı) | CS 95 ← 1 | 0 | bu adım zaten geri alma |
| 3.1e | Aynı döngü yolcu kartında | CS 96 ← 4/3/2/1 | 3/2/1/0 | son adım kapalı |

- 3.1a–d tek bir "satır" sayılır. Anahtar 3.1a'da açılır, 3.1d'de kapanır. Her dokunuştan önce ayrı "tamam" alınır.
- Sahne şeridindeki koltuk kartı ve eski alt bar koltuk tuşu (ClimateBar) aynı yazıcıyı kullanır. Yalnız biri tam test edilir. Diğerinde bir dokunuş (kapalı → 3) ve geri alma (döngüyü kapalıya tamamla) yapılır. Log'da aynı satırlar gelmeli.

### 3.2 Koltuk havalandırma [YAZAR - onay] [2 YAZMA]
- **Yalnız 2.3'te donanımın var olduğu görüldüyse yapılır.** 2.3 atlandıysa bu da atlanır.
- Isıtmadan havalandırmaya geçişte kod önce ısıtmayı kapatır (CS 95/96 ← 1), sonra havalandırma değerini yazar. Log'da iki satır görünmeli. Onay ikisi için birlikte istenir.
- CS 93/94 ← 4/3/2/1, okuma 3/2/1/0.
- Geri alma: kapalı (1). Sonra ısıtma 2.1'deki değerine döner.

### 3.3 Klima kapsülü (alt bar "Bizimki") [YAZAR - onay]
| # | Sen | Yazılan | Beklenen | Geri alma |
|---|---|---|---|---|
| 3.3a | Klima açıkken sürücü sıcaklığı +1 adım | HVAC 10 ← (eski raw + 1). raw = (°C − 15.5) × 2 | `ClimateTempDriver … sent`, HVAC 10 yeni değer, OEM ekranı aynı °C | −1 adım (2.1'deki raw) |
| 3.3b | Yolcu sıcaklığı +1 adım | HVAC 13 ← eski + 1 | aynı | −1 adım |
| 3.3c | Fan +1 adım (1..10 içinde) | HVAC 12 ← eski + 1 | `ClimateFan … sent` | −1 adım |
| 3.3d | **[2 YAZMA]** Klima kapalıyken sıcaklık +1 | Önce HVAC 18 ← 1 (`ClimatePower`), sonra HVAC 10 ← x | Klima açılır, sıcaklık değişir | Sıcaklığı −1 adımla 2.1'deki raw'a döndür. Sonra klimayı OEM ekranından kapat (OEM kendisi 18 ← 2 yazar) |

- 3.3d için klimayı OEM ekranından kapatırsın. Bu bizim yazmamız değildir.
- **Fan kontrolüne "kapatmak" için dokunma.** Z2 yapıldıysa bu hiçbir şey yazmamalı. Log'da `result=sent … value=0` görülürse hemen dur.
- LO (raw 0) ve HI (raw 30) OEM değerleri. Ama bu turda denenmez, yalnız 1 adımlık değişiklikler denenir.
- Bölüm sonunda fan, sıcaklıklar ve hava yönü 2.1'deki değerlerle karşılaştırılır.

### 3.4 Arka hoparlör [YAZAR - onay, karar gerekir]
- Widget fade ← 0 yazar (ön uç, arka sessiz). **0'ın OEM değeri olduğu doğrulanmadı.**
- LauncherProbe DSP modülünü taramıyor. `RearSpeaker` logu yalnız kart kendisi okuduğunda gelir. Okumayı tetiklemek için OEM fader değiştikten sonra ana ekrana dön veya kartı kapatıp yeniden aç.
- **Önce dinle (anahtar kapalı):**
  1. `RearSpeaker` logu açık.
  2. **Sen:** OEM ses ayarlarında fader'ı merkezde bırak. Ana ekrana dön.
  3. Ben `SOUND_FIELD read … fade=` değerini not ederim. **Bu değer geri alma değeridir.**
  4. **Sen:** fader'ı en öne getir, ana ekrana dön. Ben yine okurum.
  5. **Sen:** fader'ı merkeze geri al.
- `SOUND_FIELD read` satırı hiç gelmezse bu bir GAP'tir. Yazma yapılmaz.
- Yazma kararı:
  - OEM en önde 0 okuyorsa ve merkez değeri kayıtlıysa widget testi yapılır. Bir dokunuş fade ← 0 yazar. İkinci dokunuş kayıtlı merkez değerine döner.
  - İkinci dokunuş kayıtlı değere dönmezse fader OEM'den elle merkeze alınır.
  - OEM en önde 20 okuyorsa **yazma yapılmaz**. Kod düzeltmesine kalır.

### 3.5 Yazmayan widget'lar: kısa kontrol [OKUR] (anahtar kapalı)
- Bu widget'lar araca yazmaz. Her birine bir kez dokun ve fotoğraf çek:
  - Ekranı karart (dokununca çıkılır)
  - Üst bar (Wi-Fi, BT, şarj, ayarlar)
  - Wi-Fi paneli
  - Araç / Sahne sahnesi: kapı, bagaj, kaput, cam, yakıt kapağı, lastik. Kilit çipine 3.8'e kadar dokunma.
  - Sürüş modu
  - AWD
  - Tüketim / Yolculuk / Sağlık / Hava kalitesi
  - Medya
- Veri kontrolü:
  - Ayarlar → Veri kontrolü → **Kaydet**.
  - `DataCheck` log'u gelir ve `/sdcard/Android/data/$LT/files/datacheck-<stamp>.txt` dosyası oluşur.

### 3.6 Bagaj [YAZAR - onay, bagaj kuralları]
- **Kim izliyor:**
  - **İkinci kişi** aracın arkasında durur. Kapağın açılma alanını boşaltır ve sesli rapor verir. Kapağın kendi tuşuna o ulaşır.
  - İkinci kişi yoksa: sen inersin, alanı kontrol edersin, oturursun, dokunursun. 3.6b'den önce yine inip kontrol edersin.
- **Acil durum kontrolü:** oturduğun yerden ulaşabileceğin kontrolü (anahtar kumandasındaki bagaj tuşu veya kabindeki bagaj tuşu varsa) 3.6a'dan **önce** belirle ve söyle.
- **Kurallar (kontrol-listesi Tur 5):**
  - Kapağın açılma alanında kimse ve hiçbir şey yok.
  - Tek komut.
  - Açık "tamam".
  - Ardından 10 saniye PLG ve CS 92 okuması yapılır.
  - 1 saniye içinde ikinci dokunuş yok.
  - Kapılar kilitli değil. Bu **elle** sağlanır (kapı kolu veya kumanda). Kilit widget'ı ve CS 169 kullanılmaz. Kontrol için 3.5'teki DataCheck satırında CS 169 = 1 (açık) okunmalı.
- **Geri okuma:** widget'ın geri okuması yalnız 3 saniye. Kapağın 0 → 81 gitmesi daha uzun sürüyor. Bu yüzden her dokunuştan yaklaşık 10 saniye sonra `trunk_info` çalıştırırım.

| # | Sen | Yazılan | Beklenen | Geri alma |
|---|---|---|---|---|
| 3.6a | Bagaj kartına bir kez dokun | CS 92 ← 1 (çünkü CS 92 == 2 veya RO 98 == 0) | `Trunk … sent`. `trunk_info`: RO 98 yaklaşık 0 → 81, CS 92 = 2 → 3 (açılıyor) → 2, RO 19 = 1 | – |
| 3.6b | Açılma bitince ve alan tekrar boşken dokun → **onay penceresi** → Evet | CS 92 ← 2 | RO 98 → 0, CS 92 = 2 → 4 (kapanıyor) → 2, RO 19 = 0 | bu adım geri alma |

- Hareket sırasında "dur" değeri yok, kod hiçbir şey göndermez. Acil durumda yukarıda belirlenen kontrol veya arkadaki kişi kapağın tuşunu kullanır.
- Sonuç STALE ise hiçbir şey gönderilmemiştir.

### 3.7 Widget'tan yapılamayanlar
- Direksiyon ısıtma, ön cam ısıtma, güneşlik ve buğu çözmenin widget'ı yok. Yazıcıların çağıranı yok. Bunlar Bölüm 4'te test aracıyla denenir.
- A/C, Auto, iç/dış hava ve klima güç tuşları: Z7 (a) yapıldıysa bu tuşlar 3.3 akışıyla burada denenir (Bölüm 4.8'e bak). Yapılmadıysa test edilmez.
- FrostNav widget'ları §2b'ye kalır. FrostNav'da **Sunroof otomasyonuna asla dokunulmaz**.

### 3.8 Merkezi kilit (Kilit kartı) [YAZAR - onay]
- **Ön koşul:** sen araçtasın, anahtar araç içinde. P, el freni çekili. Kapıların içten açılabildiğini biliyorsun (kapı kolu her zaman çalışır).
- **Başlangıç:** 2.1'deki DataCheck kaydında CS 169 = 1 (kilit açık) olmalı.
- Kod değeri: `WidgetWritePolicy` → CS 169 ← 2 kilitle, ← 1 aç. Başka değer izin listesinde yok.

| # | Sen | Yazılan | Beklenen | Geri alma |
|---|---|---|---|---|
| 3.8a | Kilit kartına bir kez dokun | CS 169 ← 2 | Kilit sesi, kart "Kilitli" der, log `widget=Lock … value=2 result=sent`, geri okuma kilitli | 3.8b |
| 3.8b | 5 sn sonra karta tekrar dokun | CS 169 ← 1 | Kilit açılır, kart "Kilit açık" | bu adım geri alma |

- Kilit açılmazsa: kapının içindeki kilit düğmesi veya kumanda. Not al, tekrar deneme.

---

## 4. TEST ARACIYLA KOMUTLAR (ComfortTestActivity)

### 4.0 Hazırlık
- **Sen:** launchertest Ayarlar → yazma anahtarı **Kapalı**. Bunu kontrol et ve söyle. Bölüm 4 boyunca anahtar kapalı kalır.
- Motor çalışıyor (klima yazmaları için).

**Kalıp:** `$ADB shell am start -n $CT --es c <komut> --es ack PARKED [--ei v N]`

**Her yazma komutunda akış:**
1. **Ben:** `info` çalıştırırım. `speed119=0 gear26=1 handbrake75=1` görürüm. Z3 yapılmadıysa vites kontrolü yalnız buradaki okumaya dayanır.
2. **Sen:** "tamam".
3. **Ben:** komutu gönderirim. `SEND` satırı ve 3 saniyelik `T+…ms` geri okuma gelir.
4. 8–10 saniye bekleme.
5. Geri alma komutu yine bu akışla gider. Otomatik geri alma yok.

Risk sırası (en düşükten):

### 4.1 Yalnız ekran (araca değer yazmaz) [EKRAN - onay]
| # | Komut | Geri alma | Kontrol |
|---|---|---|---|
| 4.1a | OEM klima paneli: `$ADB shell am startservice -n com.desaysv.svhvac/.service.HvacService --es type com.desaysv.svhvac.OPEN_PANEL --ei page 0` (sonra `--ei page 1` = koltuklar) | 4.1c, işe yaramazsa elle kapat | `settings get system hvac_main_display_status` / `hvac_chair_display_status` |
| 4.1b | Aşağı panel: `$ADB shell am startservice -n com.android.systemui/.service.SVSystemUIService --es type com.android.systemui.OpenNegative` | `... --es type com.android.systemui.CloseNegative` | `settings get system panelShow` |
| 4.1c | Tüm OEM popup'larını kapat: `$ADB shell am broadcast -a com.desaysv.systemui.ACTION_DIALOG_CLOSE` | – (kendisi geri alma) | `statusPopShow` |

- CLOSE_PANEL dizgisi ve HvacService broadcast yedeğinin ekstra anahtarları doğrulanmadı. Bu yüzden bunlar **gönderilmez**.
- 4.1a Android 11'de arka plan servis başlatma kuralı yüzünden engellenirse sonuç "çalışmadı" diye not edilir.
- SettingService pencereleri (kablosuz şarj, ses, Wi-Fi, BT) **GAP**. Tam action dizgileri elimizde yok.

### 4.2 Favori tuşun fabrika işlevi (bu turda adb ile yazılmaz)
- `settings put global com.desaysv.vehiclesetting.key_custom_button 13` değeri için OEM kaynağı bulunmadı. Ayrıca kalıcı bir ayar. Bu yüzden **adb ile gönderilmez**.
- Fabrika işlevi rahatsız ediyorsa **sen**, OEM araç ayarlarındaki özel tuş seçeneğinden bir seçenek seçersin. Önceki değer 2.1'de kayıtlı.
- **Ben:** `settings get global com.desaysv.vehiclesetting.key_custom_button` ile yeni değeri okurum. Bu, 13'ün OEM listesinde olup olmadığını gösterir. Sonra 2.6 tekrarlanır.
- Bölüm 7'de değer 2.1'deki değere OEM menüsünden geri alınır ve okunarak kontrol edilir.

### 4.3 Ön cam ısıtma [YAZAR - onay]
- Komut: `--es c defrost_front --ei v 1`, yani HVAC 65 ← 1.
- Beklenen: hvac65 değişir, ön cam ızgarası ısınır.
- HVAC 65 şimdiye kadar hep 0 okudu. Donanım yoksa değişmez. "Değişmedi" de bir sonuçtur.
- Geri alma: `--ei v 0` (HVAC 65 ← 2 olarak gider).
- OEM ise kapatırken 65 ← 2 **ve** 24 ← 2 gönderir. Bizim geri almamız yalnız ilk yarıyı gönderir.
  - Geri almadan sonra `info` ile hvac24 okunur.
  - hvac24 açıksa ayrı bir onayla **[YAZAR - onay]** `--es c defrost_front_max --ei v 0` gönderilir.

### 4.4 Ön maksimum buğu çözme [YAZAR - onay] [2 YAZMA etkisi]
- Komut: `--es c defrost_front_max --ei v 1`, yani HVAC 24 ← 1.
- Beklenen: hvac24 açılır. **OEM klima menüsü açılır** (bu araçta ölçüldü). Fan 10'a çıkar. A/C ve klima gücü de değişebilir. Onay bu yan etkiler için de istenir.
- Geri alma: `--ei v 0`, yani HVAC 24 ← 2.
  - Bu değer OEM değeri, ama bu araçta **ilk kez denenecek**. Önceki turda ← 0 ve tekrar ← 1 kapatmadı.
  - Kapanmazsa OEM tuşuyla kapat.
- Ardından:
  - Menü 4.1c ile kapatılır.
  - Fan, A/C, hava yönü ve sıcaklık DataCheck ile okunur. 2.1 ile karşılaştırılır. Farklıysa OEM ekranından elle geri getirilir.

### 4.5 Arka cam buğu çözme [YAZAR - onay]
- Komut: `--es c defrost_rear --ei v 1`, yani HVAC 80 ← 1.
- Beklenen: hvac80 açılır.
- Geri alma:
  1. BULGULAR'a göre OEM 80 ← 1'i aç/kapa düğmesi gibi kullanıyor. Önceki turda tekrarlanan 1 kapatmamıştı, ama çok hızlı gönderilmiş olabilir. Bu yüzden **en az 10 saniye sonra**, ayrı bir onayla, `defrost_rear --ei v 1` bir kez daha gönderilir.
  2. Kapanmazsa OEM tuşuyla kapatılır.
- `--ei v 0` ve `--ei v 2` OEM değeri değil. **Gönderilmez** (Z4 bunları koddan da çıkarır).
- CS 159 **asla**.
- Sonra fan ve klima durumu 2.1 ile karşılaştırılır.

### 4.6 Güneşlik (sunroof değil) [YAZAR - onay] (isteğe bağlı)
- **Önce okuma:** CS 237 okunur (F1 veya 2.1 tarama `SCAN` satırı). OEM, CS 237 == 1 iken güneşliği reddediyor. 1 ise yazma yapılmaz.
- **Kimsenin eli tavanda değil.** Bunu sesli söyle.
- Komut: `--es c shade_open_vr`, yani VR 327696 id 124 ← 1. Bu yöntem doğrulanmış çalışıyor.
- Geri okuma:
  - CS 10 = 2 açılıyor, 5 açık.
  - Açılma yaklaşık 6,8 saniye.
  - Kapanma yaklaşık 8,1 saniye.
- Geri alma: `shade_close_vr`, yani VR 124 ← 2. Sonuç 2.1'deki sunshade10 değeri olmalı. Başlangıçta açıksa sıra tersine çevrilir.
- **Bir sonraki komut yalnız CS 10 = 1, 5 veya 6 (durmuş) okunduktan sonra gider.** Hareket sırasında komut yön değiştiriyor. "Dur" değeri bilinmiyor, `shade_stop*` kullanılmaz.

### 4.7 Direksiyon ısıtma [YAZAR - onay] (koşullu, büyük olasılıkla atlanır)
- **TR donanımında direksiyon ısıtma yok** (ölçüldü).
- Denenme koşulu: OEM menüsünde veya fiziksel tuşta direksiyon ısıtma seçeneği görüldüyse ve F1 ile CS 160'ın bir kez değiştiği görüldüyse.
  - F1 yoksa CS 160 yalnız `info` ile tek seferlik okunur. Bu yetmez.
  - Config 120 okuması G7/G11'e kaldı.
- Komut: `--es c wheel_heat --ei v 1`. Komut CS 160'ı okur, farklıysa CS 160 ← 1 gönderir. Okunamazsa reddeder.
- Geri alma: `--ei v 0`. Yine CS 160 ← 1 gider (düğme mantığı).
- Widget kullanılmaz. Widget önce HVAC 9 ← 1 yazar ve bu OEM panelini açar.

### 4.8 Klima tuşları (A/C, Auto, iç/dış hava, güç)
- **Z7 yapılmadıysa bu turda araç yok.** Yazma yolu ne widget'ta ne CT komutunda var. Bu durumda turun ana hedeflerinden biri test edilmemiş olur ve sonuç belgesine böyle yazılır. 2.3 ve 2.4'teki OEM değerleri kaydedilir.
- **Z7 yapıldıysa** (widget veya yeni CT komutu), her biri **[YAZAR - onay]** olur. Bölüm 3 veya 4 akışıyla, tek tek yapılır:

| # | Yazılan (OEM değeri) | Geri alma |
|---|---|---|
| 4.8a | İç/dış hava HVAC 8 ← 2'nin tersi (2.1'deki değerin tersi: 1 iç / 2 dış) | 2.1'deki değer |
| 4.8b | A/C HVAC 1 ← 2 (kapalı) | ← 1 |
| 4.8c | Auto HVAC 4 ← 2'nin tersi (T1E24 1/2 kullanıyor, 2.3'te doğrulandıysa) | 2.1'deki değer |
| 4.8d | **[2-3 YAZMA]** Klima kapat: 1 ← 2, 18 ← 2, 4 ← 2 (dalda düzeltilmiş OEM dizisi) | 18 ← 1, yaklaşık 150 ms sonra 1 ← 1. Sonra 2.1 ile karşılaştır |

---

## 5. GİZLİ ÖZELLİK DENEMELERİ

- **Bugün hepsi GAP.** `hidden_set` komutu yok (G6). Bu turda **yalnız okuma** yapılır (2.1 ve 2.2).
- Aşağıdaki liste, 2.2'de değeri **değişen** id'ler için sonraki turun planıdır.
- Değerler Tiggo 8 kodundan geliyor. T7'de doğrulanmadı.

| # | Özellik | OEM değeri (yazma) | Geri alma | Araç |
|---|---|---|---|---|
| 5.1 | Karşılama ışığı CS 41 | 1 açık / 2 kapalı | 2.1'deki değer | GAP (G6) |
| 5.2 | Karşılama kokusu CS 76, kabin havalandırma CS 22, temizleme CS 23, hava temizleme oto CS 77, tazeleme CS 78 | 1 / 2 | 2.1'deki değer | GAP (G6) |
| 5.3 | Ambiyans müzik ritmi CS 117, sürüş modu hafızası CS 177 | 1 / 0 | 2.1'deki değer | GAP (G6). Önce config 100/101 ve 109 okunmalı (G11) |
| 5.4 | Yağmur sensörü CS 161 | 1..4 | eski seviye | GAP (G6) |
| 5.5 | Kablosuz şarj CS 45 | 2 açık / 1 kapalı (okuma 1 = açık) | 2.1'deki değer | GAP (G6). Config 33 == 1 olmalı |
| 5.6 | Ayna kilitte katlama CS 1 | 1 / 2 | 2.1'deki değer | GAP (G6). Config 17 gerekir |
| 5.7 | Otomatik buğu (T7 kolu) HVAC 97 | 3 açık / 1 kapalı | 2.1'deki değer | GAP (G6). Config 4 == 1, 75 == 0, 43 == 1 gerekir |
| 5.8 | Kilit geri bildirimi CS 0, ADAS ses stili CS 205 | yazma = okuma + 1 (CS 0) / 2 (CS 205) | eski değer | GAP. CS 0 ve 205 bu turda **yalnız okunur** |
| 5.9 | Ekran koruyucu / ekran kapat 196620 | (3,7) / (2,1) veya (0,1) | (3,8) / (0,0) | GAP (G5). Kısa dizi servisi çökertiyor, hep 2 int gönderilir |
| 5.10 | Ambiyans ışık CS 7 | 2 kapalı | 1 | GAP. Okuma 0 = kapalı |

- Bu turda **yalnız okunacak, yazılmayacak:** CS 42, 240, 0, 204, 205.
- T7'de beklenmeyenler (denenmez): akıllı klima HVAC 70 (yalnız T22), yan basamak CS 232, arka koltuklar CS 139/141/142/144.

---

## 6. KADRAN / NAVİGASYON ALANI

Şu an 721702'yi yazacak araç **yok** (GAP G1). Bu turda **yalnız dinleme** yapılır.

| # | Sen | Ben | Beklenen | Kayıt |
|---|---|---|---|---|
| 6.1 | – | `./olcum.sh tarama` (LauncherProbe, `NAVI-NOW` ve `NAVI-CHANGE` satırları, 2 saniyede bir) | 721702 ve 721705'in başlangıç değerleri | değerler |
| 6.2 | FrostNav Yansıt **kapalı**, Yandex önde. Sesli komut: **"haritayı göstergede göster"**, sonra kapatma komutu | Önce aynı tarama. Ctrl-C. Sonra komutlar tekrarlanırken: `$ADB logcat -v time -s Navi-VrManager CarLan` | 721705 = 9 (mini) / 10 (tam) / 0 istek gelir mi? Ayrıca `RequestNaviAreaDisplay`, KEY_TYPE 10116/10117, EXTSCREEN_WINDOW_INFO 1002 | kadran fotoğrafı |
| 6.3 | Aynısını FrostNav Yansıt **açıkken** yap | aynı sıra | Bu araçta SVVDSMap `NotSupportNavi` yazıyor (ölçüldü). QNX yine de alan istiyor mu? | fotoğraf |

- VR log etiketlerinin adları bilinmiyor. Gerekirse en fazla 1 dakika filtresiz kayıt alınır: `$ADB logcat -v time > $RAW/vr-nav-ham.log`. Sonra grep ile bakılır. Ham dosya yedeğe ve sohbete girmez.
- **721702 = 9, sonra 0 yazma testi** G1 `nav_area` komutu yazıldıktan sonra bir sonraki turda yapılır. Geri alma 0 olur.
- Bu araçta küçük kadran pencere alanı {1300,-50,580,580}.

---

## 7. Bitiş

1. **Sen:** launchertest Ayarlar → yazma anahtarı **Kapalı** (zaten kapalı olmalı, kontrol et).
2. **Sen:** launchertest Ayarlar → Favori tuşu → **Kapalı** (varsayılan).
3. **Ben:** açık logcat, tarama ve 2.7 shell döngüsünü Ctrl-C ile kapatırım. Hiçbir pencere açık kalmaz.
4. **Ben:** son kontrol (yalnız okuma): `hidden_info`, `info`, `trunk_info` ve Veri kontrolü → Kaydet.
   - Değerler 2.1 ile aynı olmalı: gizli ayarlar, klima, fan, sıcaklık, koltuklar, CS 45, sunshade10.
   - Farklı olan her şeyi sen OEM ekranından düzeltirsin. 2.2'de değiştirilen OEM ayarları da buna dahil.
5. **Ben:** `settings get global com.desaysv.vehiclesetting.key_custom_button` ve `.keycode`. 2.1 ile aynı olmalı. Farklıysa sen OEM menüsünden eski seçeneği seçersin.
6. **Ben:** dosyaları çekerim:
   - `$ADB pull /sdcard/Android/data/com.mapcontrol.launchertest/files/ $LOG/datacheck/`
   - Mac'teki `comforttest.log`, `widgetwrite.log`, tarama ve OEM etiket logları zaten `$LOG` ve `$RAW` altında. Bunların tam olduğunu kontrol ederim.
7. **Sen:** koltuk ısıtma kapalı, buğu çözmeler kapalı, güneşlik 2.1'deki durumda, bagaj kapalı, fader merkezde, klima 2.1'deki gibi.
8. **Sen:** **FrostNav Yansıt'ı durdur.**
9. Çalışmayan bir APK varsa: `$ADB uninstall com.mapcontrol.launchertest`. Bildirim dinleyicisi bu durumda kendiliğinden düşer. FrostNav'a dokunulmaz.
10. **Ben:** `$ADB forward --remove tcp:7462`.
11. **Ben:** notları yedeklerim: `~/chery-notlar/yedekle.sh`.
    - **Önce** F5 yapıldı mı diye bakarım: `raw/` klasörü yedeğe girmemeli.
    - F5 yapılmadıysa `$RAW` yedekten önce Mac'te başka bir yere taşınır.

---

## 8. Sonuçların yazılacağı yerler

| Sonuç | Değişecek yer |
|---|---|
| Koltuk ısıtma 3.1 tamam | `SeatHeat.kt` zaten yazıyor. FrostNav derlemesine log ekle (G12). `widget-testi.md` B #2–3 = OK |
| Koltuk ısıtma 3.1 uyuşmadı | `SeatHeat.encode` ve `katalog/oem-komut-kodlamalari.md:66`. FrostNav derlemesinde seat heat yazmasını kapat |
| Koltuk havalandırma 3.2 tamam | `SEAT_VENT_WRITE_VERIFIED = true` (`app/.../launcher/LauncherEdition.kt`). Donanım yoksa widget'ı T7'de gizle |
| Klima kapsülü 3.3 tamam | `CLIMATE_WRITE_VERIFIED = true` (`ClimateWriter.kt:93`). Z2 fan 1..10 kalır. IGN kontrolü ekle (RO 14 ∈ {2,3}) |
| Bagaj 3.6 tamam | `TRUNK_WRITE_VERIFIED = true` (`ComfortWriter.kt:129-147`) |
| Arka hoparlör uç değeri (0 mı 20 mi) ve merkez değeri | `SoundFieldMath` ve `REAR_SPEAKER_WRITE_VERIFIED`. Okuma tetiklenemediyse DSP okuyucu (GAP) |
| Ön cam 65 / maksimum buğu 24 / arka 80 kapatma değeri | `ComfortPlan.kt` (`defrost_front` geri almasına 24 ← 2 ekle), `HeatTiles.kt` (`HeatMath`: 65 kapalı = 2 ve 24 ← 2), `HEAT_WRITE_VERIFIED`. Buğu ve güneşlik widget'ı (G13) |
| Direksiyon ısıtma yok veya var | `HeatMath`'tan HVAC 9 ← 1'i çıkar. `WidgetWritePolicy` `SteerHeatDisplay` satırını sil. T7'de widget'ı gizle |
| Klima tuşları 2.3 / 2.4 / 4.8 değerleri | `ClimateKeys.kt`, `CLIMATE_KEYS_WRITE_VERIFIED`. HVAC 4'ün 1/2 olup olmadığı |
| Sıcaklık 10/13 mü 43 mü | `ClimateWriter.kt` |
| Gizli ayarlarda değişen id'ler 2.2 | `ComfortPlan.kt` `HIDDEN_SIGNALS`, sonraki `hidden_set` izin listesi (G6) |
| Kablosuz şarj 2.5 | `ReadOnlyTiles.kt:309-310` (CS 44/45 çözümü), `HeaderBar.kt` |
| Direksiyon tuşu tablosu 2.6 ve key_custom_button seçenekleri 4.2 | `FavoriteKey.kt`, `katalog/oem-komut-kodlamalari.md` |
| OEM panel anahtarları 2.7 | Launcher'ın OEM pencere algılaması (yeni iş) |
| Kontak döngüsü sonrası Home ve dinleyici 2.11 | `arac-test/kontrol-listesi.md` 3b |
| Güneşlik CS 237 ve durma değerleri 4.6 | `ComfortPlan.kt` (güneşlik izin listesi), G13 |
| 721705 istekleri 6 | G1 `nav_area` komutunun tasarımı |
| Ölçülen her yeni araç gerçeği | `memory/tiggo7-car-facts.md`, `katalog/KATALOG.md` §6, `arac-test/widget-testi.md` sonuç sütunu |

---

**Bilinmeyenler:**
- HVAC 24 ← 2 bu araçta kapatıyor mu?
- Arka cam buğu çözme 80: yavaş tekrarlanan ← 1 kapatıyor mu?
- Arka hoparlörde ön uç 0 mı 20 mi? DSP fader okuması tetiklenebiliyor mu?
- `key_custom_button` için OEM seçenek listesi ne? 13 bu listede var mı?
- CLOSE_PANEL dizgisi ve HvacService broadcast yedeğinin ekstra anahtarları neler? (Bu turda gönderilmiyorlar.)
- SettingService action dizgileri neler?
- VR log etiketlerinin adları neler?
- T7 ülke kodu ne?
- OEM alt bar görüntüsünün `/sdcard` altındaki tam yolu ne? Bilinmiyor. Bu yüzden bu turda çekilmez.
- Gizli ayar değerleri Tiggo 8'den geliyor. T7'de hiçbiri denenmedi.

**Temel dosyalar:**
- `/Users/serhat/chery-embed/app/src/main/java/com/mapcontrol/launcher/WidgetWritePolicy.kt`
- `/Users/serhat/chery-embed/app/src/main/java/com/mapcontrol/launcher/VehicleTiles.kt`
- `/Users/serhat/chery-embed/app/src/main/java/com/mapcontrol/launcher/ClimateWriter.kt`
- `/Users/serhat/chery-embed/app/src/main/java/com/mapcontrol/launcher/ClimateControl.kt`
- `/Users/serhat/chery-embed/app/src/main/java/com/mapcontrol/comfort/ComfortPlan.kt`
- `/Users/serhat/chery-embed/launchertest/src/main/java/com/mapcontrol/comfort/ComfortSender.kt`
- `/Users/serhat/chery-embed/launchertest/src/main/java/com/mapcontrol/comfort/ComfortTestActivity.kt`
- `/Users/serhat/chery/arac-test/yarin.sh`
- `/Users/serhat/chery/arac-test/olcum.sh`
- `/Users/serhat/chery/arac-test/widget-testi.md`
- `/Users/serhat/chery-notlar/yedekle.sh`
---

## 9. Test sonrası: FrostNav'ı bizimkiyle değiştirme (3 Kasım kararı)
Bölüm 1–8 bittikten ve bulunan hatalar düzeltildikten sonra yapılır. Adımlar `yarin-plani.md` §2b'de.
- Ayrı paket test uygulaması kalabilir ya da `adb uninstall com.mapcontrol.launchertest` ile kaldırılır.
- Bugün `adb install` çalışırsa §2b'deki "pm install Success değilse dur" koşulu sağlanmış olur.
- Araçtaki FrostNav cihaz sahibi: `adb uninstall` ile değil, FrostNav'ın içinden "Uygulamayı kaldır" ile kaldırılır.
- Önce FrostNav ayarlarının fotoğrafı (otomasyonlar, cam tavan, karşılama sesi, bölünmüş ekran, uygulamalar).
- Kurulacak: `~/chery/frostnav-launcher-3kasim-c.apk` (ya da düzeltmelerden sonra yeniden derlenen sürüm).
