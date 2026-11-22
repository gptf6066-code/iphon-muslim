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
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell appops set $P PROJECT_MEDIA allow
adb push face1.jpg /sdcard/Pictures/face1.jpg
adb push face2.jpg /sdcard/Pictures/face2.jpg
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/face1.jpg
adb logcat -c
adb shell am start -n $P/.MainActivity; sleep 4; shot 01_app
tap_text "Фаъол кардан" a; sleep 3; shot 02_after_tap
tap_text "^ ?(Start|Start now|Начать|Оғоз)" b; sleep 3; shot 03_after_consent
tap_text "^ ?(Start|Start now|Начать)" c; sleep 2
adb shell am start -n $P/.MainActivity; sleep 2; shot 04_status
# show test image in a viewer
adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Pictures/face1.jpg -t image/jpeg; sleep 5; shot 05_view_file
adb shell am start -a android.intent.action.VIEW -d https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/lena.jpg; sleep 8; shot 06_view_web
tap_text "(Accept|No thanks|Use without)" d; sleep 3; tap_text "(No thanks|No, thanks)" e; sleep 5; shot 07_view_web2
adb shell pm list packages | sort > results/packages.txt
adb logcat -d -s IphonMuslim:* > results/applog.txt
adb logcat -d -b crash > results/crash.txt
adb logcat -d | grep -i -E "iphonmuslim|MediaProjection" | tail -200 > results/related.txt
true
