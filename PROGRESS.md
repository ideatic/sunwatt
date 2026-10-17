# Progress

## Done
- [x] Step 1: repo, XcodeGen project (app + widget extension, iOS + macOS), local package `HomeDashKit`, core models, client protocols, `Theme`, `Format`, real fixtures, `scripts/verify.sh`.
- [x] Step 2: `FusionSolarKioskClient` + `KioskEndpoint` + `HTMLEntities`; `PVPCClient` + price analysis. Parallel worktrees, merged, 55 tests. Reviewed by 2 fresh Sonnet reviewers.
  - Kiosk review BLOCKING (fixed by me): real plant name/address in `kiosk-night.json` -> anonymized.
  - Should-fix items (cache policy, cancellation, midnight stale curve, kk hidden from descriptions, port, perf, granularity, bad points) -> step 3b.
- [x] Step 3: `AppGroupStore`, `HomeDashLoader`, `TimelineBuilder`, stale flags. Merged, 81 tests. Reviewed.
  - Review BLOCKING: no overall deadline in `load()` (widget ~30 s budget) -> step 3b.
- [x] Localization infra (7 languages: en, es, pt, fr, de, it, nl), `PriceRegion` contract. (New user requests mid-night: multilingual, country choice, kiosk how-to, nice app design, add-widget button, Mac.)

- [x] Step 3b: all review fixes (deadline, cancellation, cache policy, kk never in descriptions/logs, midnight curve, perf, granularity), 8 price regions (Spain PVPC + 7 via Energy-Charts), localized errors/regions. 166 tests. Live check of kiosk + all 8 regions passed.
- [x] Step 4: widgets (small, medium, large, accessoryRectangular, accessoryCircular), App Intent config (content, price window), snapshot tool (67 PNGs), 4 design iterations:
  - Iter 1-2 (implementer): accented-mode blue marks, label collisions, "-0,000", faint past bars, longer languages.
  - Iter 3-4 (after my review): grey current-hour column replaced by outline + marker, min bar height, clean dark past bars, no stem under the mini curve dot, "€/kWh" not uppercased, y-axis scale in large, helpful error states with skeleton charts, smooth sample curves, clock icon on fresh data time.
- Flaky test found once after a merge (loader deadline test with 0.2 s on a busy machine). Deadline raised; 6 + 3 clean runs since.

## In progress
- [~] Step 5: main app (onboarding, dashboard, settings, kiosk how-to, country picker, add-widget guide, Mac layout, dark mode) + simulator screenshots.

## Pending
- [ ] Step 6: integration, README, MORNING_REPORT

## Known issues
- macOS signing needs a team (DECISIONS D4).
- No public API to add a widget from the app (checked iOS 27 SDK). App shows a guide and detects installed widgets.
