#!/usr/bin/env python3
"""olcum.sh klasörünü okuyup ana bulguları yazdırır. Kullanım: python3 -I ozet.py [klasör]
Klasör verilmezse katalog/loglar/tur2-*/olcum-* içindeki en yenisi alınır."""
import glob
import os
import re
import sys

LOGROOT = "/Users/serhat/chery/katalog/loglar"
OURS = "com.mapcontrol.launchertest"
FRAME = re.compile(r"(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
WIN = re.compile(r"Window\{[0-9a-f]+ u\d+ (.+?)\}")


def read(folder, name):
    try:
        with open(os.path.join(folder, name), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def latest():
    c = sorted(glob.glob(os.path.join(LOGROOT, "tur2-*", "olcum-*")))
    return c[-1] if c else None


def head(t):
    print("\n== " + t + " ==")


def parse_windows(text):
    wins = []
    for blk in re.split(r"\n(?=\s*Window #\d+ )", text):
        m = WIN.search(blk)
        if not m:
            continue
        fr = FRAME.search(blk)
        ty = re.search(r"\bty=(\w+)", blk)
        wins.append({
            "name": m.group(1),
            "type": ty.group(1) if ty else "?",
            "frame": tuple(int(x) for x in fr.groups()) if fr else None,
            "text": blk,
        })
    return wins


def fmt(w):
    f = w["frame"]
    return "%s  type=%s  frame=%s" % (w["name"], w["type"], "[%d,%d][%d,%d]" % f if f else "?")


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else latest()
    if not folder or not os.path.isdir(folder):
        print("Klasör yok: %s" % folder)
        return 1
    print("Klasör:", folder)

    size_t = read(folder, "wm-size.txt")
    dens_t = read(folder, "wm-density.txt")
    head("Ekran")
    print(size_t.strip() or "(wm size yok)")
    print(dens_t.strip() or "(wm density yok)")
    ms = re.findall(r"size: (\d+)x(\d+)", size_t)
    W, H = (int(ms[-1][0]), int(ms[-1][1])) if ms else (0, 0)

    wins = parse_windows(read(folder, "dumpsys-window-windows.txt"))
    head("Pencereler (%d)" % len(wins))
    if not wins:
        print("(ayrıştırılamadı)")

    head("Alt ~105px'i kaplayanlar (OEM çubuğu)")
    found = False
    for w in wins:
        f = w["frame"]
        if f and H and f[3] >= H - 2 and f[1] >= H - 160 and (f[3] - f[1]) <= 200:
            print(" ", fmt(w))
            found = True
    if not found:
        print("(bulunamadı; H=%s)" % H)

    head("Üstteki pencereler (durum çubuğu / sesli asistan)")
    found = False
    for w in wins:
        f = w["frame"]
        isbar = w["type"] in ("STATUS_BAR", "NOTIFICATION_SHADE", "VOICE_INTERACTION", "VOICE_INTERACTION_STARTING")
        voice = re.search(r"voice|assist|speech|vr", w["name"], re.I)
        if f and ((f[1] <= 2 and (f[3] - f[1]) <= 200) or isbar or voice):
            print(" ", fmt(w))
            found = True
    if not found:
        print("(yok)")

    head("Sistem çubuğu türleri")
    for w in wins:
        if w["type"] in ("NAVIGATION_BAR", "STATUS_BAR"):
            print(" ", fmt(w))

    head("Launcher penceresi ve bildirilen inset'ler (%s)" % OURS)
    ours = [w for w in wins if OURS in w["name"]]
    if not ours:
        print("(launcher penceresi yok; ön planda değil mi?)")
    for w in ours:
        print(" ", fmt(w))
        n = 0
        for line in w["text"].splitlines():
            if re.search(r"inset|InsetsSource|stable|content|visible", line, re.I) and n < 20:
                print("    " + line.strip()[:200])
                n += 1
    ins = read(folder, "insets.txt")
    if ins:
        print("  -- genel InsetsSource satırları --")
        for line in [l for l in ins.splitlines() if "InsetsSource" in l][:12]:
            print("    " + line.strip()[:200])

    head("Ekranlar (dumpsys display)")
    disp = read(folder, "dumpsys-display.txt")
    seen = 0
    for line in disp.splitlines():
        if "DisplayDeviceInfo{" in line or re.search(r"mBaseDisplayInfo=DisplayInfo\{", line):
            print("  " + line.strip()[:230])
            seen += 1
    if not seen:
        print("(satır yok)")

    head("Bildirim dinleyicileri")
    notif = read(folder, "dumpsys-notification.txt")
    comps = []
    on = False
    for line in notif.splitlines():
        if re.search(r"listeners", line, re.I) and line.rstrip().endswith(":") or re.search(r"listeners \(\d+\)", line, re.I):
            on = True
            print("  " + line.strip()[:150])
            continue
        if on and "ComponentInfo{" in line:
            comps.append(line.strip())
        elif on and line.strip() and not line.startswith("    "):
            on = False
    for c in comps[:30]:
        print("    " + c[:200])
    navi = "com.desaysv.ivi.vds.navi"
    print("  %s: %s" % (navi, "VAR (dinleyici listesinde)" if any(navi in c for c in comps)
                          else ("notification dökümünde geçiyor" if navi in notif else "YOK")))
    sec = read(folder, "settings-secure.txt")
    for line in sec.splitlines():
        if line.startswith("enabled_notification_listeners="):
            print("  secure enabled_notification_listeners:", line.split("=", 1)[1][:300])

    head("launchertest sürümü")
    print(read(folder, "launchertest-surum.txt").strip()[:600] or "(yok)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
