#!/usr/bin/env bash
# Sourced by the other scripts: connects to the Shield and exports ANDROID_SERIAL.
# Env: LUKARNE_DEVICE, the Shield's address and ADB port (required), e.g. LUKARNE_DEVICE=<shield-ip>:5555.
DEV="${LUKARNE_DEVICE:-}"
[[ -n "$DEV" ]] || { echo "set LUKARNE_DEVICE to your Shield's address, e.g. LUKARNE_DEVICE=<shield-ip>:5555 (Settings → Device Preferences → About → Status)" >&2; exit 1; }
[[ "$DEV" == *:* ]] || DEV="$DEV:5555"
HOST="${DEV%:*}"; PORT="${DEV#*:}"
RELAY_PORT=15555
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if adb connect "$DEV" 2>&1 | grep -q '^connected\|already connected' && adb -s "$DEV" get-state >/dev/null 2>&1; then
  export ANDROID_SERIAL="$DEV"
else
  # macOS Local Network privacy can block adb itself ("No route to host"): relay through Apple's nc.
  if ! pgrep -f "adb-relay.sh" >/dev/null; then
    SHIELD_HOST="$HOST" SHIELD_PORT="$PORT" LOCAL_PORT="$RELAY_PORT" nohup "$here/adb-relay.sh" >/dev/null 2>&1 &
    sleep 1
  fi
  adb connect "127.0.0.1:$RELAY_PORT" >/dev/null
  export ANDROID_SERIAL="127.0.0.1:$RELAY_PORT"
  adb get-state >/dev/null 2>&1 || { echo "Shield unreachable at $DEV (is it awake, with Network debugging on?)" >&2; exit 1; }
fi
