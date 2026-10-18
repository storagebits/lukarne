#!/usr/bin/env bash
# Device loop: install, launch, wait, capture shots/<name>.png, print suspicious log lines.
#   tools/shot.sh <name> [am-start extras...]     Env: SKIP_INSTALL=1, NO_LAUNCH=1, SETTLE=seconds
set -euo pipefail
NAME="$1"; shift || true
cd "$(dirname "$0")/.."
source tools/device.sh
[[ -z "${SKIP_INSTALL:-}" ]] && ./gradlew -q installDebug
if [[ -z "${NO_LAUNCH:-}" ]]; then
  adb logcat -c
  adb shell am start -S -W -n app.lukarne/.MainActivity "$@" | grep -E 'TotalTime' || true
fi
sleep "${SETTLE:-5}"
mkdir -p shots
adb exec-out screencap -p > "shots/$NAME.png"
echo "captured shots/$NAME.png"
PID=$(adb shell pidof app.lukarne | tr -d '\r' || true)
{
  [[ -n "$PID" ]] && adb logcat -d --pid="$PID" -s 'Lukarne.Hls:*' 'Lukarne.YT:*' 'Lukarne.Health:*' 'Lukarne:*' | tail -15
  adb logcat -d | grep -E 'FATAL EXCEPTION|ANR in app.lukarne|Process: app.lukarne' || true
} | sed '/^$/d' | tail -25
