#!/usr/bin/env bash
# Frame stats while the in-app autopilot presses keys (see Autopilot.kt). Measure the release build
# (tools/deploy.sh): debug Compose is ~10x slower per frame on the Shield.
#   tools/jank.sh "<steps>" [am-start extras...]      e.g. tools/jank.sh "right right down left" --es place alps/venice
# Restarts the app, lets it settle, resets gfxinfo just before the autopilot starts, prints stats when it is done.
set -uo pipefail
cd "$(dirname "$0")/.."
source tools/device.sh
t() { perl -e 'alarm 20; exec @ARGV' "$@"; }
STEPS="$1"; shift
n=$(wc -w <<<"$STEPS"); DELAY=${DELAY:-400}
t adb shell am start -S -n app.lukarne/.MainActivity --es autopilot "${STEPS// /,}" --ei autopilotDelay "$DELAY" "$@" >/dev/null
sleep 5.2
t adb shell dumpsys gfxinfo app.lukarne reset >/dev/null
sleep "$(echo "0.5 + $n * $DELAY / 1000 + 1" | bc -l)"
t adb shell dumpsys gfxinfo app.lukarne | grep -E "Total frames|Janky|90th percentile|99th percentile|Slow UI|deadline" | grep -v gpu | tr -s ' ' | tr '\n' ' '
echo "cpu=$(t adb shell top -b -n 1 | awk '/app.lukarne/ {print $9}')%"
