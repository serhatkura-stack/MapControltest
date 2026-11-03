# Tur 5 araç testi sonuçları (3 Kasım 2026, Tiggo 7 T1E24)

Kayıtlar: `katalog/loglar/tur5-2026-11-03/` (git dışı). GPS ve tanı ekranı görüntülerinde konum var; paylaşma.
APK: `~/chery/launcher-test-3kasim-c.apk` (feat/frostnav-embed 7e70412 + commit edilmemiş değişiklikler).

## Kurulum
- `adb install` araçta yine engelli: user 0'da `no_install_apps`, `no_uninstall_apps`, `no_install_unknown_sources`. Cihaz yöneticisi yok, kilit fabrikadan.
- Çalışan yol: `app.frostnav.com` → "adbauth.key sahibiyim" → Bağlan → **Dosyadan Yükle**. Kilit sadece o oturumda açık; sonradan adb ile kurulmuyor.
- Dosyadan Yükle büyük dosyada (211 MB) "dosya büyük" diyor. Büyük APK için: FrostNav web paneli (7462) + curl upload, sonra FrostNav → Uygulamalar → İndirilen dosyalar → Kur (FrostNav'a önce "Yüklü Uygulamalara Yetki Ver").
- Market'teki Yandex Navi 31.1.4 yeniden imzalı: "application signature mismatch", açılmıyor. Orijinal Yandex Haritalar 27.0.2 kuruldu ve çalışıyor.
- Araçta şimdi: resmî FrostNav (`com.frostnav`), Yandex Haritalar 27.0.2, Launcher Test. Bizim gömülü FrostNav (`com.mapcontrol`) kaldırıldı.

## Doğrulananlar (yazma ve okuma)
| Ne | Yazılan | Okunan |
|---|---|---|
| Sürücü koltuk ısıtma CS 95 | kademe+1 (2/3/4), kapat 1 | 1/2/3, kapalı 0 |
| Sürücü koltuk havalandırma CS 93 | kademe+1 (2/3/4), kapat 1 | 1/2/3, kapalı 0 — **havalandırma araçta var** |
| Klima gücü HVAC 18, sıcaklık 10/13 | 18←1, sıcaklık ham değer | aynen |
| Merkezi kilit CS 169 | 2 kilitle, 1 aç | 0 kilitli, 1 açık |
| Bagaj CS 92 | 1 aç, 2 kapat | CS 92: 1 açık, 2 kapalı; RO 98 konum 0→95 |
| Ön maks buğu HVAC 24 | 1 / 2 | 1 / 0 (fabrika klimayı da açıyor, kapatınca klima açık kalıyor) |
| Arka buğu HVAC 80 | 1 (kapama değeri denenmedi) | 1 / 0 |
| Direksiyon ısıtma CS 160, ön cam HVAC 65 | yazma geri okunuyor | **donanım yok** (TR donanımı); geri okuma kanıt değil |

## Dinlemede bulunanlar
- Klima aç (fabrika ekranı): 18, 1, 4 birlikte 1; kapat: hepsi 0, fan 0.
- HVAC 9 klima ünitesinin "ekran etkin" bayrağı; Android panelini güvenilir göstermiyor.
- Fabrika klima paneli ~21 sn sonra kendiliğinden kapanıyor. `HvacService` CLOSE_PANEL kapatıyor; `ACTION_DIALOG_CLOSE` kapatmıyor. OPEN_PANEL shell'den açmadı.
- Ayarlar: CS 41 karşılama ışığı, CS 7 ambiyans, CS 161 yağmur sensörü (1..3), CS 51 geri viteste ayna, CS 3 far gecikmesi, CS 39 akıllı kilit, CS 40 akıllı bagaj, CS 121 şerit uyarısı, CS 168 cam kilidi (kilitle birlikte), koku HVAC 61/62/71.
- Bulunamayan: kilitte ayna katlama (CS 1 değişmedi), müziğe göre ambiyans ritmi.
- Favori tuş: fabrika değeri 9 = AVM. Basış bizim launcher'a ulaşıyor (`FavoriteKey`). Test sonunda fabrika değeri **13** (sessiz) yapıldı; geri alma `settings put global com.desaysv.vehiclesetting.key_custom_button 9`.
- Sesli komut "haritayı göstergede göster": asistan uyandı ama `controlCluster` hiç gelmedi; Türkçe bu komut yok.
- Ekran görüntüsü: aracın kendi aracı `/storage/emulated/0/ScreenShotCut_*.png` kaydediyor; orijinal alt bar giriyor, klima paneli girmiyor.
- Performans (boşta): launcher CPU %1–4, PSS 89 MB.

## Düzeltilecekler (Mac)
1. Kapsül yazdıktan sonra `HvacService` CLOSE_PANEL gönder (ayarla kapatılabilir); klima açıkken gücü yeniden gönderme.
2. Müzik kartı kapak resmi ~5 sn'de bir yanıp sönüyor: BT servisi her 5 sn metadata gönderiyor; kapağı sadece şarkı değişince yenile.
3. Favori tuş: Yandex Haritalar seçeneği ekle.
4. Havalandırma yazmasını doğrulandı say (`SEAT_VENT_WRITE_VERIFIED`).
5. Direksiyon ısıtma ve ön cam ısıtma kartlarını bu araçta gizle.
6. Arka hoparlör kartı ses alanını okuyamıyor (`fade=null`).
7. Test sürümünde FrostNav'a bağlı kartlar (Wi-Fi vb.) basınca bir şey yapmıyor: uyarı göster ya da fabrika ayarını aç.
8. Yolculuk kartı "Toplam süre 0 dk" gösteriyor.
9. Bizim alt barı orijinalle hizala (referans: `ekran/ScreenShotCut_2026-11-03_21-03-07.png`).
10. Kadran navigasyon alanı için `nav_area` test komutu (721702 = 9/10/0).

## Yapılmayanlar
Yolcu koltuğu, sürüş modları, tema geçişi ölçümü, gizli özellik yazmaları, kadran yazması.
