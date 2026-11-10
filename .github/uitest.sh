#!/usr/bin/env bash
# Drives the dev app on the emulator for plus-uitest.yml. See that file for the step language.
set -u
PKG=org.indodev.smarttubeplus.dev
mkdir -p out
n=0

adb install -r app.apk || { echo "install failed"; exit 1; }
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
sleep 20

IFS=';' read -ra STEP_LIST <<< "${STEPS:-shot start}"
for raw in "${STEP_LIST[@]}"; do
  step=$(echo "$raw" | xargs)
  [ -z "$step" ] && continue
  cmd=${step%% *}
  arg=${step#* }
  case "$cmd" in
    key)
      name=${arg%% *}
      times=1
      [ "$name" != "$arg" ] && times=${arg##* }
      for _ in $(seq 1 "$times"); do adb shell input keyevent "KEYCODE_$name"; sleep 0.7; done
      ;;
    hold) adb shell input keyevent --longpress "KEYCODE_$arg"; sleep 1 ;;
    text) adb shell input text "${arg// /%s}"; sleep 1 ;;
    sleep) sleep "$arg" ;;
    shot)
      n=$((n + 1))
      adb exec-out screencap -p > "out/$(printf '%02d' "$n")-$arg.png"
      ;;
    *) echo "unknown step: $step" ;;
  esac
done

adb logcat -d > out/logcat-full.txt
# The parts worth reading: crashes, and what TV mode logged.
grep -E "FATAL EXCEPTION|AndroidRuntime|TvMode" out/logcat-full.txt | tail -300 > out/logcat.txt
grep -A 30 "FATAL EXCEPTION" out/logcat-full.txt | tail -120 > out/crash.txt
gzip -f out/logcat-full.txt
ls -l out
exit 0
