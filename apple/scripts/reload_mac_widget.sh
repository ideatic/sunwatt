#!/bin/bash
# SunWatt: make the Mac desktop widget use the build you just ran from Xcode.
# macOS keeps the old widget process (and its old code) alive after a rebuild, so the widget looks stuck.
# This stops it, restarts the widget service (chronod, it starts again by itself) and opens the app,
# which reloads the widgets. Old copies of the app registered elsewhere (worktrees, archives) are unregistered.
# Usage: scripts/reload_mac_widget.sh [path/to/SunWatt.app]   (default: the Xcode Debug build)
set -euo pipefail
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
APP="${1:-$(ls -d ~/Library/Developer/Xcode/DerivedData/SunWatt-*/Build/Products/Debug/SunWatt.app 2>/dev/null | head -n 1)}"
[[ -d "$APP" ]] || { echo "No SunWatt.app found. Build the Mac app in Xcode first, or pass its path." >&2; exit 1; }

"$LSREGISTER" -dump 2>/dev/null | sed -nE 's/^path: +(.*\/SunWatt\.app)( \(0x[0-9a-f]+\))?$/\1/p' | sort -u | while read -r other; do
  [[ "$other" == "$APP" || "$other" == *CoreSimulator* ]] && continue
  "$LSREGISTER" -u "$other" 2>/dev/null || true
  echo "Unregistered old copy: $other"
done
"$LSREGISTER" -f -R "$APP"

pkill -f "SunWattWidgets.appex/Contents/MacOS/SunWattWidgets" || true
killall chronod 2>/dev/null || true
sleep 2
open "$APP"
echo "Widget reloaded from: $APP"
