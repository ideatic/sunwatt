#!/bin/zsh
# Checks that iOS/macOS can index the App Intents of a built app (DECISIONS D26).
#
# Usage: scripts/check_appintents_symbols.sh <path to .app or .xcarchive> [...]
#
# Each bundle (app, extension, watch app) that lists App Intents packages in Metadata.appintents/extract.packagedata
# ("includes": mangled type names such as 10SunWattKit0abC14IntentsPackageV) must export the type descriptor
# symbol of each one (_$s<name>Mn) from its own binary. On install, iOS (linkd) looks the package up by that symbol;
# when the binary was stripped of it, iOS drops all App Intents of the app: widgets stay placeholders and Siri
# shortcuts fail. Nothing is hard-coded: new or renamed packages and new targets are checked too.
# Prints one line per checked bundle; exits 1 when a symbol is missing or nothing was found to check.
set -euo pipefail

(( $# )) || { echo "usage: $0 <app or archive> [...]" >&2; exit 2; }

checked=0
failed=0
for root in "$@"; do
  [[ -d "$root" ]] || { echo "FAILED: $root does not exist" >&2; exit 1; }
  while IFS= read -r -d '' packagedata; do
    # The bundle that owns this metadata: the nearest enclosing .app or .appex.
    bundle="${packagedata:h}"
    while [[ "$bundle" != *.app && "$bundle" != *.appex && "$bundle" != "/" ]]; do bundle="${bundle:h}"; done
    [[ "$bundle" == "/" ]] && { echo "FAILED: no bundle around $packagedata" >&2; failed=1; continue; }
    if [[ -f "$bundle/Contents/Info.plist" ]]; then
      binary="$bundle/Contents/MacOS/$(plutil -extract CFBundleExecutable raw -o - "$bundle/Contents/Info.plist")"
    else
      binary="$bundle/$(plutil -extract CFBundleExecutable raw -o - "$bundle/Info.plist")"
    fi
    # grep reads all of nm's output (no -q): under pipefail an early exit would fail nm with SIGPIPE.
    exported="$(nm -gU "$binary" | awk '{ print $NF }')"
    i=0
    while name="$(plutil -extract "includes.$i" raw -o - "$packagedata" 2>/dev/null)"; do
      symbol="_\$s${name}Mn"
      if print -r -- "$exported" | grep -Fx -- "$symbol" >/dev/null; then
        echo "ok      ${bundle:t}: $name"
      else
        echo "FAILED  ${bundle:t}: $symbol is not exported by ${binary:t} (stripped? see STRIP_STYLE in project.yml)" >&2
        failed=1
      fi
      checked=$((checked + 1))
      i=$((i + 1))
    done
  done < <(find "$root" -path '*/Metadata.appintents/extract.packagedata' -print0)
done

(( checked )) || { echo "FAILED: no App Intents package includes found under $*" >&2; exit 1; }
exit $failed
