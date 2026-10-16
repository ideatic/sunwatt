# Progress

## Done
- [x] Step 1: repo, XcodeGen project (app + widget extension, iOS + macOS), local package `HomeDashKit`, core models, client protocols, `Theme`, `Format`, real fixtures, `scripts/verify.sh`.
- [x] Step 2: `FusionSolarKioskClient` + `KioskEndpoint` + `HTMLEntities`; `PVPCClient` + price analysis. Parallel worktrees, merged, 55 tests. Reviewed by 2 fresh Sonnet reviewers.
  - Kiosk review BLOCKING (fixed by me): real plant name/address in `kiosk-night.json` -> anonymized.
  - Should-fix items (cache policy, cancellation, midnight stale curve, kk hidden from descriptions, port, perf, granularity, bad points) -> step 3b.
- [x] Step 3: `AppGroupStore`, `HomeDashLoader`, `TimelineBuilder`, stale flags. Merged, 81 tests. Reviewed.
  - Review BLOCKING: no overall deadline in `load()` (widget ~30 s budget) -> step 3b.
- [x] Localization infra (7 languages: en, es, pt, fr, de, it, nl), `PriceRegion` contract. (New user requests mid-night: multilingual, country choice, kiosk how-to, nice app design, add-widget button, Mac.)

## In progress
- [~] Step 3b: review fixes + multi-country prices (Energy-Charts) + localized errors/regions.
- [~] Step 4: widgets + snapshot pipeline + design iterations (parallel with 3b, separate files).

## Pending
- [ ] Step 5: main app (onboarding, dashboard, settings, kiosk how-to, add-widget guide, Mac) + simulator screenshots
- [ ] Step 6: integration, README, MORNING_REPORT

## Known issues
- macOS signing needs a team (DECISIONS D4).
- No public API to add a widget from the app (checked iOS 27 SDK). App shows a guide and detects installed widgets.
