#!/bin/zsh
# Regenerates the project, then builds iOS Simulator + macOS and runs all tests.
# Usage: scripts/verify.sh [quick]   (quick = swift test only)
set -euo pipefail
cd "$(dirname "$0")/.."
SIM="${HOMEDASH_SIM:-platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0}"
DD="${HOMEDASH_DD:-build/dd}"

echo "== swift test"
LOG="$(mktemp -t homedash-test)"
if ! (cd Packages/HomeDashKit && swift test >"$LOG" 2>&1); then
  grep -E "recorded an issue|error:" "$LOG" | head -n 40
  tail -n 3 "$LOG"
  echo "swift test FAILED (full log: $LOG)"
  exit 1
fi
tail -n 1 "$LOG"
[[ "${1:-}" == "quick" ]] && exit 0

echo "== xcodegen"
xcodegen generate --quiet

FAILED=0
# Runs xcodebuild, prints only errors and the result line, records failure.
run() {
  local out
  out="$(xcodebuild "$@" 2>&1)" || FAILED=1
  print -r -- "$out" | grep -E "error:|\*\* (BUILD|TEST)|Test run with" || true
}

echo "== iOS Simulator build + test"
run -project HomeDash.xcodeproj -scheme HomeDash -destination "$SIM" -derivedDataPath "$DD" test

echo "== macOS build (unsigned: macOS App Groups need a team, see DECISIONS.md D4)"
run -project HomeDash.xcodeproj -scheme HomeDash -destination 'platform=macOS' -derivedDataPath "$DD" build CODE_SIGNING_ALLOWED=NO

echo "== watchOS build"
run -project HomeDash.xcodeproj -scheme HomeDashWatch -destination 'generic/platform=watchOS Simulator' -derivedDataPath "$DD" build

if (( FAILED )); then echo "VERIFY FAILED"; exit 1; fi
echo "VERIFY OK"
