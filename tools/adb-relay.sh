#!/usr/bin/env bash
# macOS "Local Network" privacy blocks adb (and Homebrew python) from reaching LAN hosts ("No route to host"),
# while Apple-signed /usr/bin/nc is allowed. This relays 127.0.0.1:$LOCAL_PORT -> $SHIELD so that
#   adb connect 127.0.0.1:15555
# works. Restarts after each connection ends. Ctrl-C to stop.
SHIELD_HOST="${SHIELD_HOST:?set SHIELD_HOST to the Shield's address (tools/device.sh does it from LUKARNE_DEVICE)}"; SHIELD_PORT="${SHIELD_PORT:-5555}"; LOCAL_PORT="${LOCAL_PORT:-15555}"
FIFO="$(mktemp -u -t adbrelay)"; mkfifo "$FIFO"; trap 'rm -f "$FIFO"' EXIT
while :; do
  /usr/bin/nc -l 127.0.0.1 "$LOCAL_PORT" < "$FIFO" | /usr/bin/nc "$SHIELD_HOST" "$SHIELD_PORT" > "$FIFO"
  sleep 0.2
done
