# Araç testi 3. tur: sıralı liste

Hazırlık: P konumu, kontak açık, USB bağlı. Bağlantıyı ben kurarım: `adb forward tcp:7462`, sonra APK'yı panele yüklerim.
APK: `~/chery/launcher-test.apk`, commit f12884a, SHA-256 `db1e3361eb1d…`.
**Ben** = Mac, **Sen** = araç, 📷 = fotoğraf.
Her adımda iki seçenek var: "çalışıyor" ya da "gereksiz, silelim".

## A. Kurulum (5 dk)
1. **Ben:** APK'yı panele gönderirim. **Sen:** FrostNav → Uygulamalar → İndirilen dosyalar → `launcher-test (…).apk` → Kur.
2. **Ben:** Bildirim erişimini kontrol ederim (güncellemede kalır). Gerekirse `cmd notification allow_listener …` ile veririm.
3. **Ben:** Taramayı başlatırım: `./olcum.sh tarama`.

## B. Veri kontrolü ekranı (10 dk, araca hiçbir şey yazmaz)
4. **Sen:** Ayarlar → **Veri kontrolü**. 📷 ilk hali çek. Yeşil nokta veri geliyor, gri veri yok, sarı geçersiz değer demek.
5. **Sen:** Aşağıdakileri sırayla yap, her birinde ilgili satırın değeri değişiyor mu bak:
   - Kapı aç/kapa.
   - Kilitle/aç.
   - Sürüş modunu değiştir.
   - Koltuk ısıtmayı aç, sonra soğutmayı aç.
   - Klima sıcaklığını ve fanı değiştir.
   - Tavan perdesini aç/kapa.
   - Ön ve arka buğuyu aç/kapa.
   - Direksiyon ısıtmayı aç/kapa.
   - Bir camı aç.
   - Kaputu ve yakıt kapağını aç (mümkünse).
   - Vitesi **P → R → N → D → P** yap.
   - El frenini çek/indir.
   - Telefonu kablosuz şarja koy.
6. **Sen:** "Paylaş/Kaydet". **Ben:** dosyayı `adb pull` ile alırım.

## C. Widget'lar, sırayla ekle ve bak (15 dk)
7. **Ekrana bak:** Gündüz/gece ve alt çubuğun "Bizimki" hali. 📷
8. **Yeni widget'lar:** Kalem → Widget ekle, sırayla şunları ekle:
   - Saat, Akü, Gövde, Mola, Hız limiti, Telefon, 4x4 güç dağılımı.
   - Tavan perdesi, Buğu, Direksiyon ısıtma. Bunlar şimdilik yalnızca gösterir; değiştirmezler.
   - Fotoğraf, Ekranı karart, Nereye/Ev/İş.
   Her birinde: veri doğru mu? 📷
9. **Fotoğraf:** **Ben** panelden bir resim yüklerim. **Sen** widget'a dokun → resmi seç. İzin sorarsa ver, sonra "Yenile".
10. **Ekranı karart:** Dokun, ekran kararır; tekrar dokun, geri gelir.
11. **Nereye/Ev/İş:** "Ev"e basılı tut. Konum izni sorarsa ver, tekrar basılı tut, "Ev kaydedildi" çıkmalı. Sonra "Ev"e dokun, Yandex rota açıyor mu?
12. **Koltuk:** En yükseğe al, kutucukta **"Yüksek"** yazmalı.

## D. Sürüşte (15 dk)
13. **Park/Sürüş:** Harekete geç. ~5 sn sonra ekran **Sürüş** düzenine geçmeli. Kırmızı ışıkta Sürüş'te kalmalı. P'ye alınca (ya da 3 dk durunca) Park'a dönmeli.
14. **4x4 güç dağılımı:** Sert gaz ver. Bizim widget ile göstergedeki 4x4 animasyonunu karşılaştır.
15. **Anlık tüketim ve yolculuk:** Göstergedeki değerlerle aynı mı?
16. **Mola:** "x dk sürüş" yazısı süreyle artıyor mu?

## E. Yazma testleri (yalnızca P, hız 0, onayınla)
17. **Tavan perdesi süresi (okuma):** **Ben** `shade_time` başlatırım. **Sen** perdeyi elle TAM aç, sonra TAM kapat.
18. **Konfor yazma:** **Ben** gizli test ekranından sırayla gönderirim, **sen** ne olduğunu söylersin:
    - perde aç / kapa;
    - "dur" adayları (3, 6, 0);
    - ön buğu, arka buğu, direksiyon ısıtma (aç ve kapa).
    Komutlar: kontrol-listesi.md → "Konfor yazma testi".
19. **HUD:** `info` → `demo` → gerekirse `alt` → ok numaraları (2, 3, 4, 5, 8, 9) → `clear`. Her birinde 📷 HUD.

## F. Bitiş
20. **Ben:** Taramayı durdurur, kayıtları alırım. **Sen:** Yansıt açıksa durdur.
21. **Birlikte:** Hangi widget kalsın, hangisi silinsin?
