#!/bin/zsh
# Araçtaki FrostNav'ı bizim yamalı 1.4.3 + launcher derlemesiyle değiştirmek.
#   ./frostnav-degistir.sh kontrol   → hiçbir şey değiştirmez; silmeden önceki 2 güvenlik kontrolü
#   ./frostnav-degistir.sh kur       → FrostNav SİLİNDİKTEN SONRA bizim APK'yı kurar + Berk'in install.sh izinleri
# Silme adımı bu betikte YOK: FrostNav'ın kendi "kaldır" düğmesiyle yapılır (cihaz sahibi yetkisini o bırakır).
set -u
ADB=~/.local/toolchains/android-sdk/platform-tools/adb
GOMME=/Users/serhat/chery-embed
LT_APK=/Users/serhat/chery/launcher-perf2.apk   # bugünkü test APK'sı; deneme kurulumu bunu kurar
OUR_APK=$GOMME/app/build/outputs/apk/debug/app-debug.apk

case "${1:-}" in
kontrol)
  echo "== 0. Bağlantı"; $ADB devices | sed -n 2p
  echo "== 1. Araçtaki FrostNav sürümü"; $ADB shell dumpsys package com.mapcontrol | grep -m2 -E "versionName|versionCode"
  echo "== 2. Cihaz sahibi (FrostNav mı?)"; $ADB shell dumpsys device_policy | grep -iE -m3 "Device Owner|admin=|package="
  echo "== 3. Hesaplar (0 olmalı, yoksa cihaz sahibi geri alınamaz)"; $ADB shell dumpsys account | grep -E "Accounts:|Account \{" | head -8
  echo "== 4. adb shell pm install yolu çalışıyor mu (Launcher Test'i bugünkü APK ile kurar; panelden kurmaya gerek kalmaz)"
  $ADB push -q "$LT_APK" /data/local/tmp/lt-deneme.apk && echo "pm install -r /data/local/tmp/lt-deneme.apk" | $ADB shell
  $ADB shell rm -f /data/local/tmp/lt-deneme.apk
  echo "Sonuç: 4'te 'Success' ve 3'te hesap yoksa değiştirmek güvenli."
  ;;
kur)
  [ -f "$OUR_APK" ] || { echo "Yamalı APK yok: $OUR_APK"; exit 1; }
  $ADB shell pm path com.mapcontrol | grep -q package && { echo "FrostNav hâlâ kurulu. Önce FrostNav içinden kaldır."; exit 1; }
  # Berk'in install.sh'ı release APK yolunu bekliyor; yamalı debug APK'yı oraya koyup onu çalıştırıyoruz.
  mkdir -p $GOMME/app/build/outputs/apk/release
  cp "$OUR_APK" $GOMME/app/build/outputs/apk/release/app-release.apk
  (cd $GOMME && PATH="${ADB:h}:$PATH" bash ./install.sh)
  ;;
*) echo "Kullanım: $0 kontrol | kur"; exit 1 ;;
esac
