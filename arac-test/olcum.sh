#!/usr/bin/env bash
# Araç ölçüm betiği (2. tur, Tiggo 7). Yalnızca OKUMA komutları çalıştırır:
# kurma/kaldırma, "settings put", "am broadcast", "input" ve cihaza yazma YOK.
# Kullanım:
#   ./olcum.sh            tek seferlik ölçüm (klasöre kaydeder)
#   ./olcum.sh tarama     launcher'ın salt-okur taramasını başlat + LauncherProbe/ClusterCard kaydı (Ctrl-C ile biter)
#   ./olcum.sh gosterge   gösterge kartı denemesi + ClusterCard kaydı (Ctrl-C ile biter)
#   ./olcum.sh tarama --devam   kablo takılınca: uygulamayı yeniden başlatmadan, son tarama klasörüne
#                               aynı etiket filtresiyle logcat'i yeniden bağlar (dosyaya ekler)
# Ortam: SERIAL (varsayılan 449671d3), ADB (varsayılan platform-tools/adb), TIMEOUT (sn, varsayılan 20)
set -u

SERIAL="${SERIAL:-449671d3}"
ADB_BIN="${ADB:-$HOME/.local/toolchains/android-sdk/platform-tools/adb}"
TIMEOUT="${TIMEOUT:-20}"
PKG="com.mapcontrol.launchertest"
ACT="com.mapcontrol.launcher.HomeLauncherActivity"
BASE="/Users/serhat/chery/katalog/loglar/tur2-$(date +%Y%m%d)"
LOGROOT="/Users/serhat/chery/katalog/loglar"

if [ ! -x "$ADB_BIN" ]; then
  echo "adb bulunamadı: $ADB_BIN" >&2
  exit 1
fi

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }

DEVAM=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--devam" ]; then DEVAM=1; else ARGS+=("$a"); fi
done
MODE="olcum"
if [ "${#ARGS[@]}" -gt 0 ]; then MODE="${ARGS[0]}"; fi
case "$MODE" in
  olcum | tarama | gosterge) ;;
  *) echo "Kullanım: $0 [olcum|tarama|gosterge] [--devam]" >&2; exit 2 ;;
esac

if [ "$DEVAM" = 1 ]; then
  if [ "$MODE" = "olcum" ]; then
    echo "--devam yalnızca tarama/gosterge ile kullanılır." >&2
    exit 2
  fi
  # En son açılmış aynı moddaki klasörü bul (gün değişmiş olabilir diye tüm tur2-* klasörlerine bakar).
  OUT="$(ls -dt "$LOGROOT"/tur2-*/"$MODE"-* 2>/dev/null | head -n 1)"
  if [ -z "$OUT" ] || [ ! -d "$OUT" ]; then
    echo "Devam edilecek $MODE klasörü yok. Önce: $0 $MODE" >&2
    exit 1
  fi
  echo "Devam: mevcut klasöre eklenecek: $OUT"
else
  OUT="$BASE/$MODE-$(date +%H%M%S)"
  mkdir -p "$OUT" || exit 1
  echo "Klasör: $OUT"
fi

# Cihaz görünüyor mu? (yalnızca bilgi; sonuç ne olursa olsun devam)
"$ADB_BIN" devices -l >"$OUT/adb-devices.txt" 2>&1
if ! grep -q "^$SERIAL[[:space:]]" "$OUT/adb-devices.txt"; then
  echo "UYARI: $SERIAL adb devices listesinde yok (bkz. adb-devices.txt)" >&2
fi

# run_cmd <dosya> <komut...>: çıktıyı dosyaya yazar, TIMEOUT sn sonra öldürür, asla betiği durdurmaz.
run_cmd() {
  local name="$1"
  shift
  local pid watcher rc
  "$@" >"$OUT/$name" 2>&1 &
  pid=$!
  ( sleep "$TIMEOUT"; kill "$pid" 2>/dev/null ) &
  watcher=$!
  wait "$pid" 2>/dev/null
  rc=$?
  kill "$watcher" 2>/dev/null
  wait "$watcher" 2>/dev/null
  if [ "$rc" -ne 0 ]; then
    printf '\n[olcum.sh] çıkış kodu %s (143/137 = zaman aşımı)\n' "$rc" >>"$OUT/$name"
    echo "  ! $name (kod $rc)"
  else
    echo "  ok $name"
  fi
  return 0
}

# Çıktıyı yerelde kısaltır (cihazda boru kullanmadan).
trim() { # <dosya> <satır>
  local tmp="$OUT/$1.tmp"
  head -n "$2" "$OUT/$1" >"$tmp" 2>/dev/null && mv "$tmp" "$OUT/$1"
  return 0
}

stream_log() { # <dosya> <logcat etiketleri...>
  local name="$1"
  shift
  echo "Kayıt: $OUT/$name  (durdurmak için Ctrl-C)"
  trap 'echo; echo "Durduruldu. Klasör: $OUT"; exit 0' INT TERM
  adb logcat -v time -s "$@" >>"$OUT/$name" 2>&1
  echo
  echo "!! logcat sona erdi: kablo çıkmış ya da cihaz bağlantısı kopmuş olabilir."
  echo "   Kabloyu takıp aynı klasöre devam etmek için:  $0 $MODE --devam"
  echo "   (uygulama yeniden başlatılmaz; probe 3 saat boyunca açık kalır)"
  echo "Klasör: $OUT"
}

if [ "$MODE" = "tarama" ]; then
  if [ "$DEVAM" != 1 ]; then
    run_cmd am-start.txt adb shell am start -n "$PKG/$ACT" --ez launcher_probe true
  fi
  stream_log tarama.log LauncherProbe:I ClusterCard:I
  exit 0
fi

if [ "$MODE" = "gosterge" ]; then
  if [ "$DEVAM" != 1 ]; then
    run_cmd am-start.txt adb shell am start -n "$PKG/$ACT" --ez launcher_cluster_test true
  fi
  stream_log gosterge.log ClusterCard:I
  exit 0
fi

echo "Ölçüm başlıyor (yalnızca okuma)..."
run_cmd getprop.txt            adb shell getprop
run_cmd wm-size.txt            adb shell wm size
run_cmd wm-density.txt         adb shell wm density
run_cmd dumpsys-display.txt    adb shell dumpsys display
run_cmd dumpsys-window-windows.txt adb shell dumpsys window windows
run_cmd dumpsys-window.txt     adb shell dumpsys window
grep -iE "InsetsSource|navigation|statusbar|status_bar|inset" "$OUT/dumpsys-window.txt" >"$OUT/insets.txt" 2>/dev/null || true
run_cmd dumpsys-activity.txt   adb shell dumpsys activity activities
trim dumpsys-activity.txt 400
run_cmd pm-list-packages.txt   adb shell pm list packages -f
run_cmd dumpsys-package-launchertest.txt adb shell dumpsys package "$PKG"
grep -iE "versionCode|versionName|firstInstallTime|lastUpdateTime|flags=" "$OUT/dumpsys-package-launchertest.txt" >"$OUT/launchertest-surum.txt" 2>/dev/null || true
run_cmd dumpsys-notification.txt adb shell dumpsys notification
run_cmd settings-global.txt    adb shell settings list global
run_cmd settings-secure.txt    adb shell settings list secure
run_cmd settings-system.txt    adb shell settings list system
run_cmd dumpsys-media_session.txt adb shell dumpsys media_session
run_cmd surfaceflinger-list.txt adb shell dumpsys SurfaceFlinger --list
run_cmd logcat.txt             adb logcat -d -b main -v time

echo "Bitti: $OUT"
echo "Özet için: python3 -I $(dirname "$0")/ozet.py \"$OUT\""
