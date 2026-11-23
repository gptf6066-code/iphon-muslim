#!/bin/bash
set -x
P=tj.bikhon.muslimshield
C=tj.bikhon.iphonmuslim
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
  if [ -z "$B" ] && [ -z "$RETRY" ]; then sleep 3; RETRY=1 tap_text "$1" "$2"; RETRY=1 && [ -z "$B" ] && { sleep 3; RETRY=1 tap_text "$1" "$2"; }; return; fi
  echo "tap $1 -> $B"; [ -n "$B" ] && adb shell input tap $B
}
adb install -r iphon-muslim.apk
adb shell wm size 720x1440; adb shell wm density 320
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb push face1.jpg /sdcard/Pictures/woman1.jpg
adb push .github/astronaut.png /sdcard/Pictures/woman2.png
adb push face2.jpg /sdcard/Pictures/man1.jpg
adb push .github/grid.png /sdcard/Pictures/grid.png
adb logcat -c
view() { adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Pictures/$1 -t image/$2; }
# Экрани «Дар бораи барнома» → Идома → Фаъол кардан → огоҳӣ → Розӣ ҳастам
adb shell am start -n $P/$C.MainActivity; sleep 5; shot 00_about
tap_text "^ ?ادامه" ab; sleep 3; shot 00b_main
tap_text "فعال کردن" en; sleep 3; shot 00c_disclosure
tap_text "موافقم" ag; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 1
# Ҳолати тез асосӣ аст: бо фаъол шудани Специальные возможности худкор оғоз мешавад
adb shell appops set $P PROJECT_MEDIA allow
adb shell settings put secure enabled_accessibility_services $P/$C.FilterAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 5
tap_text "^ ?(Start|Start now|Начать)" a; sleep 3
adb shell am start -n $P/$C.MainActivity; sleep 4; shot 01_app_fa
view woman1.jpg jpeg; sleep 5; shot 02_woman1
view man1.jpg jpeg; sleep 5; shot 03_man1
view woman2.png png; sleep 5; shot 04_woman2
# --- лента дар Chrome + скролл ---
(cd .github/feed && python3 -m http.server 8000 >/dev/null 2>&1 &)
adb shell true
adb shell 'echo "_ --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
adb shell am set-debug-app --persistent com.android.chrome >/dev/null 2>&1
adb shell am start -a android.intent.action.VIEW -d http://10.0.2.2:8000/index.html; sleep 8
tap_text "(Accept|No thanks|Use without|Got it)" d; sleep 2; tap_text "(No thanks|No, thanks|Got it)" e; sleep 2
adb shell input swipe 360 1200 360 700 600; sleep 4; shot 05_feed_a
for i in 1 2 3 4 5 6; do
  (sleep 0.35; adb exec-out screencap -p > results/06_scroll_${i}_mid.png) &
  adb shell input swipe 360 1150 360 750 700
  sleep 0.15; shot 06_scroll_${i}_end
  sleep 1.2
done
shot 07_feed_settled
# --- намуди «танҳо ҷои зан» ---
adb shell am start -n $P/$C.MainActivity; sleep 3
tap_text "فقط جای خود شخص" m; sleep 1
view woman2.png png; sleep 6; shot 10_person_woman2
adb shell am start -n $P/$C.MainActivity; sleep 2
tap_text "کل عکس یا ویدیو" n; sleep 1
# корбари зан: мардҳо хира шаванд
tap_text "زن هستم" w; sleep 1
view man1.jpg jpeg; sleep 6; shot 12_women_user_man1
view woman1.jpg jpeg; sleep 6; shot 13_women_user_woman1
adb shell am start -n $P/$C.MainActivity; sleep 2
tap_text "مرد هستم" mm; sleep 1
tap_text "&#127760;" lg; sleep 2; shot 14_lang_dialog
tap_text "Тоҷикӣ" t; sleep 4; shot 11_app_tg
adb logcat -d -s IphonMuslim:* > results/applog.txt
adb logcat -d -b crash > results/crash.txt
true
pkill -f "http.server" || true
