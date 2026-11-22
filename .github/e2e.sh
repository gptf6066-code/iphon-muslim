#!/bin/bash
set -x
P=tj.bikhon.iphonmuslim
mkdir -p results
shot() { adb exec-out screencap -p > results/$1.png; }
tap_text() {  # tap a UI element whose text/desc matches regex $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml results/ui_$2.xml >/dev/null 2>&1
  B=$(python3 - "$1" results/ui_$2.xml <<'PY'
import re,sys
pat=re.compile(sys.argv[1],re.I); x=open(sys.argv[2],encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    t=(re.search(r' text="([^"]*)"',n) or [0,''])[1]+' '+(re.search(r'content-desc="([^"]*)"',n) or [0,''])[1]
    if pat.search(t):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups(); b=list(map(int,b))
        print((b[0]+b[2])//2,(b[1]+b[3])//2); break
PY
)
  echo "tap $1 -> $B"; [ -n "$B" ] && adb shell input tap $B
}
adb install -r iphon-muslim.apk
adb shell wm size 720x1440; adb shell wm density 320
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb push face1.jpg /sdcard/Pictures/woman1.jpg
adb push .github/astronaut.png /sdcard/Pictures/woman2.png
adb push face2.jpg /sdcard/Pictures/man1.jpg
adb logcat -c
# --- A: танҳо Специальные возможности (бе сабти экран) ---
adb shell settings put secure enabled_accessibility_services $P/$P.FilterAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 4
adb shell am start -n $P/.MainActivity; sleep 3; shot 01_app
view() { adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Pictures/$1 -t image/$2; }
view woman1.jpg jpeg; sleep 5; shot 02_woman1
view man1.jpg jpeg; sleep 5; shot 03_man1
view woman2.png png; sleep 5; shot 04_woman2
# --- B: ҳолати тез ---
adb shell appops set $P PROJECT_MEDIA allow
adb shell am start -n $P/.MainActivity; sleep 3
tap_text "Ҳолати тез" a; sleep 3; shot 05_consent
tap_text "^ ?(Start|Start now|Начать)" b; sleep 3
adb shell am start -n $P/.MainActivity; sleep 2; shot 06_fast_status
view woman1.jpg jpeg; sleep 4; shot 07_fast_woman1
view man1.jpg jpeg; sleep 4; shot 08_fast_man1
adb logcat -d -s IphonMuslim:* > results/applog.txt
adb logcat -d -b crash > results/crash.txt
true
