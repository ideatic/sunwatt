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
- [x] FusionSolar API account (Northbound API): login with user name + system code, station KPI, power now, hourly curve, rate limit, dongle on the home network; Settings switch "Kiosk link" / "API account" (DECISIONS D23). Not yet tried with a real account.
- [x] Several solar plants: settings, per-plant secrets and caches, loader, rotating widgets (or a pinned plant), dashboard pager, plant management in Settings, Siri/control totals, watch pages and total complications (DECISIONS D22). Not yet rendered (needs macOS) or tried on a device.
- [x] Provider contracts written on the protocols, shared `ProviderContractTests` for every solar/price/inverter source, `SolarProviderProfile` (DECISIONS D21).
- [x] SunSpec live data for SolarEdge and PVOutput (SolarEdge, SMA, Fronius, … on the home network): reader, test/find on ports 502/1502, today from the lifetime counter, SolarEdge limit kept (DECISIONS D20). Not yet tried on real hardware.
- [x] "Find inverter": scans the home subnet for a Huawei dongle (port 502 + Modbus read), fills in the address and unit ID (DECISIONS D18). Not yet tried on a real dongle.
- [x] Siri phrases: 10–11 per action (incl. "Ask SunWatt…", appliance questions like "when should I run the washing machine") in every language, everyday wording ("la luz", "placas"), app name also accepted as "Sun Watt" / "Sunwat" (`INAlternativeAppNames`). Siri still needs the app name in the sentence (Apple rule); a personal Shortcut can use any phrase. To check on device: Spanish phrases recognised.

- [x] Huawei inverter on the home network (Modbus TCP, DECISIONS D18): live data at home, kiosk elsewhere. Tested in CI against a fake dongle (also over real TCP on loopback). **Not tested on the real SDongleA-05 yet**: needs Modbus TCP "Enable (unrestricted)" on the dongle.

- [x] Full code review (15 findings): all fixed (DECISIONS D19).

- [x] Tap-to-refresh fixed (2026-10-17, simulator, iPad iOS 27): a `Button(intent:)` that wrapped the whole widget was ignored and the tap opened the app. The button is now an invisible overlay; a tap refreshes (cache files rewritten) and the app stays closed. No unit test: widget hit-testing only exists in the system renderer. Also checked on the iPhone 18 Pro simulator (2026-10-17, medium widget, live kiosk data): cache rewritten on tap, app stays closed.
- [x] FusionSolar API account field shows Huawei's portal word: "Password (system code)" in all 7 languages (label, hint, help, two errors). SolarEdge/PVOutput API keys are secret fields (dots) like the system code.
- [x] `scripts/upload_metadata.sh`: uploads store texts (and optional screenshots) with fastlane deliver; dry run by default, `--upload` only on the owner's word (docs/RELEASE.md, step f2). Not run against App Store Connect yet.
- [x] Store screenshots rendered by `SunWattSnapshots --store` (fastlane layout, AppStore/screenshots). All 7 languages rendered and checked (2026-10-17). The folder is git-ignored; render it again before the upload.
- [x] Design renders (`Screenshots/**/*.png`, 443 files) are git-ignored now; only the `ITERATIONS.md` logs stay in git. The README shows 10 copies from `docs/readme/` (widgets, Lock Screen, watch, iPhone and Mac app). Store screenshots are git-ignored too and rendered before upload (2026-10-17).
- [x] Mac widget said "Solar not set up" (2026-10-17): the Debug build's generic Mac profile does not list `group.com.ideatic.sunwatt`, so macOS blocked the App Group and the app saved settings privately. The Mac now uses `EX68NKELW9.com.ideatic.sunwatt` (DECISIONS D4); the app moves old private settings into the group and plist secrets into the keychain at launch. Checked on this Mac: settings and solar/price caches in the group, kiosk link out of the plist; the owner saw the Mac widget show solar.
- [x] `private/` (git-ignored) holds all local secrets: kiosk link, live-test keys, App Store Connect API key (checked: read-only GET /v1/apps answers 200 and lists SunWatt).
- [x] Times show AM/PM in 12-hour locales (en_US showed "01:30" for 13:30).

## Known issues
- macOS signing: team is set now; local unsigned macOS builds only compile (DECISIONS D4).
- Daytime kiosk fixture is still synthetic (a real daytime capture was scheduled for 11:30 today).
- No public API to add a widget from the app (checked iOS 27 SDK). App shows a guide and detects installed widgets.

## Release checklist (from the metadata task)
- [x] Onboarding has a "Skip" path for solar (App Review notes rely on it): `Packages/SunWattKit/Sources/SunWattKit/Views/App/OnboardingView.swift:98` ("Skip, prices only", shown on the solar step when no kiosk link). Review notes wording says "Skip"; the button text is "Skip, prices only".
- [x] Energy-Charts "Fraunhofer ISE, CC BY 4.0" attribution visible in the app: `Views/App/AppComponents.swift:326-331` (`sourceAttribution`), shown in `DashboardView.swift:148`, `SettingsView.swift:114`, `OnboardingView.swift:218`; sources list `SettingsView.swift:213-217` (also Red Eléctrica and the "not affiliated" line).
- [x] `ITSAppUsesNonExemptEncryption = NO` in all four Info.plists (set in `project.yml`, checked in built Release bundles)
- [x] Privacy manifests (`PrivacyInfo.xcprivacy`) for app, widgets, watch app, watch widgets and the SunWattKit resource bundle (UserDefaults 1C8F.1, plus CA92.1 where standard defaults are the fallback; no file-timestamp, boot-time or disk-space APIs used). Present in the built iOS, macOS and watchOS Release bundles.
- [x] LQA + ASO review of the store texts in 7 languages and of all Italian app strings (Sonnet reviewers, one per language, audited before applying; DECISIONS D24). New subtitles and keywords, fuller descriptions, 76 Italian string fixes (link Kiosk, Siri "su SunWatt", grammar). Metadata check OK, 461 tests pass.
- [x] Website (`docs/`, GitHub Pages): home page with app icon, App Store badge (Apple's Marketing Tools badge, links to app id 6823996246; live once the app is released), screenshot strip, feature cards with widget, Lock Screen and watch renders, Mac section, providers and markets, privacy note; shared `site.css` (light/dark, phone width checked) for the privacy and support pages too. README and site pictures are English renders (`*-en` jobs in the snapshot tools).
- [x] Store URLs point to GitHub Pages, `https://ideatic.github.io/sunwatt/` (`docs/` on `main`, `.nojekyll`). Pages is on; the three URLs answer 200 (2026-10-17).
- [x] User: app record created in App Store Connect ("SunWatt: Solar & Power", same as en-US name.txt). Screenshot sizes confirmed in the renderer.

## Release (step by step in `docs/RELEASE.md`)
- [x] `DEVELOPMENT_TEAM` EX68NKELW9, version 1.0.0, build number in one place (`CURRENT_PROJECT_VERSION` in `project.yml`)
- [x] iPad: 4 orientations, `UIRequiresFullScreen` false; category utilities (iOS and macOS)
- [x] Release builds without signing: iOS, macOS, watchOS pass; `kiosk.local` is not in any Release bundle
- [x] `scripts/release.sh` (dry run by default, `--upload` for the real run) and export options in `scripts/`
- [x] Archive without `-allowProvisioningUpdates` fails with "No profiles for ..." (expected until the first `--upload`)
- [ ] User: first `scripts/release.sh --upload`, TestFlight, screenshots, submit
