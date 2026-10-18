#!/usr/bin/env bash
# Regression check for "Quit lukarne does nothing": drives the installed build with the D-pad and checks that OK on
# the leave sheet's Quit row really leaves the app, in each scenario, N times. Captures of failures go to
# captures/quit-check/ (gitignored). Runs on whatever adb sees (the TV emulator); --shield goes through device.sh.
#   tools/quit-check.sh [rounds=5] [--shield]
# Scenarios:
#   plain     BACK, DOWN, DOWN, OK.
#   home      BACK (sheet open), HOME, back into lukarne, DOWN, DOWN, OK: the screens remount behind the open sheet
#             (LocalSession) and used to take the remote from it; every key then went to the globe.
#   longback  BACK held (key repeats), DOWN, DOWN, OK: the repeats used to close the sheet as soon as it opened.
set -uo pipefail
cd "$(dirname "$0")/.."
ROUNDS="${1:-5}"
[[ "${2:-}" == "--shield" ]] && source tools/device.sh
OUT=captures/quit-check; mkdir -p "$OUT"
t() { timeout 30 "$@"; }
key() { t adb shell input keyevent "$@"; sleep 0.3; }
launch() { t adb shell am start -W -n app.lukarne/.MainActivity >/dev/null; }
# Who is on top; an empty answer means adb lost the device, which must not count as a quit.
topActivity() {
  local top; top=$(t adb shell dumpsys activity activities | grep -m1 topResumedActivity)
  [[ -n "$top" ]] || { echo "adb lost the device: aborting" >&2; exit 2; }
  echo "$top"
}

run() {   # run <scenario> <round>: 0 when OK on Quit left the app
  t adb shell am force-stop app.lukarne
  launch; sleep 7   # splash (2.2 s) and the globe settling
  case "$1" in
    plain)    key BACK ;;
    home)     key BACK; key HOME; sleep 2; launch; sleep 2 ;;
    longback) key --longpress BACK ;;
  esac
  key DPAD_DOWN; key DPAD_DOWN
  key DPAD_CENTER; sleep 2
  local top; top=$(topActivity) || exit 2
  if grep -q app.lukarne <<<"$top"; then t adb exec-out screencap -p > "$OUT/$1-$2-stayed.png"; return 1; fi
}

fail=0
for s in plain home longback; do
  ok=0
  for r in $(seq 1 "$ROUNDS"); do run "$s" "$r" && ok=$((ok + 1)); done
  echo "$s: $ok/$ROUNDS quit"
  [[ $ok -eq $ROUNDS ]] || fail=1
done
[[ $fail -eq 0 ]] && echo "PASS" || echo "FAIL (captures in $OUT)"
exit $fail
