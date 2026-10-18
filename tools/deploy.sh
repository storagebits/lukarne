#!/usr/bin/env bash
# One command: build, install on the Shield, launch.   tools/deploy.sh [debug]
# Release by default: R8-minified Compose is ~10× faster per frame than debug on the Shield.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/device.sh
if [[ "${1:-}" == "debug" ]]; then ./gradlew -q installDebug; else ./gradlew -q installRelease; fi
adb shell am start -S -n app.lukarne/.MainActivity >/dev/null
echo "lukarne running on $ANDROID_SERIAL"
