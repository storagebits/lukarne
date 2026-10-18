#!/usr/bin/env bash
# Ships the version in app/build.gradle.kts: build, check the signature, mirror the code, publish the GitHub release.
#   tools/release.sh [--draft] [--dry-run] notes.md     (bump versionCode and versionName and commit first)
#   --draft    the release is created as a draft: nothing is visible (and releases/latest is unchanged) until it is
#              published on GitHub
#   --dry-run  build and check the APK, show what the mirror would get; no push, no release
# Every release carries the APK twice: lukarne-X.Y.Z.apk, and lukarne.apk for the stable link the app shows
# (Updates.DOWNLOAD_URL: …/releases/latest/download/lukarne.apk). The notes get the APK's SHA-256 and the
# certificate's appended. Works on macOS and Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
DRAFT=""; DRY=""
while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --draft) DRAFT=1 ;;
    --dry-run) DRY=1 ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
NOTES="${1:?usage: tools/release.sh [--draft] [--dry-run] notes.md}"
[[ -f "$NOTES" ]] || { echo "no notes file $NOTES" >&2; exit 1; }
REPO=storagebits/lukarne
CERT=1d6ff424ff4f7c6eba45bd4db03208710b9ca150a55d56608b0bb8febe269a62   # SHA-256 of the release certificate
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
TAG="v$VERSION"
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
gh release view "$TAG" -R "$REPO" >/dev/null 2>&1 && { echo "$TAG is already released (or drafted): bump versionName" >&2; exit 1; }

./gradlew -q :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
"$(ls -d "$SDK"/build-tools/* | tail -1)/apksigner" verify --print-certs "$APK" | grep -q "SHA-256 digest: $CERT" \
    || { echo "the APK is not signed with the release key (release.storeFile in local.properties?)" >&2; exit 1; }
SUM="$(sha256 "$APK")"

OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
cp "$APK" "$OUT/lukarne-$VERSION.apk"; cp "$APK" "$OUT/lukarne.apk"
{ cat "$NOTES"; printf '\n%s\n' "---" "" "\`lukarne-$VERSION.apk\` (same file as \`lukarne.apk\`): SHA-256 \`$SUM\`" "" \
    "Signing certificate SHA-256: \`$CERT\`"; } > "$OUT/notes.md"

if [[ -n "$DRY" ]]; then
  tools/publish.sh --dry-run "lukarne $VERSION"
  echo "dry run: would create ${DRAFT:+a draft of }release $TAG on $REPO with lukarne-$VERSION.apk and lukarne.apk, notes:"
  sed 's/^/  | /' "$OUT/notes.md"
  echo "APK SHA-256: $SUM"
  exit 0
fi
tools/publish.sh "lukarne $VERSION"
gh release create "$TAG" "$OUT/lukarne-$VERSION.apk" "$OUT/lukarne.apk" -R "$REPO" --target main ${DRAFT:+--draft} \
    --title "lukarne $VERSION" --notes-file "$OUT/notes.md"
echo "APK SHA-256: $SUM"
