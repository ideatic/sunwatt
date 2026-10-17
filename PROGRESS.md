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

## Release checklist (from the metadata task)
- [x] Onboarding has a "Skip" path for solar (App Review notes rely on it): `Packages/SunWattKit/Sources/SunWattKit/Views/App/OnboardingView.swift:98` ("Skip, prices only", shown on the solar step when no kiosk link). Review notes wording says "Skip"; the button text is "Skip, prices only".
- [x] Energy-Charts "Fraunhofer ISE, CC BY 4.0" attribution visible in the app: `Views/App/AppComponents.swift:326-331` (`sourceAttribution`), shown in `DashboardView.swift:148`, `SettingsView.swift:114`, `OnboardingView.swift:218`; sources list `SettingsView.swift:213-217` (also Red Eléctrica and the "not affiliated" line).
- [x] `ITSAppUsesNonExemptEncryption = NO` in all four Info.plists (set in `project.yml`, checked in built Release bundles)
- [x] Privacy manifests (`PrivacyInfo.xcprivacy`) for app, widgets, watch app, watch widgets and the SunWattKit resource bundle (UserDefaults 1C8F.1, plus CA92.1 where standard defaults are the fallback; no file-timestamp, boot-time or disk-space APIs used). Present in the built iOS, macOS and watchOS Release bundles.
- [ ] User: host `docs/` (e.g. GitHub Pages) and replace `REPLACE-ME` URLs/email
- [ ] User: create the app record in App Store Connect, confirm screenshot sizes

## Release (step by step in `docs/RELEASE.md`)
- [x] `DEVELOPMENT_TEAM` EX68NKELW9, version 1.0.0, build number in one place (`CURRENT_PROJECT_VERSION` in `project.yml`)
- [x] iPad: 4 orientations, `UIRequiresFullScreen` false; category utilities (iOS and macOS)
- [x] Release builds without signing: iOS, macOS, watchOS pass; `kiosk.local` is not in any Release bundle
- [x] `scripts/release.sh` (dry run by default, `--upload` for the real run) and export options in `scripts/`
- [x] Archive without `-allowProvisioningUpdates` fails with "No profiles for ..." (expected until the first `--upload`)
- [ ] User: first `scripts/release.sh --upload`, TestFlight, screenshots, submit
