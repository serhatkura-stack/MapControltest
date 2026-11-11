# 3 Kasım araç günü — özet (2 Kasım gecesi hazırlandı)

**APK'lar** (`~/chery/`, git dışı; commit `ef73448`, dal `feat/frostnav-embed`, push YOK):
- `launcher-test-3kasim.apk` (SHA-256 `f86384849b15f6a2…`) — ayrı paket, FrostNav'a dokunmadan veri + komut testi bununla.
- `frostnav-launcher-3kasim.apk` (SHA-256 `07d0ca997edf20a3…`) — launcher gömülü FrostNav; yalnızca bölüm 2b'deki güvenlik kontrollerinden sonra.

**Sıra:** aşağıdaki bölüm 2 (1–9) → 2c (root dökümünden yeni sinyaller/komutlar) → yeni eklenenler (aşağıda) → alt çubuk fotoğrafları → uygunsa 2b.

**Araçtayken çalışma düzeni (ultracode):**
1. **Araca tek hat konuşur.** `adb` komutlarını yalnız ana oturum sırayla gönderir (`yarin.sh` gibi). Paralel ajanlar araca hiçbir komut göndermez.
2. **Okuma serbest, yazma tek tek.** Okuma komutları (getprop, dumpsys, logcat, sinyal okuma) hızlıca gider. Araca yazan her komut (klima, koltuk vb.) park halinde, tek tek ve önce sorularak gider. Aynı anda iki yazma yok.
3. **Analiz Mac'te, paralel.** Araçtan çekilen günlükler, dumpsys çıktıları ve ekran görüntüleri Mac'e iner. Ultracode ajanları bunları dump bulgularıyla (`katalog/BULGULAR-root-3kasim.md`) karşılaştırır: hangi sinyal neden 0, hangi ID doğru, hangi komut farklı.
4. **Döngü:** ajanlar "şunu oku / şunu dene" önerir → ana oturum araçta tek komutla dener → sonuç ajanlara geri gider.
5. **Hafif tut.** Kafa ünitesi zayıf; sürekli logcat veya üst üste dumpsys yok. Ölçüm (açılış süresi vb.) sırasında başka komut gönderilmez.

**2 Kasım'da eklenenler — araçta bak:**
1. Üst bar: solda ayarlar · [Wi-Fi | Bluetooth | şarj] · kalem, saat ortada. Sürücü koltuğundan rahat ulaşılıyor mu?
2. Bluetooth simgesi: telefon bağlıyken "bağlı" mı? Dokununca aracın telefon/Bluetooth ekranı açılıyor mu?
3. **Kablosuz şarj:** telefonu pede koy → şarj simgesi çıkıyor mu? CarSetting 44 değerini oku (0 = boş ölçüldü; dolu değer bilinmiyor).
4. Wi-Fi paneli soldan açılıyor; ağ listesi, bağlan/kes (cihaz sahibi olarak) çalışıyor mu?
5. Park ↔ Sürüş geçişinde "Sürüş düzeni" yazısı tarihin yerinde görünüyor mu?
6. Ayarlar → Ana ekran → "Araç ekranı": Otomatik'te Tiggo 7 seçili kalmalı (profil yalnız ekran boyutundan).
7. **Alt çubuk:** aracın kendi çubuğunu gündüz, düz açıdan fotoğrafla + her düğmenin işi, uzun basma, kaydırma, gece/gündüz notları. Cihazda kaydolan ekran görüntüsünü çek (komut bölüm 2c'de). Revizyon bunlarla yapılacak.

**3 Kasım gecesi eklenenler — araçta bak (Sahne düzeni varsayılan):**
8. Sahne genel: akıcılık ölçümü `adb shell dumpsys gfxinfo com.mapcontrol reset` → ekranı kullan → `dumpsys gfxinfo com.mapcontrol` (janky %, 90/99. yüzdelik). Gece/gündüz okunurluk, güneşte parlama.
9. Kapı animasyonu: sırayla sol ön (sürücü), sol arka, sağ ön, sağ arka kapı, bagaj, kaput aç-kapa → doğru parça mı dönüyor, amber "… açık" satırı ve Kapı kilidi "Kilit açık" doğru mu.
10. Birleşik koltuk: Isıtma 1-2-3 / Kapalı yazıyor mu (sürüşte de). Soğutma yazımı kapalı (SEAT_VENT_WRITE_VERIFIED=false): P'de onayla soğutma kodlamasını dene, doğrulanırsa aç.
11. Araç rengi (Görünüm → renk örnekleri) değişiyor mu; bagaj görseli renge uyuyor mu.
12. FrostNav widget'ları: Ekolayzer hazır ayar, Otomasyon anahtarları, Bölünmüş ekran, Göstergeye yansıt aç/kapat (gösterge ekranında görüntü), Web paneli (Wi-Fi paneli satırı + karekod telefondan açılıyor mu).
14. Web sunucusu ortak yöneticide (ef73448, APK'da VAR; emülatörde iki yönlü senkron doğrulandı): launcher'dan aç/kapa, Aktar sekmesiyle senkron, FrostNav kapanınca durma davranışı.
13. FrostNav'ın yüzen "←" geri düğmesi launcher'ın üstünde çıkıyor mu (FloatingBackButtonManager; FrostNav ayarından kapatılabilir).

**Tasarım (kullanıcı, 2 Kasım gecesi):** Hallmark taslakları (.hallmark/mocks: kor/tabela/kabin) "Paint gibi" bulundu. Yeni iş, test sonrası: **premium** görünüm. Üst bar ve alt bar iskelet olarak kalır. **impeccable** orta alanı ele alır: widget tasarımları, widget alanının arka planı; orta alanın iskeleti (ızgara/yerleşim) de değişebilir, o da denenecek. Eksik olan derinlik, malzeme ve iyi bir araç görseli (kendi üretimimiz, OEM kopyası değil). Kullanıcıdan 2–3 referans ekran görüntüsü istenecek.

**Karar (3 Kasım):** Ana tasarım Sahne. Serbest (hareketli ızgara) arka planda kalır, çalışır durumda tutulur ama tasarım emeği Sahne'ye.

**Tasarım yönü bulundu (2 Kasım gecesi):** `.impeccable/mocks/premium/premium-b.html` (gece) ve `premium-b-day.html` (gündüz) — araç ortada ışıklı sahnede, solda medya paneli, sağda yolculuk paneli, altta ince kontrol şeridi. Kullanıcı beğendi. Açık soru: orta alan sabit kompozisyon; düzenleme modu bu yapıya nasıl uyacak (öneri: sahne sabit, iki yan panel ve şerit yuvaları değiştirilebilir).

**Kullanıcı kararları (3 Kasım gece):** (1) Sürüşte ve parkta her widget'a dokunulabilir; "araç hareketliyken yazma yok" kuralı kaldırılıyor (WidgetWritePolicy + parkOnly). (2) Ön/arka buğu ve tavan perdesi widget'ları kaldırılıyor (nadir kullanılıyor, fiziksel tuşu var). (3) Koltuk ısıtma+soğutma tek yuva (sürücü, yolcu): dokununca Isıtma 1-2-3 / Soğutma 1-2-3 / Kapalı seçimi. (5) Web paneli Wi-Fi panelinin içine: "Wi-Fi ayarları…" üstünde anahtar + açıkken karekod/adres; açıkken kapsülde Wi-Fi yanında küçük nokta; widget olarak da kalır. (6) "Uyku modu" adı "Arka hoparlör" oluyor (Açık/Kapalı, kapalıyken ay simgesi, panelde "Ses sadece önden"). (4) Şerit ve panel için seçilmiş widget listesi; her kutu ve widget alanının boyutu yeniden değerlendirilip ideale getirilecek.

**FrostNav widget istekleri (kullanıcı, 2 Kasım gecesi), Tiggo 7 bitince:**
1. Web sunucu: dokununca FrostNav web panelini başlatır, kart karekoda (adres) döner.
2. Ekolayzer: hazır ayarlar (FrostNav ekolayzer sekmesi).
3. Otomasyon: FrostNav otomasyonları (klima / cam tavan) aç-kapa.
4. Acil kayıt: tercihen 4 kameradan 1 dk kayıt — **Berk Bey'e sorulacak** (mümkün mü, nasıl).
5. Bölünmüş ekran: tek dokunuş.
6. Gösterge ekranına yansıt / durdur: yalnızca FrostNav'ın mevcut özelliğini başlat/durdur düğmesi; navigasyon/HUD koduna dokunulmaz.

**Bekleyen (Tiggo 7 bitince):** varsayılan Park/Sürüş düzenleri baştan (kullanıcı beğenmedi); Tiggo 8 (12x8 ızgara ve ince şerit kodda hazır, koltuk paneli fikri, bir Tiggo 8 sahibinden `wm size` + fotoğraf).

---

# Yarının planı (1 Kasım): launcher'ı paylaşıma hazır etmek

Hedef: akşama "her widget veri alıyor, kontrol widget'ları araca komut gönderiyor, Tiggo 8 için hazır" diyebilmek.
**Ben** = Mac, **Sen** = araç.

## 1. Sabah, masada — ✅ 31 Ekim gecesi yapıldı
APK: `launcher-perf2.apk`, commit `8cf4e76`, SHA-256 `9232cf43ba95b20c…`. Fork'a push edildi.
Büyük ekran: 2560x1440 @160/240/320 emülatörde düzgün (ölçek uyarlaması). Müzik kartı boşluğu düzeltildi.
**Araçta komutlar:** `arac-test/yarin.sh baglan | yukle | olc once|sonra|surus | gunluk | kontak`. Paylaşım mesaj taslakları: `notlar/paylasim-taslaklari.md`.

1. **Yedek:** bugünkü commit'leri fork'a push (onayınla).
2. **Büyük ekran:** emülatörü 2560x1440 (Tiggo 8 tahmini) ve 1920x720'de açıp launcher düzenini düzelt; ekran görüntüsünü sana atarım.
3. **Tanılama:** açılışta ekran boyutu, yoğunluk, araç modeli, Android ve uygulama sürümü günlüğe ve Veri kontrolü dosyasına yazılsın (Tiggo 8 sahibinden geri bildirim için).
4. **Bilinen küçük hatalar:**
   - Sürüş düzeni kaydedilince gizli park widget'ları siliniyor.
   - Aynı widget iki kez eklenebiliyor.
5. **APK:** tek APK (`launcher-perf2.apk` yenisi), commit ve SHA not edilir.

## 2. Araçta (Sen + Ben, ~1,5 sa) — sıra önemli
P, el freni çekili, kontak açık, USB bağlı, FrostNav Web Yönetimi açık.

| # | Ne | Süre | Araca yazar mı |
|---|---|---|---|
| 1 | Eski sürümle tema geçişi + açılış süresi ölçümü ("önce") | 5 dk | Hayır |
| 2 | Yeni APK'yı kur | 5 dk | Hayır |
| 3 | Aynı ölçümler ("sonra") | 5 dk | Hayır |
| 4 | **Widget veri testi** — `widget-testi.md` bölüm A, 26 widget | 20 dk | Hayır |
| 5 | **Widget komut testi** — bölüm B, 10 adım (kilit, koltuk, sıcaklık + kaydırma, fan, perde, buğu, bagaj) | 20 dk | **Evet**, her dokunuş onayla |
| 6 | Kontak kapat-aç: launcher kendiliğinden geliyor mu, veri geri akıyor mu | 5 dk | Hayır |
| 7 | Yandex hesap eşitleme denemesi (telefonda Ev kaydet → araçta görünüyor mu) | 5 dk | Hayır |
| 8 | Kısa sürüş: işlemci/çizim ölçümü + Park/Sürüş geçişi | 15 dk | Hayır |
| 9 | **Uyarı ışığı taraması:** `../arac-test/olcum.sh tarama` açıkken göstergede uyarı yaktır (kemer takılı değil, kapı açık, el freni, varsa silecek suyu az) → `MeterMalfunction` 0–20 sinyallerinden hangisi değişiyor (şimdiye kadar hepsi [0,0]). Silecek suyu sinyali bulunursa araç kartına uyarı eklenir. | 5 dk | Hayır |

Her adımda: çalışıyor / çalışmıyor / yanlış değer. Fotoğrafları çek, AirDrop'la.

### 2b. FrostNav'ı yamalı 1.4.3 + launcher ile değiştirme (1 Kasım kararı) — SONRA
**Sıra:** önce yukarıdaki 1–9 (Launcher Test ile veri + komut testi, FrostNav'a dokunmadan), sonra yanlış çıkan kodlamaları düzelt (exp/launcher), düzeltmeleri gömme dalına (`feat/frostnav-embed`) al, APK'yı yeniden derle; ancak ondan sonra bu bölüm.
Amaç: Berk'e vermeden önce gömülü hâli araçta gerçek FrostNav olarak iyice denemek. Betik: `arac-test/frostnav-degistir.sh`.

| # | Ne | Kim | Geri dönüş |
|---|---|---|---|
| 1 | `./frostnav-degistir.sh kontrol`: sürüm, cihaz sahibi, hesap sayısı, `pm install` yolu | Ben | Hiçbir şey değişmez |
| 2 | **Karar:** `pm install` "Success" değilse veya araçta hesap varsa **DURUYORUZ** (FrostNav'sız kalırız / cihaz sahibi geri gelmez) | Birlikte | — |
| 3 | FrostNav'daki ayarların fotoğrafını çek (cam tavan otomasyonu, karşılama sesi, bölünmüş ekran, uygulamalar) | Sen | Ayarları elle geri girmek için |
| 4 | FrostNav içinden "Uygulamayı kaldır" (cihaz sahibi yetkisini kendisi bırakır) | Sen | Web yükleyiciyle resmi FrostNav geri kurulur |
| 5 | `./frostnav-degistir.sh kur`: yamalı APK + Berk'in `install.sh` izinleri ve cihaz sahibi | Sen çalıştırırsın (güvenli ayar yazıyor) | — |
| 6 | FrostNav açılıyor mu, ayarları geri gir, launcher modunu aç, Home → bizim ekran | Sen + Ben | — |
| 7 | Tam tur: FrostNav sekmeleri (kamera, cam tavan, profil, yolculuk, web paneli, APK kurma) + launcher veri/komut testi | Sen + Ben | — |

Not: Bundan sonra Berk'in resmi güncellemeleri bizimkinin üstüne kurulmaz (imza farklı). Resmi sürüme dönmek = bizimkini kaldır + web yükleyici.

## 3. Öğleden sonra, masada (Ben, ~1–2 sa)
1. Araç testinde çalışmayanları düzelt: komut kodlaması yanlış çıkanlar (koltuk ısıtma 0–3 / 1–4?, kilit 2?, buğu kapatma 2?, bagaj VR 121 / CS 92), veri gelmeyen widget'lar.
2. Doğrulanan komutların "doğrulandı" bayraklarını aç; gerçek uygulamada kilit için park kontrolünü ekle.
3. Düzeltmeler varsa **ikinci kısa araç turu** (yalnız düzeltilenler, ~15 dk).

## 4. Akşam: paylaşım hazırlığı
1. **Tiggo 8 paketi:** APK + kısa kurulum notu (FrostNav paneli → İndirilen dosyalar → Kur) + "Ayarlar → Veri kontrolü → Paylaş" ile geri bildirim isteği.
2. Önce **tek bir Tiggo 8 sahibine** gönder; düzen ve veri sorunlarını düzelt. Ekran fotoğrafına göre karar: uzun kartlarda içerik boş kalıyorsa önce kart içeriğini yüksekliğe göre büyüt (küçük iş); gerekirse sonra uzun ekranlarda daha fazla satır (12x8, büyük iş).
3. Sorun yoksa **WhatsApp "devs" grubu** mesajı (taslağı ben hazırlarım, sen gönderirsin).
4. **Berk Bey'e mesaj taslağı:** "Launcher PR olarak hazır, insanlar denesin; beğenirseniz launcher modunuzun yerine koyalım; Tiggo 8'de de çalışıyor; ileride FrostNav tasarım dilini parça parça güncelleyebilirim." Gönderme yalnız senin onayınla.

## Kapsam dışı (yarın değil)
Navigasyon/HUD, FrostNav yeniden tasarımı, tema paketleri, yolculuk kartı revizyonu, performans 3. tur.

### 2c. Root dökümünden çıkanlar — araçta denenecek (1 Kasım gecesi)
Kaynak: `katalog/oem-komut-kodlamalari.md`, `katalog/root-dump-envanter.md` (döküm Tiggo 8'den; T1E24 dalları okundu).
- **Sadece okuma (getOnce):** lastik basıncı `ReadOnly 140` (tekerlek başına mı?), toplam km 150 / toplam yakıt 153 / sürüş süresi 151–152, silecek 131–132, devridaim `Hvac 111`, oto fan 112, direksiyon ısıtma (CS160) ve ön cam ısıtma (HVAC 65) durumları — kullanıcıya göre Tiggo'larda bu donanım yok, widget katalogdan kaldırıldı; sadece komut gidiyor mu diye bak, CarConfig 59 (cam tavan sinyali VR122 mi 123 mü).
- **Yazma (P, her dokunuş onayla):** bagaj CS92 ← 1 aç / 2 kapat (eski VR121 yolu kaldırıldı); arka buğu 80 ← 2, olmazsa aralıklı 1; klima A/C (1: 1/2), Oto (4: 1/2), devridaim (8: 1 iç / 2 dış), klima kapat (18 ← 2); direksiyon ısıtma, ön cam ısıtma; sürüş modu Kum/Arazi (OEM yazarken 7/5, okurken 5/6).
- Sıcaklık sınırı artık 16–30 °C + LO/HI.
- **Arka hoparlör kapatma (fader):** `VDEventDsp.SOUND_FIELD` (983242), Bundle `type`=sağ-sol (balance), `value`=ön-arka (fade); orta 10/10, OEM "sürücü" ön ayarı 7/7 (küçük = sol/ön olabilir). Önce getOnce ile mevcut değeri oku; sonra P'de onayla fade 0 ve 20 dene: hangisi ön, arka tamamen susuyor mu, kontak kapat-aç sonrası kalıyor mu.
- **Alt çubuk ekran görüntüsü (1 Kasım, araçtan alındı):** kullanıcı araçta ekran görüntüsü aldı, cihazda bir yere kaydoldu. Bağlanınca bul ve çek: `adb shell 'ls -lt /sdcard/Pictures/Screenshots /sdcard/DCIM/Screenshots /sdcard/Pictures /sdcard/DCIM 2>/dev/null | head -20'` → `adb pull`. Ayrıca kendimiz `adb exec-out screencap -p > alt-cubuk.png` al (ana ekran + bir tam ekran uygulama açıkken). Bununla OEM alt çubuğun piksel ölçüleri (`katalog/oem-alt-cubuk-olculeri.md`) kesinleşecek.
- **Alt çubuk revizyonu (2 Kasım kararı):** bizim alt çubuk ilk hâline döndürüldü. Araçta kullanıcı OEM alt çubuğun fotoğraflarını ve özelliklerini (her düğme ne yapıyor, uzun basma, kaydırma, gündüz/gece) çekecek; revizyon buna göre yapılacak.

### 2d. Root dökümü derin analizi (3 Kasım gecesi) — araçta denenecek
Kaynak: `katalog/BULGULAR-root-3kasim.md` (7 alan, 93 bulgu, her yeni bulguya 2 bağımsız doğrulayıcı; döküm Tiggo 8 FL4'ten). Tam liste raporun "Araçta denenecekler" bölümünde (23 madde). Aşağıdakiler launcher için öncelikli olanlar.

**Bizim kodla uyuşmazlıklar (ana oturum jadx'te satırından kontrol etti):**
- **Direksiyon ısıtma CS 160:** OEM aç da kapat da hep `160 ← 1` yazıyor (anahtar gibi), 0 hiç yazmıyor (`SVSystemUI/.../svhvac/model/HvacModel.java:617`). Bizde `ComfortPlan.kt:237` `wheel_heat` kapatırken 0 yazıyor. Not: OEM aynı tıkta önce `HVAC 9 ← 1` de gönderiyor (rapora göre OEM iklim panelini açıyor; denenmeyecekler listesinde).
- **Kablosuz şarj CS 44:** OEM durum çubuğu 0 → boşta, 1 → şarj, 2 → ayrı durum (rapor "dolu" diyor, tahmin), 3 → hata (-1) (`StatusBarViewModel.java:1433`). Gösterim CS 45 anahtarına bağlı (okuma 1 = açık). Bizim `padState` 2'yi "telefon var" sayıyor, 3'ü bilmiyor.
- **VDBus aboneliği:** bizim `VDConnector.subscribeCommit` jadx'in çözemediği boş gövde; dökümdeki OEM `vdbus.jar` sürümü temiz (`VDConnector.java:431`) → taşınırsa push abonelik çalışabilir.
- **Klima gücü/AC/ön cam (MUHTEMEL):** OEM 1 = aç, 2 = kapat; açarken önce `18 ← 1`, ~150 ms sonra asıl komut. Bizim motor 0 ile kapatıyor ve sırasız yazıyor. Arka buz çözmede OEM yalnız `HVAC 80` kullanıyor, CS 159'u kimse yazmıyor (2c'deki "80 ← 2" notuyla çelişir; raporu esas al).

**[OKUMA] önce:** model kodu (`settings get system settings.system.car.model.code`), CarConfig blokları (4/75 otomatik klima mı, 33 kablosuz şarj, 120 direksiyon ısıtma — widget gösterme kuralları), `IS_NULL` ile "hiç gelmedi" ↔ gerçek 0 ayrımı (CS 44, RO 140), uyarı lambaları 327697 (kapı/kemer açıkken 42/43).

**[OEM-AYNI YAZMA] sonra (park, tek tek, geri okuyarak):** CS 160 ← 1 / ← 1; HVAC 18 ← 1 → 150 ms → 1 ← 1, kapat 1 ← 2 + 18 ← 2; HVAC 80 ← 1 / ← 1; CS 45 ← 2 aç / ← 1 kapat; ortam ışığı CS 7 ← 2 / ← 1.

**Denenmeyecekler:** HVAC 9 (bilerek denenmedikçe), CS 159, NE88 (güç kapatma), CabinLan/VDCL, EngMode fan yazması.

### 2e. Favori tuşu → Yandex Navi (3 Kasım) — araçta denenecek
Kod: `app/.../launcher/FavoriteKey.kt` (launcher kendi VDBus aboneliği: olay 65537, tip 19; kısa basış = (19,0)→(19,3) Yandex'i açar, uzun basış yalnız loglanır). Ayar: launchertest ayarlarında "Favori tuşu (direksiyon)": Kapalı (varsayılan) / Yandex Navi. Gömülü FrostNav'da ayar ekranı henüz yok.
**[OKUMA] önce:**
1. `adb shell settings get global com.desaysv.vehiclesetting.key_custom_button` ve `... .keycode` → değerleri buraya yaz (geri dönüş için).
2. `adb shell pm list packages | grep -i yandex` → `ru.yandex.yandexnavi` var mı?
3. Ayarı "Yandex Navi" yap, tuşa kısa ve uzun bas; `adb logcat -s FavoriteKey KeyEventAdapter` → `type=19 action=0/3` (kısa), `0/1/4` (uzun) geliyor mu? Yandex açıldı mı? Fabrika da kendi işini yaptı mı (çift işlem)?
4. Yandex öndeyken ve başka bir uygulama öndeyken tekrar dene (Android 10+ arka plandan uygulama açmayı engelleyebilir).
**[YAZMA] sonra (onayla, tek komut):** fabrika işini susturmak için `adb shell settings put global com.desaysv.vehiclesetting.key_custom_button 13`. `.keycode`'a ve MCU'ya dokunma. Kısa/uzun basışta fabrika sessiz mi, Yandex açılıyor mu? Bitince 1'deki değere geri yaz (ya da istersen 13'te bırak).
**Not:** OEM ayar ekranından favori tuşu yeniden seçilirse değer ezilir; launcher'ın çalışması bozulmaz ama çift işlem geri gelir.
