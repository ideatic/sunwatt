#!/bin/zsh
# SunWatt release: archive iOS (with the embedded watch app) and macOS, then export.
#
# Usage:
#   scripts/release.sh             Safe mode (default, same as --dry-run). Archives and exports to build/export/.
#                                  Nothing is uploaded. No -allowProvisioningUpdates, so nothing is registered in
#                                  the Apple Developer account. It stops with "No profiles for ..." until the IDs
#                                  and profiles exist (the first --upload run creates them).
#   scripts/release.sh --dry-run   Same as the default.
#   scripts/release.sh --upload    Real release. Passes -allowProvisioningUpdates (registers the App IDs, the App
#                                  Group and the profiles in the Apple Developer account that Xcode is signed in
#                                  to) and uploads both builds to App Store Connect
#                                  (scripts/ExportOptions-AppStore.plist, destination upload).
#   --no-bump                      Keep the current build number.
#
# Steps: xcodegen generate -> set CURRENT_PROJECT_VERSION in project.yml to YYYYMMDDHHmm -> regenerate ->
#        xcodebuild archive (iOS, macOS) into build/archives/ -> xcodebuild -exportArchive.
# The build number lives in ONE place: CURRENT_PROJECT_VERSION in project.yml. Edit MARKETING_VERSION there by hand.
set -euo pipefail
cd "$(dirname "$0")/.."
# Xcode packages the .ipa with /usr/bin/rsync, which starts "rsync" from PATH as its other side. A Homebrew rsync
# (3.x) first in PATH rejects its options ("--extended-attributes: unknown option") and the export fails with
# "Copy failed". Apple's tools first; Homebrew stays reachable for xcodegen.
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

MODE=dry-run
BUMP=1
for arg in "$@"; do
  case "$arg" in
    --upload) MODE=upload ;;
    --dry-run) MODE=dry-run ;;
    --no-bump) BUMP=0 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

ARCHIVES=build/archives
EXPORT=build/export
mkdir -p "$ARCHIVES" "$EXPORT"
PROV=()
if [[ "$MODE" == upload ]]; then
  OPTIONS=scripts/ExportOptions-AppStore.plist
  PROV=(-allowProvisioningUpdates)
  echo "MODE: UPLOAD. Registers App IDs, the App Group and profiles in the Apple Developer account (team EX68NKELW9)"
  echo "      and uploads the iOS and macOS builds to App Store Connect."
else
  OPTIONS=scripts/ExportOptions-AppStore-Export.plist
  echo "MODE: DRY RUN. Archives and exports to $EXPORT/ only. No upload, no account changes."
  echo "      Signing needs profiles that already exist. Without them it stops with 'No profiles for ...'."
fi

echo "== xcodegen"
xcodegen generate --quiet

if (( BUMP )); then
  BUILD="$(date +%Y%m%d%H%M)"
  sed -i '' -E "s/^( *CURRENT_PROJECT_VERSION: ).*/\1\"${BUILD}\"/" project.yml
  grep -q "CURRENT_PROJECT_VERSION: \"${BUILD}\"" project.yml || { echo "Could not set the build number in project.yml" >&2; exit 1; }
  xcodegen generate --quiet
  echo "== build number: $BUILD"
else
  echo "== build number: unchanged"
fi

archive() { # name destination
  echo "== archive $1"
  rm -rf "$ARCHIVES/$1.xcarchive"
  xcodebuild archive -project SunWatt.xcodeproj -scheme SunWatt -configuration Release \
    -destination "$2" -archivePath "$ARCHIVES/$1.xcarchive" "${PROV[@]}"
}
export_archive() { # name
  echo "== export $1 ($MODE)"
  xcodebuild -exportArchive -archivePath "$ARCHIVES/$1.xcarchive" \
    -exportOptionsPlist "$OPTIONS" -exportPath "$EXPORT/$1" "${PROV[@]}"
}

archive iOS 'generic/platform=iOS'
archive macOS 'generic/platform=macOS'
# iOS indexes the App Intents (widget configuration, Siri) by symbols in each binary; a stripped one leaves widgets as
# placeholders (DECISIONS D26). Stop before uploading such a build.
scripts/check_appintents_symbols.sh "$ARCHIVES/iOS.xcarchive" "$ARCHIVES/macOS.xcarchive"
export_archive iOS
export_archive macOS

if [[ "$MODE" == upload ]]; then
  echo "DONE. Both builds went to App Store Connect. Wait for the processing e-mail, then use TestFlight."
else
  echo "DONE (dry run). Exported to $EXPORT/. Nothing was uploaded."
fi
