# Yarının planı (1 Kasım): launcher'ı paylaşıma hazır etmek

Hedef: akşama "her widget veri alıyor, kontrol widget'ları araca komut gönderiyor, Tiggo 8 için hazır" diyebilmek.
**Ben** = Mac, **Sen** = araç.

## 1. Sabah, masada — ✅ 31 Ekim gecesi yapıldı
APK: `launcher-perf2.apk`, commit `fa281bc`, SHA-256 `3ed5a20d572ee8fe…`. Fork'a push edildi.
Büyük ekran: 2560x1440 @160/240/320 emülatörde düzgün (ölçek uyarlaması). Kalan küçük not: 1920x720'de müzik kartında ilerleme çubuğu sanatçı adına çok yakın.

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

Her adımda: çalışıyor / çalışmıyor / yanlış değer. Fotoğrafları çek, AirDrop'la.

## 3. Öğleden sonra, masada (Ben, ~1–2 sa)
1. Araç testinde çalışmayanları düzelt: komut kodlaması yanlış çıkanlar (koltuk ısıtma 0–3 / 1–4?, kilit 2?, buğu kapatma 2?, bagaj VR 121 / CS 92), veri gelmeyen widget'lar.
2. Doğrulanan komutların "doğrulandı" bayraklarını aç; gerçek uygulamada kilit için park kontrolünü ekle.
3. Düzeltmeler varsa **ikinci kısa araç turu** (yalnız düzeltilenler, ~15 dk).

## 4. Akşam: paylaşım hazırlığı
1. **Tiggo 8 paketi:** APK + kısa kurulum notu (FrostNav paneli → İndirilen dosyalar → Kur) + "Ayarlar → Veri kontrolü → Paylaş" ile geri bildirim isteği.
2. Önce **tek bir Tiggo 8 sahibine** gönder; düzen ve veri sorunlarını düzelt.
3. Sorun yoksa **WhatsApp "devs" grubu** mesajı (taslağı ben hazırlarım, sen gönderirsin).
4. **Berk Bey'e mesaj taslağı:** "Launcher PR olarak hazır, insanlar denesin; beğenirseniz launcher modunuzun yerine koyalım; Tiggo 8'de de çalışıyor; ileride FrostNav tasarım dilini parça parça güncelleyebilirim." Gönderme yalnız senin onayınla.

## Kapsam dışı (yarın değil)
Navigasyon/HUD, FrostNav yeniden tasarımı, tema paketleri, yolculuk kartı revizyonu, performans 3. tur.
