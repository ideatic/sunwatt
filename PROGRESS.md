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

- [x] Step 5: main app (onboarding with "Skip, prices only", dashboard with interactive charts, settings with kiosk test + how-to, country picker, add-widget guide with installed-widget detection, Mac window + Settings scene, iPad two-column layout, light/dark). 2 design iterations by the implementer + 1 visual pass after my review (app charts matched to widgets, iPad layout).
- [x] Extra (user requests): Control Center controls (price, solar), Siri/Shortcuts (3 intents, 7 languages), tap-to-refresh widgets, Apple Watch app + 4 complication families, App Store texts in 7 languages, privacy/support pages, release setup.
- [x] Full rename HomeDash -> SunWatt (code, targets, bundle IDs, App Group).
- [x] Step 6: integration, README, `/code-review high` (7 findings, all fixed: shared cache window against parallel fetches / 429, kiosk field no longer saves per keystroke, refresh cancels stale loads, per-day price contexts, yesterday's solar hidden after midnight, solar cache tied to the kiosk link, watch settings race).
- Final: 298 tests, `scripts/verify.sh` -> VERIFY OK (iOS Simulator tests, macOS build, watchOS build).
- One unexplained failure: right after one merge, a single `swift test` run reported 176 issues; it never reproduced (20+ runs, clean builds). `verify.sh` now prints the failing tests and keeps the log if it happens again.

- [x] More solar providers (user request): SolarEdge (Monitoring API) and PVOutput, besides FusionSolar. Provider picker in onboarding and Settings, API key + ID editor with "Test connection", 20-min fetch limit for SolarEdge, strings in 7 languages, store texts and privacy/support pages updated, `SolarProviderTests` (DECISIONS D15).
  - Written in a Linux session without Xcode; compiled and tested by the CI workflow on 2026-10-17 (`5d6b788`: VERIFY OK, 344 tests on macOS and the iOS 27 simulator, macOS and watchOS builds). Still to do on the Mac: render `Screenshots/app` (new `settingsSolarEdge` and `dashboardHome` screens) and check the Settings and onboarding solar pages.
  - SolarEdge/PVOutput fixtures are synthetic. Formats checked on 2026-10-17 against open-source clients (no account available): SolarEdge field names and time format match `clambin/solaredge` (Go); PVOutput history column order, `limit=288`, 400 "No status found", 403 rate limit and the `X-Pvoutput-*` headers match `openclimatefix/pvoutput` (Python). A live check with a real site is still the final proof.
  - Translations reviewed by one Haiku LQA agent per locale (AGENTS.md, "Translations"). Portal menu names are kept in English in every language (SolarEdge: Admin › Site Access › API Access; PVOutput: Settings › API Access): checked against third-party setup guides: PVOutput matches (Settings page: enable API Access, key and System Id on the same page); SolarEdge is Admin (cog icon) › Site Access › API Access in most guides, but some portal versions call it "Plant Access" / "System Access" with an "Access Control" tab. The Admin menu only shows for accounts with admin rights (else ask the installer; the support page says so).

- [x] CI: GitHub workflow `Verify` runs `scripts/verify.sh` on an Xcode 27 Mac runner (unsigned).

- [x] Home consumption for plants with a meter at home (SolarEdge, PVOutput), hidden for export-only plants (DECISIONS D16). Dashboard section + large widget line; new screenshot kind `dashboardHome`.

- [x] Kiosk link expiry: rejection detection (30 min), expired state in app, widgets, watch and Siri, renew notice a month before (DECISIONS D17). Screenshot kinds `dashboardKioskExpiring`, `dashboardKioskExpired`. Missing: a real expired-kiosk response as fixture; support page FAQ.

- [x] Huawei inverter on the home network (Modbus TCP, DECISIONS D18): live data at home, kiosk elsewhere. Tested in CI against a fake dongle (also over real TCP on loopback). **Not tested on the real SDongleA-05 yet**: needs Modbus TCP "Enable (unrestricted)" on the dongle.

## Known issues
- macOS signing: team is set now; local unsigned macOS builds only compile (DECISIONS D4).
- Daytime kiosk fixture is still synthetic (a real daytime capture was scheduled for 11:30 today).
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
