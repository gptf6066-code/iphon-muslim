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
# --- видеои намоишӣ барои Google Play (эъломияи Accessibility) ---
adb shell screenrecord --bit-rate 6000000 --time-limit 170 /sdcard/demo.mp4 &
REC=$!
sleep 1
# Экрани «Дар бораи барнома» → Идома → Фаъол кардан → огоҳӣ → Розӣ ҳастам → танзимот
adb shell am start -n $P/$C.MainActivity; sleep 6; shot 00_about
tap_text "^ ?Идома" ab; sleep 3; shot 00b_main
tap_text "^ ?Фаъол кардан" en; sleep 4; shot 00c_disclosure
adb shell input swipe 360 1000 360 400 800; sleep 2
tap_text "Розӣ ҳастам" ag; sleep 4; shot 00d_a11y_settings
# дар саҳифаи Специальные возможности: калид → Allow
tap_text "^Muslim Shield" sw; sleep 3
tap_text "(Use Muslim Shield|Use service)" us; sleep 3; shot 00e_a11y_dialog
tap_text "^ ?(Allow|Разрешить|OK)" al; sleep 4; shot 00f_a11y_on
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
# агар бо UI нашуд — бо фармон
adb shell settings get secure enabled_accessibility_services | grep -q muslimshield || {
  adb shell settings put secure enabled_accessibility_services $P/$C.FilterAccessibilityService
  adb shell settings put secure accessibility_enabled 1; }
adb shell appops set $P PROJECT_MEDIA allow
sleep 4
tap_text "^ ?(Start|Start now|Начать)" a; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -n $P/$C.MainActivity; sleep 4; shot 01_app_tg
view woman2.png png; sleep 7; shot 02a_demo_astronaut
adb shell am start -n $P/$C.MainActivity; sleep 4
kill $REC 2>/dev/null; sleep 3; adb shell pkill -INT screenrecord; sleep 3
adb pull /sdcard/demo.mp4 results/demo.mp4 || true
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
tap_text "Танҳо ҷои худи шахс" m; sleep 1
view woman2.png png; sleep 6; shot 10_person_woman2
adb shell am start -n $P/$C.MainActivity; sleep 2
tap_text "Тамоми сурат ё видео" n; sleep 1
# корбари зан: мардҳо хира шаванд
tap_text "Зан ҳастам" w; sleep 1
view man1.jpg jpeg; sleep 6; shot 12_women_user_man1
view woman1.jpg jpeg; sleep 6; shot 13_women_user_woman1
adb shell am start -n $P/$C.MainActivity; sleep 2
tap_text "Мард ҳастам" mm; sleep 1
tap_text "&#127760;" lg; sleep 2; shot 14_lang_dialog
tap_text "فارسی" t; sleep 4; shot 11_app_fa
adb logcat -d -s IphonMuslim:* > results/applog.txt
adb logcat -d -b crash > results/crash.txt
true
pkill -f "http.server" || true
