#!/bin/zsh
# Regenerates the project, then builds iOS Simulator + macOS and runs all tests.
# Usage: scripts/verify.sh [quick]   (quick = swift test only)
# Environment: SUNWATT_SIM (iOS Simulator destination), SUNWATT_DD (derived data folder),
# SUNWATT_XCODEBUILD_ARGS (extra xcodebuild arguments, e.g. CODE_SIGNING_ALLOWED=NO on CI).
set -euo pipefail
cd "$(dirname "$0")/.."
SIM="${SUNWATT_SIM:-platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0}"
DD="${SUNWATT_DD:-build/dd}"
EXTRA=(${=SUNWATT_XCODEBUILD_ARGS:-})
# The debug prefill build step declares private/kiosk.local as an input (script sandboxing), so it must exist.
# An empty file means "no prefill" (fresh clones, CI).
[[ -f private/kiosk.local ]] || { mkdir -p private && : > private/kiosk.local; }

echo "== swift test"
# Full logs of every step stay in build/logs (uploaded by CI).
mkdir -p build/logs
LOG="$PWD/build/logs/swift-test.log"
if ! (cd Packages/SunWattKit && swift test >"$LOG" 2>&1); then
  grep -E "recorded an issue|error:" "$LOG" | head -n 40 || true
  tail -n 3 "$LOG"
  echo "swift test FAILED (full log: $LOG)"
  exit 1
fi
tail -n 1 "$LOG"
[[ "${1:-}" == "quick" ]] && exit 0

echo "== xcodegen"
xcodegen generate --quiet

FAILED=0
STEP=0
# Runs xcodebuild, prints only errors and the result line, records failure. Full output: build/logs/xcodebuild-<n>.log.
run() {
  local out
  STEP=$((STEP + 1))
  out="$(xcodebuild "$@" "${EXTRA[@]}" 2>&1)" || FAILED=1
  print -r -- "$out" > "build/logs/xcodebuild-$STEP.log"
  print -r -- "$out" | grep -E "error:|recorded an issue|\*\* (BUILD|TEST)|Test run with" || true
}

echo "== iOS Simulator build + test"
run -project SunWatt.xcodeproj -scheme SunWatt -destination "$SIM" -derivedDataPath "$DD" test

echo "== macOS build (unsigned: macOS App Groups need a team, see DECISIONS.md D4)"
run -project SunWatt.xcodeproj -scheme SunWatt -destination 'platform=macOS' -derivedDataPath "$DD" build CODE_SIGNING_ALLOWED=NO

echo "== watchOS build"
run -project SunWatt.xcodeproj -scheme SunWattWatch -destination 'generic/platform=watchOS Simulator' -derivedDataPath "$DD" build

if (( FAILED )); then echo "VERIFY FAILED"; exit 1; fi
echo "VERIFY OK"
