# Widget testi: veri alıyor mu, komut gönderiyor mu?

Amaç: launcher'daki her widget'ın araçtan veri aldığını ve kontrol widget'larının araca gerçekten komut gönderdiğini görmek.
APK: `launcher-perf2.apk`. **Ben** = Mac, **Sen** = araç, 📷 = fotoğraf.

## Hazırlık (5 dk)
1. **Sen:** P, el freni çekili, kontak açık, USB bağlı. **Ben:** FrostNav paneline APK'yı yüklerim, sen kurarsın.
2. **Ben:** `adb logcat -s WidgetWrite` kaydını başlatırım (her komut ve aracın 3 sn içindeki tepkisi buraya düşer).
3. **Sen:** Ayarlar → Ana ekranı düzenle → aşağıdaki bütün widget'ları ekle (yer yetmezse iki tura böleriz).

## A. Veri (araca hiçbir şey yazmaz, 15 dk)
Her satırda: widget'ta değer var mı ("Araç verisi bekleniyor" ya da "—" kalmamalı) ve aracın kendi ekranıyla aynı mı? 📷

| Widget | Ne yapıp bakacağız |
|---|---|
| Araç kartı | Menzil, yakıt %, kilit; bir kapıyı aç → kırmızı uyarı |
| Kilit | Anahtardan kilitle/aç → durum değişiyor mu |
| Sürüş modu | Modu değiştir → ad değişiyor mu |
| Anlık tüketim, Yolculuk, Araç sağlığı | Göstergedeki yol bilgisayarıyla karşılaştır |
| Akü | Volt değeri (~12–14 V) |
| Bakım | Göstergedeki bakım km/gün ile aynı mı |
| Hava kalitesi | İç/dış PM2.5 değeri var mı |
| Gövde | Kapı, bagaj, kaput, cam aç → "… açık" çıkıyor mu |
| Telefon | Telefonu kablosuz şarja koy/al |
| Mola | Kontak açıkken sürüş süresi (sürüşte artar) |
| 4x4 | Ön/arka dağılım; göstergedeki 4x4 sayfasıyla karşılaştır |
| Koltuk ısıtma / soğutma | Aracın ekranından kademe değiştir → widget aynı kademeyi gösteriyor mu |
| Alt çubuk: sıcaklık, fan | Aracın ekranından değiştir → aynı değer mi |
| Tavan perdesi | Kendi tuşundan aç/kapat → durum ve çizim değişiyor mu |
| Buğu ön/arka | Aracın tuşundan aç/kapat → Açık/Kapalı |
| Bagaj | Elle aç/kapat → açıklık % ve durum |
| Saat, Hava durumu, Müzik | Saat doğru mu; hava var mı; Bluetooth müzikte parça adı |
| Klima tuşları (yeni) | A/C, Oto, iç/dış hava, klima güç tuşlarını **aracın kendi ekranından** değiştir → widget'taki dolgu aynı mı? İç/dış havada hangi yönün "İç hava" yazdığını not et (okuma: HVAC 8 == 0 → iç hava varsayımı). `logcat -s ClimateKeys` HVAC 111 değerini de yazar |
| Isıtmalar (yeni) | Direksiyon ısıtmayı ve ön cam ısıtmayı aracın tuşundan aç/kapat → "Açık/Kapalı" doğru mu (CarSetting 160 / HVAC 65 == 1). 65 daha önce hiç değişmemişti: değişmezse "—/Kapalı" kalır, not et |
| Lastik basıncı (yeni) | ReadOnly 140: dört değer mi, tek mi, birim doğru mu (göstergedeki/OEM lastik ekranıyla karşılaştır, 📷)? Boşsa "Araç verisi bekleniyor" kalmalı. Ham diziyi **Ayarlar → Veri kontrolü → "Yeni sinyaller"** grubundan (satır "Lastik basıncı (140)") fotoğrafla / paylaş |
| Toplam (yeni) | Toplam km göstergeyle aynı mı (150, yoksa 71)? Toplam yakıt (153) litre mi? Sürüş süresi (151/152) dakika mı, göstergeyle aynı mı? Olmayan sinyal satırdan düşer; ham değerler aynı grupta (150, 153, 151, 152) |
| Arka hoparlör (yeni) | Okuma: "Açık" yazmalı (fader 10/10). `adb logcat -s RearSpeaker` `SOUND_FIELD read balance=… fade=…` satırı 10/10 göstermeli; satır yoksa okuma boş |
| Uygulama kısayolu, Fotoğraf, Ekranı karart | Dokun → uygulama açılıyor mu / resim seçiliyor mu / ekran kararıp geri geliyor mu |

## B. Komut (araca YAZAR, 15 dk)
**Sen:** Ayarlar → **"Widget'lardan araca yaz (test)" → Açık**. Anahtar kaydedilmez; uygulama ya da kontak yeniden başlayınca kendiliğinden kapanır.
Kurallar: araç P'de; komutlar yalnızca park halindeyken gider (değilse reddedilir ve günlüğe yazılır). **Her dokunuştan önce ben "şimdi şuna bas" derim, sen basarsın, ne olduğunu söylersin.** Bir widget yanlış davranırsa anahtarı kapatırız.

| # | Widget | Dokunuş | Araca giden | Beklenen | Not |
|---|---|---|---|---|---|
| 1 | Kilit | Kilitle, sonra aç | CarSetting 169 ← 2 / 1 | Kapılar kilitlenir / açılır | Kilitleme değeri (2) doğrulanmadı |
| 2 | Koltuk ısıtma (sürücü) | 4 kez dokun: 1 → 2 → 3 → kapalı | CarSetting 95 ← 2, 3, 4, 1 | OEM ekranı aynı kademe | Kodlama doğrulanmadı (okuma 0–3, yazma 1–4) |
| 3 | Koltuk ısıtma (yolcu) | Aynı | CarSetting 96 | Aynı | |
| 4 | Koltuk soğutma (varsa) | Aynı | CarSetting 93 / 94 | Aynı | Doğrulanmadı |
| 5 | Alt çubuk sıcaklık | Sürücü: ok ile +0,5 / −0,5, sonra dereceyi yukarı-aşağı **kaydırarak** 2–3 derece değiştir; yolcu aynı; uzun bas → yolcu sürücüyle eşitlenir. **Uçlar:** en alta kaydır → "LO" (ham 0), en üste → "HI" (ham 30); 30,5'ten fazlası olmamalı. Klima kapalıyken bir kez dene: önce `HVAC 18 ← 1` gitmeli | Hvac 10 / 13 ← (°C − 15,5) × 2 (LO = 0, 16–30 °C = 1..29, HI = 30); kapalı klimada önce Hvac 18 ← 1 | Klima ekranı değişir; LO/HI aracın ekranında da LO/HI | Doğrulanmadı; üst sınır 32 → 30 (OEM) |
| 6 | Alt çubuk fan | Kaydır 1 → 5 → 1 | Hvac 12 ← 0–10 | Fan hızı değişir | Doğrulanmadı |
| 7 | Tavan perdesi | Aç, bitince kapat | VR 124 ← 1 / 2 | Perde hareket eder | adb'den doğrulandı, widget'tan değil |
| 8 | Buğu ön | Aç, sonra kapat | Hvac 24 ← 1 / 2 | Simge yanar / söner | Açma doğrulandı; kapatma (2) denenecek |
| 9 | Buğu arka | Aç, sonra kapat | Hvac 80 ← 1 / 2 | Aynı | Aynı |
| 10 | Bagaj | **Sen kapaktan uzakta.** Aç; tam açılınca onaylı kapat. 1 sn içinde ikinci dokunuş yok sayılmalı | CarSetting 92 ← 1 (aç) / 2 (kapat); değer OEM gibi `(CS92==2 \|\| PLG==0) ? 1 : 2` seçilir | Kapak açılır / kapanır | Doğrulanmadı. VR 121 ve 3/4 değerleri **kaldırıldı**; çalışmazsa günlükteki tepkiye bak |
| 11 | Klima tuşları: A/C | Aç, kapat | Hvac 1 ← 1 / 2 | A/C simgesi yanar / söner | Doğrulanmadı |
| 12 | Klima tuşları: Oto | Aç, kapat | Hvac 4 ← 1 / 2 | Oto yanar / söner | Doğrulanmadı |
| 13 | Klima tuşları: iç/dış hava | İç havaya al, dışa al | Hvac 8 ← 1 (iç) / 2 (dış) | Devridaim lambası; widget etiketi İç hava ↔ Dış hava | Okuma yönü (0 = iç hava) doğrulanmadı |
| 14 | Klima tuşları: Klima (güç) | Kapat, sonra aç | Hvac 18 ← 2 / 1 | Klima kapanır / açılır | Doğrulanmadı |
| 15 | Isıtmalar: direksiyon | Aç, kapat | Hvac 9 ← 1, ardından CarSetting 160 ← 1 (anahtar: açık/kapalı fark etmez) | Direksiyon ısınır / söner | Doğrulanmadı; kapatmak için de aynı 1 |
| 16 | Isıtmalar: ön cam | Aç, kapat | Hvac 65 ← 1 / 2 | Ön cam ısıtma yanar / söner | Doğrulanmadı |
| 17 | Arka hoparlör | Dokun (arka kapanır), tekrar dokun (geri gelir). Ses çalarken dinle; arka koltukta biri varsa sor | DSP SOUND_FIELD (983242), düz Bundle: "type" = balance (okunan, korunur), "value" = fade ← ön uç (0); ikinci dokunuşta (balance, eski fade) | Arka hoparlörler susar; `adb logcat -s RearSpeaker WidgetWrite` | Yük biçimi aracın Ayarlar uygulamasından (kanıtlı); **ön uç 0 mı 20 mi doğrulanmadı** (`SoundFieldMath.FRONT_EXTREME`). `dsp_hal setSoundField: balance … fader …` satırından izle. Denenecek: `VDEventDsp.REAR_MUTE` (983045) |

Bitince: anahtarı kapat. **Ben:** `WidgetWrite` kaydını alırım; her satır için "çalıştı / çalışmadı / yanlış değer" yazarız. Çalışmayanların kodlamasını kayıttaki araç tepkisine göre düzeltip bir sonraki turda yeniden deneriz. Doğrulananları gerçek uygulamada da açarız.

## Bilinen açıklar
- Gerçek uygulamada kilit komutunun park kontrolü yok; test sürümünde var. Gerçek sürüme çıkmadan eklenecek.
- Koltuk ve klima widget'ı dokununca değeri hemen gösterir; komut reddedilirse bir sonraki okumada (≤3 sn) düzelir.
