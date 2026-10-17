#!/bin/bash
# SunWatt: upload App Store texts (and optionally screenshots) with fastlane deliver.
#
# Usage:
#   scripts/upload_metadata.sh                      Dry run (default). Checks AppStore/metadata and prints what
#                                                   would be uploaded. Does not contact Apple. No key needed.
#   scripts/upload_metadata.sh --upload             Real upload. Runs fastlane deliver. No binary, no review
#                                                   submission, no automatic release.
#   --screenshots <dir>                             Also upload screenshots. deliver reads <dir>/<locale>/*.png
#                                                   (locale folders like en-US, es-ES). Default: no screenshots.
#   --platform ios|osx|both                         Default both. macOS needs its own deliver run.
#   -h, --help                                      Show this text.
#
# Auth (upload only): App Store Connect API key as a fastlane JSON file.
#   Path: env ASC_API_KEY_PATH (default private/appstore_key.json, git-ignored). See docs/RELEASE.md.
# The key file is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_ID=com.ideatic.sunwatt
METADATA=AppStore/metadata
KEY_PATH="${ASC_API_KEY_PATH:-private/appstore_key.json}"

MODE=dry-run
PLATFORM=both
SHOTS=""
while (( $# )); do
  case "$1" in
    --upload) MODE=upload ;;
    --screenshots)
      [[ $# -ge 2 ]] || { echo "--screenshots needs a directory" >&2; exit 2; }
      SHOTS="$2"; shift ;;
    --platform)
      [[ $# -ge 2 ]] || { echo "--platform needs ios, osx or both" >&2; exit 2; }
      PLATFORM="$2"; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

case "$PLATFORM" in
  ios) PLATFORMS=(ios) ;;
  osx) PLATFORMS=(osx) ;;
  both) PLATFORMS=(ios osx) ;;
  *) echo "Unknown platform: $PLATFORM (use ios, osx or both)" >&2; exit 2 ;;
esac

if [[ -n "$SHOTS" && ! -d "$SHOTS" ]]; then
  echo "Screenshots folder not found: $SHOTS" >&2
  exit 1
fi

echo "== check metadata"
python3 -I AppStore/check_metadata.py "$METADATA"

LOCALES=()
for dir in "$METADATA"/*/; do
  LOCALES+=("$(basename "$dir")")
done

echo "App identifier: $APP_ID"
echo "Platforms:      ${PLATFORMS[*]}"
echo "Locales (${#LOCALES[@]}):    ${LOCALES[*]}"
if [[ -n "$SHOTS" ]]; then
  echo "Screenshots:    yes, from $SHOTS (overwrites the screenshots in App Store Connect)"
  if ! compgen -G "$SHOTS/*/" >/dev/null; then
    echo "Warning: $SHOTS has no locale subfolders. deliver expects $SHOTS/<locale>/*.png." >&2
  fi
else
  echo "Screenshots:    no (skip_screenshots true)"
fi
echo "Binary:         not uploaded. No review submission. No automatic release."

if [[ "$MODE" != upload ]]; then
  echo "MODE: DRY RUN. Nothing was sent to Apple. Run with --upload to upload."
  exit 0
fi

if ! command -v fastlane >/dev/null 2>&1; then
  echo "fastlane is not installed. Run: brew install fastlane" >&2
  exit 1
fi
if [[ ! -f "$KEY_PATH" ]]; then
  cat >&2 <<MSG
API key file not found: $KEY_PATH
Create it:
  1. App Store Connect > Users and Access > Integrations > App Store Connect API > Team Keys.
  2. Generate a key with the App Manager role. Download the .p8 file once (Apple shows it only once).
  3. Write the JSON file (key_id, issuer_id, the .p8 text in "key"). See docs/RELEASE.md.
Or set ASC_API_KEY_PATH to another JSON file.
MSG
  exit 1
fi

echo "MODE: UPLOAD. Sends the texts to App Store Connect (app $APP_ID)."
for platform in "${PLATFORMS[@]}"; do
  echo "== fastlane deliver ($platform)"
  ARGS=(
    deliver
    --api_key_path "$KEY_PATH"
    --app_identifier "$APP_ID"
    --platform "$platform"
    --metadata_path "$METADATA"
    --skip_binary_upload true
    --submit_for_review false
    --automatic_release false
    --precheck_include_in_app_purchases false
    --force true
  )
  if [[ -n "$SHOTS" ]]; then
    # One folder per platform: iPhone + iPad shots for ios, Mac shots for osx. deliver picks the device by pixel
    # size and would otherwise try to put the Mac shots on the iOS version.
    PLATFORM_SHOTS="build/screenshots-$platform"
    rm -rf "$PLATFORM_SHOTS"
    for dir in "$SHOTS"/*/; do
      locale="$(basename "$dir")"
      mkdir -p "$PLATFORM_SHOTS/$locale"
      if [[ "$platform" == osx ]]; then
        cp "$dir"*_mac_*.png "$PLATFORM_SHOTS/$locale/"
      else
        cp "$dir"*_iphone*_*.png "$dir"*_ipad*_*.png "$PLATFORM_SHOTS/$locale/"
      fi
    done
    ARGS+=(--skip_screenshots false --screenshots_path "$PLATFORM_SHOTS" --overwrite_screenshots)
  else
    ARGS+=(--skip_screenshots true)
  fi
  fastlane "${ARGS[@]}"
done
echo "DONE. Texts are in App Store Connect. Nothing was submitted for review."
