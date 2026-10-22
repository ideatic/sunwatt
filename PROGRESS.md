# Progress

## Done
- [x] Step 1: repo, XcodeGen project (app + widget extension, iOS + macOS), local package `HomeDashKit`, core models, client protocols, `Theme`, `Format`, real fixtures, `apple/scripts/verify.sh`.
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
- Final: 298 tests, `apple/scripts/verify.sh` -> VERIFY OK (iOS Simulator tests, macOS build, watchOS build).
- One unexplained failure: right after one merge, a single `swift test` run reported 176 issues; it never reproduced (20+ runs, clean builds). `verify.sh` now prints the failing tests and keeps the log if it happens again.

- [x] More solar providers (user request): SolarEdge (Monitoring API) and PVOutput, besides FusionSolar. Provider picker in onboarding and Settings, API key + ID editor with "Test connection", 20-min fetch limit for SolarEdge, strings in 7 languages, store texts and privacy/support pages updated, `SolarProviderTests` (DECISIONS D15).
  - Written in a Linux session without Xcode; compiled and tested by the CI workflow on 2026-10-17 (`5d6b788`: VERIFY OK, 344 tests on macOS and the iOS 27 simulator, macOS and watchOS builds). Still to do on the Mac: render `apple/Screenshots/app` (new `settingsSolarEdge` and `dashboardHome` screens) and check the Settings and onboarding solar pages.
  - SolarEdge/PVOutput fixtures are synthetic. Formats checked on 2026-10-17 against open-source clients (no account available): SolarEdge field names and time format match `clambin/solaredge` (Go); PVOutput history column order, `limit=288`, 400 "No status found", 403 rate limit and the `X-Pvoutput-*` headers match `openclimatefix/pvoutput` (Python). A live check with a real site is still the final proof.
  - Translations reviewed by one Haiku LQA agent per locale (AGENTS.md, "Translations"). Portal menu names are kept in English in every language (SolarEdge: Admin › Site Access › API Access; PVOutput: Settings › API Access): checked against third-party setup guides: PVOutput matches (Settings page: enable API Access, key and System Id on the same page); SolarEdge is Admin (cog icon) › Site Access › API Access in most guides, but some portal versions call it "Plant Access" / "System Access" with an "Access Control" tab. The Admin menu only shows for accounts with admin rights (else ask the installer; the support page says so).

- [x] CI: GitHub workflow `Verify` runs `apple/scripts/verify.sh` on an Xcode 27 Mac runner (unsigned).

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
- [x] `apple/scripts/upload_metadata.sh`: uploads store texts (and optional screenshots) with fastlane deliver; dry run by default, `--upload` only on the owner's word (apple/RELEASE.md, step f2). Used for the first submission.
- [x] Store screenshots rendered by `SunWattSnapshots --store` (fastlane layout, apple/AppStore/screenshots). All 7 languages rendered and checked (2026-10-17). The folder is git-ignored; render it again before the upload.
- [x] Design renders (`apple/Screenshots/**/*.png`, 443 files) are git-ignored now; only the `ITERATIONS.md` logs stay in git. The README shows 10 copies from `docs/readme/` (widgets, Lock Screen, watch, iPhone and Mac app). Store screenshots are git-ignored too and rendered before upload (2026-10-17).
- [x] Mac widget said "Solar not set up" (2026-10-17): the Debug build's generic Mac profile does not list `group.com.ideatic.sunwatt`, so macOS blocked the App Group and the app saved settings privately. The Mac now uses `EX68NKELW9.com.ideatic.sunwatt` (DECISIONS D4); the app moves old private settings into the group and plist secrets into the keychain at launch. Checked on this Mac: settings and solar/price caches in the group, kiosk link out of the plist; the owner saw the Mac widget show solar.
- [x] `private/` (git-ignored) holds all local secrets: kiosk link, live-test keys, App Store Connect API key (checked: read-only GET /v1/apps answers 200 and lists SunWatt).
- [x] Times show AM/PM in 12-hour locales (en_US showed "01:30" for 13:30).

- [x] Second code review (2026-10-18, 10 findings): 9 fixed with tests, 1 was not a bug (REE sends "not published yet" as HTTP 502 with an `errors` body, so that check stays first). Fixed: an unknown price region no longer resets all settings; a failed keychain write no longer brings an old secret back, secrets of an earlier install are deleted at launch, and settings reads reuse the secrets (D27); SolarEdge/PVOutput curves keep both 02:xx hours on the autumn DST day; PVOutput and SolarEdge rate limits are `.rateLimited`; FusionSolar API sessions are shared by the app and the widgets (D28); price sources have no default that reads `Date()`; the App Intents symbol check is general and runs in CI on an unsigned archive (D26).
- [ ] Watch app did not page on a real watch (build 202610181936: Digital Crown and swipes did nothing). Pager no longer wrapped in a `TimelineView`, solar first like the iPhone app (DECISIONS D29). To check on the watch with the next build.
- [x] Watch price chart: tap a bar to see that hour's price (owner request, DECISIONS D29). To check on the watch with the next build.
- [x] Fronius inverters on the home network through the Solar API (fifth provider, Find/Test inverter, home consumption with batteries, texts in 7 languages, privacy page, store texts; DECISIONS D30). Not yet tried on a real Fronius inverter; fixtures are synthetic. Settings provider picker now has 4 buttons: render and check at phone width.
- [x] Controls (Control Center, Lock Screen): a tap opens the app with fresh data instead of a silent refresh; the solar control shows a moon at night. iOS shows only the icon in the small Control Center size (it cannot be hidden); the larger sizes show the values. Lock Screen widget: "kWh today" in the primary style (was barely visible on light wallpapers). To check on the iPhone.

## Known issues
- macOS signing: team is set now; local unsigned macOS builds only compile (DECISIONS D4).
- Daytime kiosk fixture is still synthetic (a real daytime capture was scheduled for 11:30 today).
- No public API to add a widget from the app (checked iOS 27 SDK). App shows a guide and detects installed widgets.

## Release checklist (from the metadata task)
- [x] Onboarding has a "Skip" path for solar (App Review notes rely on it): `apple/Packages/SunWattKit/Sources/SunWattKit/Views/App/OnboardingView.swift:98` ("Skip, prices only", shown on the solar step when no kiosk link). Review notes wording says "Skip"; the button text is "Skip, prices only".
- [x] Energy-Charts "Fraunhofer ISE, CC BY 4.0" attribution visible in the app: `Views/App/AppComponents.swift:326-331` (`sourceAttribution`), shown in `DashboardView.swift:148`, `SettingsView.swift:114`, `OnboardingView.swift:218`; sources list `SettingsView.swift:213-217` (also Red Eléctrica and the "not affiliated" line).
- [x] `ITSAppUsesNonExemptEncryption = NO` in all four Info.plists (set in `apple/project.yml`, checked in built Release bundles)
- [x] Privacy manifests (`PrivacyInfo.xcprivacy`) for app, widgets, watch app, watch widgets and the SunWattKit resource bundle (UserDefaults 1C8F.1, plus CA92.1 where standard defaults are the fallback; no file-timestamp, boot-time or disk-space APIs used). Present in the built iOS, macOS and watchOS Release bundles.
- [x] LQA + ASO review of the store texts in 7 languages and of all Italian app strings (Sonnet reviewers, one per language, audited before applying; DECISIONS D24). New subtitles and keywords, fuller descriptions, 76 Italian string fixes (link Kiosk, Siri "su SunWatt", grammar). Metadata check OK, 461 tests pass.
- [x] Website (`docs/`, GitHub Pages): home page with app icon, App Store badge (Apple's Marketing Tools badge, links to app id 6823996246; live once the app is released), screenshot strip, feature cards with widget, Lock Screen and watch renders, Mac section, providers and markets, privacy note; shared `site.css` (light/dark, phone width checked) for the privacy and support pages too. README and site pictures are English renders (`*-en` jobs in the snapshot tools).
- [x] Store URLs point to GitHub Pages, `https://ideatic.github.io/sunwatt/` (`docs/` on `main`, `.nojekyll`). Pages is on; the three URLs answer 200 (2026-10-17).
- [x] User: app record created in App Store Connect ("SunWatt: Solar & Power", same as en-US name.txt). Screenshot sizes confirmed in the renderer.

## Release (step by step in `apple/RELEASE.md`)
- [x] `DEVELOPMENT_TEAM` EX68NKELW9, version 1.0.0, build number in one place (`CURRENT_PROJECT_VERSION` in `apple/project.yml`)
- [x] iPad: 4 orientations, `UIRequiresFullScreen` false; category utilities (iOS and macOS)
- [x] Release builds without signing: iOS, macOS, watchOS pass; `kiosk.local` is not in any Release bundle
- [x] `apple/scripts/release.sh` (dry run by default, `--upload` for the real run) and export options in `apple/scripts/`
- [x] Archive without `-allowProvisioningUpdates` fails with "No profiles for ..." (expected until the first `--upload`)
- [x] Build 202610172346 uploaded (iOS + watch, macOS), store texts and screenshots (iPhone, iPad, Mac, Apple Watch, 7 languages) uploaded with `apple/scripts/upload_metadata.sh`, App Privacy "Data Not Collected" published, age rating 4+, free in 175 countries, TestFlight internal group "Team". Review notes carry the demo kiosk link, added at upload from `private/kiosk.local` (never in git); the contact phone is in a git-ignored file.
- [x] 2026-10-18: iOS and macOS 1.0 submitted for App Review (manual release after approval).
- [x] 2026-10-18: widgets from TestFlight stayed gray placeholders. Cause: the archive stripped the app binary, so iOS could not index the App Intents (DECISIONS D26). Fixed with `STRIP_STYLE: non-global`; `release.sh` checks the symbol. Build 202610172346 had the bug; it was removed from review.
- [x] 2026-10-18: 1.0 resubmitted (iOS and macOS) with build 202610181936 (second review fixes; replaced 202610181901, which was resubmitted earlier with: strip fix, dark-mode axis labels, widget tap opens the app (D25)). Store texts uploaded. If `release.sh` fails with "Failed to Use Accounts", sign in again in Xcode → Settings → Apple Accounts (javier@marinros.com, team ideatic). Not yet checked on the iPhone from TestFlight. `main` not pushed yet (pushing publishes the support page for D25).
- [x] 2026-10-19: 1.0 taken out of review and resubmitted (iOS and macOS) with build 202610192205: Apple Watch fixes (solar pages first, crown and swipes page on a real watch, tap a price bar or the production curve to see that time), checked on a real watch from TestFlight. Store texts uploaded again (widget tap line, review notes). Done through the App Store Connect API (spaceship), `main` pushed.
- [ ] Release is automatic after approval (set 2026-10-19 for iOS and macOS; `upload_metadata.sh` keeps it). Optionally create a new kiosk link afterwards (the reviewers' copy stops working).
- [ ] Next Apple update: store texts in Simplified Chinese (`zh-Hans`), Traditional Chinese (`zh-Hant`) and Polish (`pl`) are ready in `apple/AppStore/metadata/` (D40) and go up with `upload_metadata.sh`. Write the new release notes in these 3 languages too. Screenshots for them are optional (the store shows the English ones). Owner request, 2026-10-22.

## Android (`android/`, DECISIONS D31 to D40)
- [x] 2026-10-22: plan (`android/PLAN.md`), Gradle project (`core`, `ui-shared`, `app`, `wear`, `build-logic`), strings generated from the `.xcstrings` catalogs in 7 languages, Android-only texts in `android/strings/Android.xcstrings`.
- [x] Core logic ported with tests on the shared Apple fixtures: 5 solar providers, 8 price regions, local inverters (Huawei, SunSpec, Fronius), inverter finder, loader, timeline. Tests: core 471, ui-shared 19, app 71, wear 27, build-logic 40.
- [x] Phone app, Glance widgets (small, medium, large, pin from the app), Quick Settings tiles, app shortcuts, `sunwatt://refresh`, WorkManager refresh, Wear OS app and complications. Debug builds prefill the kiosk link from `private/kiosk.local`; release builds do not contain it.
- [x] `scripts/check_parity.py` compares the Kotlin stored raw values with the Swift ones. Play listing texts in 7 languages (`android/play/`).
- [x] Emulator test run: `android/Screenshots/ITERATIONS.md`.
- [ ] Phone to watch settings sync not tested end to end (needs paired devices).
- [x] 2026-10-22: release build on a real phone (Redmi Note 8 Pro, Android 10 = minSdk): crashed at start because R8 removed the WorkManager database constructor; fixed with a keep rule in `app/` and `wear/` `proguard-rules.pro`. Cold start after the fix: 250 to 430 ms (the 8 to 11 s on the emulator was emulator load). Wear release build checked on the emulator.
- [ ] Add a baseline profile.
- [x] 2026-10-22: Redmi (MIUI) widget fixes: toast after pinning, size estimate when the launcher sends no size, capsules before Android 12, no stale badge on data the worker just stored. Details in `android/Screenshots/ITERATIONS.md`.
- [x] 2026-10-22: CI job `Android` (`.github/workflows/android.yml`): build-logic and unit tests, debug and release builds, lint, Play listing check.
- [ ] Privacy and support pages for Android (`docs/`).
- Play warns that native debug symbols are missing. Not fixable: the only native library (`libandroidx.graphics.path.so`, from AndroidX) ships stripped. R8 mapping files are in the bundles, so Kotlin crashes are readable.
- [x] 2026-10-22: upload key in `private/` (D39), signed release bundles, fastlane lanes in `android/fastlane/`. Play Console app created in the Ideatic account ("SunWatt: Solar & Power", app ID 4974496635921169976).
- [x] 2026-10-22: version 1.0 (phone code 3, watch code 4) sent to Google review for production in 178 countries, with the Wear OS opt-in and the store listing in 10 languages. Managed publishing is off: it goes live when Google approves. Internal testing has phone 1 and watch 2.
- [x] 2026-10-22: Play store images in all 7 app languages; store-only listings in `zh-CN`, `zh-TW`, `pl-PL` (D40). The Chinese and Polish texts had no native-speaker check.
- [x] 2026-10-22: Android on the GitHub Pages site (`docs/`): Google Play badge (Google's official badge, not distorted) and trademark line on the home page, Android and Wear OS in the privacy policy (Keystore, Wear OS sync, WorkManager, "Find inverter", no backups, Play Data safety) and support FAQ (widgets, Quick Settings tiles, shortcuts, Wear OS), in English and Spanish.
