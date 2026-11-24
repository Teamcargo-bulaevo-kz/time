#!/usr/bin/env bash
# Дымовой тест в эмуляторе: ставим APK, запускаем сканирование, забираем отчёт, ищем падения.
set -u
PKG=kz.bulaevo.audit
OUT=smoke-out
mkdir -p "$OUT"
adb install -r out/Audit.apk 2>&1 | tee "$OUT/install.log"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" --ez autoscan true </dev/null
for i in $(seq 1 40); do
  sleep 5
  if adb shell ls /sdcard/Android/data/$PKG/files/last-report.json </dev/null >/dev/null 2>&1; then break; fi
done
sleep 3
adb exec-out screencap -p </dev/null > "$OUT/result.png"
adb pull /sdcard/Android/data/$PKG/files/last-report.json "$OUT/emulator-report.json" </dev/null || echo "NO REPORT"
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "CRASH DETECTED"; grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; exit 1
fi
[ -f "$OUT/emulator-report.json" ] || exit 1
echo "OK"
