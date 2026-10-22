## What changes

<!-- One or two sentences. -->

## Both apps (FEATURES.md)

- [ ] The feature is built in both apps (`apple/` and `android/`), or the other app's row in `FEATURES.md` is `planned` with the reason.
- [ ] `FEATURES.md` is updated (new row, changed status or notes).
- [ ] Shared contracts kept (stored raw values, `sunwatt://` links, provider contracts, limits, time zones), or changed in both apps.
- [ ] New texts are in the `.xcstrings` catalogs with all 7 languages (Android strings are generated from them).
- [ ] Parser changes are tested against the shared fixtures in both apps.
- [ ] `python3 -I scripts/check_parity.py` passes.

## Checks

- [ ] `apple/scripts/verify.sh` → VERIFY OK (when `apple/` changed)
- [ ] Android build and tests pass (when `android/` changed)
