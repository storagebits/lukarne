#!/usr/bin/env bash
# Soak test: the in-app autopilot browses globe → place → mosaic → fullscreen → back → surprise, in a loop, while
# memory is sampled. Writes shots/soak/soak.csv and start/end captures; prints crashes and the memory trend.
#   tools/soak.sh [minutes=22] [sample_every_s=120]
set -uo pipefail
cd "$(dirname "$0")/.."
source tools/device.sh
MIN="${1:-22}"; EVERY="${2:-120}"
OUT=shots/soak; mkdir -p "$OUT"
t() { perl -e 'alarm 30; exec @ARGV' "$@"; }
ROUND="right,right,ok,right,left,ok,wait,right,down,ok,wait,right,right,left,wait,back,back,back,play,wait,right,back,back,back,gc,left"
ROUNDS=$(( MIN * 60 / (26 * 2) + 1 ))
t adb logcat -b crash -c; t adb logcat -c
t adb shell am start -S -n app.lukarne/.MainActivity --es autopilot "$ROUND" --ei autopilotDelay 2000 --ei autopilotRepeat "$ROUNDS" >/dev/null
sleep 20
t adb exec-out screencap -p > "$OUT/soak-start.png"
echo "time,elapsed_s,pid,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,threads,crash_lines" > "$OUT/soak.csv"
start=$(date +%s); end=$(( start + MIN * 60 ))
while :; do
  mem=$(t adb shell dumpsys meminfo app.lukarne 2>/dev/null)
  field() { echo "$mem" | awk -v k="$1" '$0 ~ k {for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) {print $i; exit}}'; }
  pid=$(t adb shell pidof app.lukarne | tr -d '\r')
  thr=$(t adb shell "ls /proc/$pid/task 2>/dev/null | wc -l" | tr -d '\r ')
  crashes=$(t adb logcat -b crash -d | grep -c 'app.lukarne')
  now=$(date +%s)
  echo "$(date +%H:%M:%S),$(( now - start )),${pid:-none},$(field 'TOTAL PSS:'),$(field 'Java Heap:'),$(field 'Native Heap:'),$(field 'Graphics:'),$thr,$crashes" | tee -a "$OUT/soak.csv"
  [[ $now -ge $end ]] && break
  sleep "$EVERY"
done
t adb exec-out screencap -p > "$OUT/soak-end.png"
echo "--- live heap after GC, once per round (a leak shows as a steady climb):"
t adb logcat -d -s Lukarne.Autopilot | grep "memory after gc" | awk '{print $1" "$2" "$(NF-3)" "$(NF-2)" "$(NF-1)" "$NF}' | tee "$OUT/gc.txt"
echo "--- crashes/ANRs:"; t adb logcat -b crash -d | grep -E 'FATAL|app.lukarne' | head -5 || true
t adb logcat -d | grep -E 'ANR in app.lukarne' | head -3 || true
echo "done: $OUT/soak.csv"
