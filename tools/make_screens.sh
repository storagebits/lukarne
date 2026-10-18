#!/usr/bin/env bash
# The README's demo GIF and captures, from the release build:   tools/make_screens.sh <step>...
#   shots    stills of the scenes in SHOTS on the device adb sees (the TV emulator by default)  → captures/screens/
#   record   the demo route on the device (LUKARNE_DEVICE=<shield-ip>:5555 for the Shield)      → captures/screens/demo.mp4
#   frames   stills taken out of the recording (FRAMES)                                         → captures/screens/
#   gif      the recording cut into SEGMENTS, 960 px, 12 fps, ≤ 8 MB                            → docs/screens/lukarne-demo.gif
#   update   the update notice against a stub releases/latest (installs the debug build: emulator only)
#   publish  the stills in PUBLISH, ≤ 1 MB each, and the overview sheet                          → docs/screens/
# Live cams change by the minute: a rerun gives other pictures, and the lists below (cam ids, the route, the
# segments) are those of the 2026-10-18 set. Look at every frame before committing: road cams only (no YouTube
# frame), no readable plate or face, no notification, no address (DECISIONS.md, "Public README").
# Needs adb, python3, gifsicle and pngquant (apt/brew install gifsicle pngquant); Pillow and ffmpeg come in a venv
# under build/.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -n "${LUKARNE_DEVICE:-}" ]] && source tools/device.sh
RAW=captures/screens; OUT=docs/screens; mkdir -p "$RAW" "$OUT"
VENV=build/screens-venv
[[ -x "$VENV/bin/python" ]] || { python3 -m venv "$VENV" && "$VENV/bin/pip" -q install Pillow imageio-ffmpeg; }
PY="$VENV/bin/python -I"
FFMPEG="$($PY -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')"
APP=app.lukarne/.MainActivity

# name | launch extras | keys once the screen is up | seconds to wait before the keys
SHOTS=(
  "globe|--es locale en|DPAD_RIGHT|7"
  "wall-truckee|--es locale en --es place california/truckee||30"
  "fs-wrights-lake|--es locale en --es cam caltrans:D3/50_Wrights_Lake_ED50_EB_1|DPAD_UP|14"
  "fs-bay-bridge|--es locale en --es cam caltrans:D4/W80_at_SAS_Tower|DPAD_UP|14"
  "fs-kotka|--es locale en --es cam digitraffic:C0352401|DPAD_UP|14"
)
# The route starts on the region of the device's time zone (Great Lakes for America/New_York): spin to the
# Canaries and back over the Caribbean, dive into New York & New England, Bethany Beach's wall, two cams, back.
ROUTE="DPAD_RIGHT:2.2 DPAD_RIGHT:2.4 DPAD_LEFT:2.4 DPAD_UP:2.4 DPAD_CENTER:2.6 DPAD_DOWN:1.6 DPAD_DOWN:1.8
       DPAD_CENTER:7 DPAD_CENTER:5 DPAD_RIGHT:5 BACK:2.5 BACK:3.5"
# second of the recording → still
FRAMES=("24.4|map-bethany" "25.0|dive" "33.3|fs-indian-river")
# a-b@speed: the globe at 2.4×, the wall filling up at 2×, the cams in real time, back out at 2×
SEGMENTS="2.3-26.0@2.4,27.5-33.0@2,32.9-33.8@1,35.0-36.5@1,38.8-41.0@1,45.5-50.5@2"
# still | file in docs/screens | label on the overview sheet, in the sheet's order
PUBLISH=(
  "globe|lukarne-globe|The globe: every light is a place with live cams"
  "dive|lukarne-dive|Diving into a region"
  "map-bethany|lukarne-places|Places of a region"
  "wall-truckee|lukarne-wall|A wall of live cams (Truckee, Sierra Nevada)"
  "fs-indian-river|lukarne-coast|Indian River Inlet, Delaware"
  "fs-wrights-lake|lukarne-mountain|Highway 50 at Wrights Lake, Sierra Nevada"
  "fs-bay-bridge|lukarne-bridge|The Bay Bridge in the fog, San Francisco"
  "fs-kotka|lukarne-finland|Kotka, Finland (a still every 10 min)"
  "update|lukarne-update|Settings and the update notice"
)

launch() { adb shell am start -S -W -n "$APP" "$@" >/dev/null; }
keys() { for k in "$@"; do adb shell input keyevent "$k"; sleep 1.5; done; }

shots() {
  for s in "${SHOTS[@]}"; do
    IFS='|' read -r name extras keys settle <<<"$s"
    # shellcheck disable=SC2086
    launch $extras; sleep "$settle"
    # shellcheck disable=SC2086
    [[ -n "$keys" ]] && keys $keys
    adb exec-out screencap -p > "$RAW/$name.png"; echo "$RAW/$name.png"
  done
}

record() {
  launch --es locale en; sleep 7
  adb shell screenrecord --bit-rate 20000000 --time-limit 60 /data/local/tmp/lukarne-demo.mp4 & local rec=$!
  sleep 2.5
  for step in $ROUTE; do adb shell input keyevent "${step%%:*}"; sleep "${step#*:}"; done
  adb shell pkill -INT screenrecord; wait "$rec" || true; sleep 1
  adb pull /data/local/tmp/lukarne-demo.mp4 "$RAW/demo.mp4" >/dev/null && adb shell rm /data/local/tmp/lukarne-demo.mp4
  adb shell input keyevent HOME
  echo "$RAW/demo.mp4"
}

frames() {
  for f in "${FRAMES[@]}"; do
    "$FFMPEG" -y -hide_banner -loglevel error -ss "${f%%|*}" -i "$RAW/demo.mp4" -frames:v 1 "$RAW/${f#*|}.png"
    echo "$RAW/${f#*|}.png"
  done
}

gif() {
  $PY tools/screens.py gif "$FFMPEG" "$RAW/demo.mp4" "$RAW/demo.gif" "$SEGMENTS"
  gifsicle -O3 --lossy=80 "$RAW/demo.gif" -o "$OUT/lukarne-demo.gif"
  local size; size=$(wc -c < "$OUT/lukarne-demo.gif")
  echo "$OUT/lukarne-demo.gif: $((size / 1000)) kB"
  (( size <= 8000000 )) || { echo "over 8 MB: shorten SEGMENTS or raise --lossy" >&2; exit 1; }
}

update() {
  [[ "$(adb get-serialno)" == emulator-* ]] || { echo "update installs the debug build: emulator only" >&2; exit 1; }
  local version next stub
  version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
  next="${version%.*}.$(( ${version##*.} + 1 ))"
  stub="$(mktemp -d)"; printf '{"tag_name":"v%s"}\n' "$next" > "$stub/latest.json"
  python3 -m http.server 8000 --bind 127.0.0.1 --directory "$stub" >/dev/null 2>&1 & local http=$!
  ./gradlew -q :app:assembleDebug
  adb uninstall app.lukarne >/dev/null || true   # the release and debug builds have different keys
  adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
  adb reverse tcp:8000 tcp:8000
  launch --es locale en --es updatesUrl http://localhost:8000/latest.json; sleep 10
  keys KEYCODE_MENU; adb exec-out screencap -p > "$RAW/update.png"; echo "$RAW/update.png (v$next offered)"
  kill "$http"; adb reverse --remove tcp:8000; rm -rf "$stub"
  adb uninstall app.lukarne >/dev/null
  ./gradlew -q :app:installRelease   # back to the release build for the other steps
}

publish() {
  local sheet=()
  for p in "${PUBLISH[@]}"; do
    IFS='|' read -r raw file label <<<"$p"
    $PY tools/screens.py png "$RAW/$raw.png" "$OUT/$file.png"
    sheet+=("$OUT/$file.png=$label")
  done
  $PY tools/screens.py sheet "$OUT/lukarne-overview.png" "${sheet[@]}"
}

[[ $# -gt 0 ]] || { sed -n '2,13p' "$0"; exit 1; }
for step in "$@"; do
  case "$step" in
    shots|record|frames|gif|update|publish) "$step" ;;
    *) echo "unknown step $step" >&2; exit 1 ;;
  esac
done
