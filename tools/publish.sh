#!/usr/bin/env bash
# Mirrors this repo's HEAD to the public repo, one commit per publish:   tools/publish.sh [--dry-run] "message"
# The public copy leaves out what is ours only: on-device captures (shots/, which hold Windy images from before
# V1.8), the original brief, the old report and a bug's reproduction notes. This repo's history (Windy catalogues)
# never leaves it. The exported tree is scanned first (tools/publish_scan.py). --dry-run builds the public tree,
# scans it and prints what would change, without committing or pushing.
# Works on macOS and Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
PUBLIC="${LUKARNE_PUBLIC:-https://github.com/storagebits/lukarne.git}"
EXCLUDE=(shots BRIEF.md REPORT.md REPRO.md)
DRY=""; [[ "${1:-}" == "--dry-run" ]] && { DRY=1; shift; }
MSG="${1:?usage: tools/publish.sh [--dry-run] \"message\"}"

[[ -n "$DRY" || -z "$(git status --porcelain)" ]] || { echo "commit first: the public copy is made from HEAD" >&2; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

git clone -q "$PUBLIC" "$WORK/pub" 2>/dev/null || { mkdir -p "$WORK/pub"; git -C "$WORK/pub" init -q -b main; git -C "$WORK/pub" remote add origin "$PUBLIC"; }
git -C "$WORK/pub" rm -rqf --ignore-unmatch . >/dev/null
git archive HEAD | tar -x -C "$WORK/pub"
for x in "${EXCLUDE[@]}"; do rm -rf "${WORK:?}/pub/$x"; done
# The exact tree that would leave and its message, scanned before anything is committed (tools/publish_scan.py).
# Anything but a clean exit stops the publish, dry run included.
# The scanner and the message are the ones of HEAD, so a dry run with local edits checks what would really leave.
python3 -I "$WORK/pub/tools/publish_scan.py" "$WORK/pub" >&2 \
  && printf '%s\n' "$MSG" | python3 -I "$WORK/pub/tools/publish_scan.py" - >&2 \
  || { echo "scan failed: nothing committed, nothing pushed" >&2; exit 1; }

cd "$WORK/pub"
git add -A
if git diff --cached --quiet; then echo "nothing new to publish"; exit 0; fi
if [[ -n "$DRY" ]]; then
  echo "dry run: would commit \"$MSG\" on $PUBLIC main ($(git rev-parse --short HEAD 2>/dev/null || echo 'empty repo') →):"
  git diff --cached --stat | tail -25
  exit 0
fi
# The public history names the owner only by his handle (board rule 2026-10-21), whatever this machine's git identity.
git -c user.name=storagebits -c user.email=48120561+storagebits@users.noreply.github.com commit -qm "$MSG"
git push -q origin HEAD:main
echo "published $(git rev-parse --short HEAD) to $PUBLIC"
