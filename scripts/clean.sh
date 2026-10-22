#!/bin/sh
# Deletes build outputs, caches and renders that the next build or render makes again.
# Usage: scripts/clean.sh   (from any folder; run it when a session ends, AGENTS.md "Working conventions")
# Never touches private/, tracked files or the generated Xcode project (Xcode may have it open).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

# Several sessions share this folder: do nothing while one of them builds or renders.
if pgrep -f "xcodebuild|swift-build|swift-test|SunWattSnapshots|SunWattWatchSnapshots|GradleWrapperMain" >/dev/null; then
    echo "CLEAN SKIPPED: a build is running" >&2
    exit 1
fi

before=$(du -sk . | cut -f1)

# Apple: build folders, SwiftPM caches, screenshot renders (git-ignored; the ITERATIONS.md logs stay).
rm -rf apple/build apple/Packages/SunWattKit/.build apple/Packages/SunWattKit/.swiftpm
find apple/Screenshots -name "*.png" -type f -delete 2>/dev/null || true

# Android: Gradle and Kotlin outputs of every module, and the fastlane run files.
rm -rf android/.gradle android/.kotlin android/build android/build-logic/build android/build-logic/.gradle
for module in app core ui-shared wear; do
    rm -rf "android/$module/build"
done
rm -f android/fastlane/report.xml android/fastlane/README.md

# Finder files anywhere outside git's own folder.
find . -name .DS_Store -not -path "./.git/*" -type f -delete

after=$(du -sk . | cut -f1)
echo "CLEAN OK: freed $(( (before - after) / 1024 )) MB"
