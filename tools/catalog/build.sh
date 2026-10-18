#!/usr/bin/env bash
# Regenerates app/src/main/assets/catalog.json. Creates the pipeline's venv on first run.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -x "$here/.venv/bin/python" ]]; then
  python3 -m venv "$here/.venv"
  "$here/.venv/bin/pip" install -q -r "$here/requirements.txt"
fi
exec "$here/.venv/bin/python" -I "$here/build.py" "$@"
