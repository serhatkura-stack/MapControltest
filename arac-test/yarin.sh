#!/usr/bin/env bash
# 1 Kasım araç testi yardımcısı. Araca YAZMAZ; yalnızca APK yükler (FrostNav paneline), ölçer ve günlük toplar.
# Widget komut testi, launcher'daki "Widget'lardan araca yaz (test)" anahtarıyla elle yapılır.
# Kullanım:
#   ./yarin.sh baglan      cihaz + FrostNav paneli kontrolü
#   ./yarin.sh yukle       launcher-perf2.apk'yı panele yükle (kurulum araçta: Uygulamalar → İndirilen dosyalar)
#   ./yarin.sh olc ETIKET  tema geçişi + soğuk açılış + kare/işlemci ölçümü (ETIKET: once / sonra / surus)
#   ./yarin.sh gunluk      WidgetWrite + LauncherInfo + ThemeTiming günlüğünü canlı dosyaya yaz (Ctrl-C ile biter)
#   ./yarin.sh kontak      kontak kapat-aç sonrası: launcher önde mi, süreç yeni mi, veri geliyor mu
# Ortam: SERIAL (varsayılan 449671d3)
set -u

SERIAL="${SERIAL:-449671d3}"
ADB_BIN="$HOME/.local/toolchains/android-sdk/platform-tools/adb"
PKG="com.mapcontrol.launchertest"
ACT="$PKG/com.mapcontrol.launcher.HomeLauncherActivity"
APK="/Users/serhat/chery/launcher-perf2.apk"
OUT="/Users/serhat/chery/katalog/loglar/tur4-$(date +%Y%m%d)"
mkdir -p "$OUT"
adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }

case "${1:-}" in
  baglan)
    "$ADB_BIN" devices | grep -q "^$SERIAL[[:space:]]*device" && echo "araç bağlı" || { echo "araç YOK (kablo/kontak?)"; exit 1; }
    adb forward tcp:7462 tcp:7462 >/dev/null
    curl -s -m 5 -o /dev/null -w "panel http %{http_code}\n" http://localhost:7462/ || echo "panel kapalı: FrostNav → Web Yönetimi'ni aç"
    adb shell "dumpsys package $PKG | grep -m1 lastUpdateTime; dumpsys window | grep mCurrentFocus | tail -1"
    ;;
  yukle)
    adb forward tcp:7462 tcp:7462 >/dev/null
    shasum -a 256 "$APK" | cut -c1-16
    curl -s -m 120 -F "file=@$APK;type=application/vnd.android.package-archive" http://localhost:7462/upload; echo
    echo "Araçta: FrostNav → Uygulamalar → İndirilen dosyalar → launcher-perf2.apk → Kur"
    ;;
  olc)
    E="${2:-olcum}"; F="$OUT/olc-$E-$(date +%H%M%S).txt"
    {
      echo "== $E $(date)"
      adb shell "dumpsys package $PKG | grep -m1 lastUpdateTime"
      echo "-- soğuk açılış (3 kez)"
      for i in 1 2 3; do adb shell am force-stop $PKG; sleep 1; adb shell am start -W -n "$ACT" | grep -E "TotalTime|WaitTime"; sleep 3; done
      echo "-- tema geçişi: şimdi gece/gündüz düğmesine 3 kez bas (20 sn)"
      adb logcat -c; adb shell dumpsys gfxinfo $PKG reset >/dev/null
    } | tee "$F"
    sleep 20
    {
      adb logcat -d -s ThemeTiming:I | grep -v beginning
      echo "-- kareler"
      adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|50th|90th|99th|Slow UI"
      echo "-- bellek / işlemci"
      adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|Views:"
      p=$(adb shell pidof $PKG | tr -d '\r'); adb shell "top -b -n 3 -d 2 -p $p | grep -E '^ *$p'"
    } | tee -a "$F"
    echo "kaydedildi: $F"
    ;;
  gunluk)
    F="$OUT/gunluk-$(date +%H%M%S).log"; echo "kayıt: $F (Ctrl-C)"
    adb logcat -c
    adb logcat -s WidgetWrite:V LauncherInfo:V ThemeTiming:V AndroidRuntime:E | tee "$F"
    ;;
  kontak)
    adb shell "dumpsys window | grep mCurrentFocus | tail -1; pidof $PKG; ps -o STIME,NAME -p \$(pidof $PKG)"
    adb logcat -d -s LauncherInfo:I | tail -3
    echo "Ekranda araç kartı/menzil değer gösteriyor mu? (\"Araç verisi bekleniyor\" kalmamalı)"
    ;;
  *)
    sed -n 2,10p "$0"; exit 2 ;;
esac
