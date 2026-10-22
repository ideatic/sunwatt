# AGENTS.md

Guide for coding agents (and humans) working on SunWatt. Read this first, then `FEATURES.md` (what the product does, in each app), `DECISIONS.md` (why things are the way they are) and `PROGRESS.md` (what is done and what is open).

## What the app is

SunWatt shows home energy data in widgets and an app:

- **Solar production** from one of five providers (`SolarProvider`, chosen under "Solar system"):
  - Huawei FusionSolar *kiosk link* (public read-only URL with a `kk=` token),
  - SolarEdge Monitoring API (site ID + API key; `overview` + `power`, 2 requests per fetch),
  - PVOutput (system ID + API key in headers; covers many inverter brands).
  - Huawei FusionSolar *API account* (Northbound API: user name + system code from the installer, server `*.fusionsolar.huawei.com`; rate-limited, at most one fetch every 10 min).
  - Fronius inverter on the home network (Solar API, plain HTTP on port 80, no account; no cloud, so data only at home, DECISIONS D30).
  - Optional live data at home over Modbus TCP: the Huawei dongle (FusionSolar) or a SunSpec inverter (SolarEdge, PVOutput users), read-only (DECISIONS D18, D20).
- **Electricity price**: Spain retail PVPC (Red Eléctrica, `apidatos.ree.es`); Portugal, France, Germany+Luxembourg, Austria, Belgium, Netherlands, North Italy day-ahead wholesale (Energy-Charts, `api.energy-charts.info`, Fraunhofer ISE, CC BY 4.0). Wholesale is labelled "Day-ahead price", never sold as retail.

Surfaces: widgets (small, medium, large, Lock Screen rectangular + circular), Control Center controls (price, solar), Siri/Shortcuts intents (price now, solar now, cheapest hours), the app (onboarding, dashboard, settings, widget guide), Apple Watch app + complications (circular, rectangular, inline, corner).

Platforms: iOS / iPadOS / macOS / watchOS **27.0** (Xcode 27). Languages: en (source), es, pt, fr, de, it, nl. Light and dark mode.

Product name: **SunWatt** (store name "SunWatt: Solar & Power"). Team `EX68NKELW9`, bundle IDs `com.ideatic.sunwatt`, `.widgets`, `.watchkitapp`, `.watchkitapp.widgets`, App Group `group.com.ideatic.sunwatt` (Mac: `EX68NKELW9.com.ideatic.sunwatt`, DECISIONS D4).

## Two apps, one product

SunWatt has an Apple app (`apple/`) and an Android app (`android/`). They are one product: same features, same behaviour, same data. The rules:

- **Every feature goes in both apps.** A new feature, a changed behaviour or a bug fix in shared logic is built in both apps in the same change when you can. When one app must wait, its row in `FEATURES.md` says `planned` with the reason. Never leave a feature in one app without a row.
- **`FEATURES.md` is the list.** Update its row in the same change. `scripts/check_parity.py` (CI job `Parity`, and the first step of `apple/scripts/verify.sh`) fails when a provider, price region, inverter kind, widget option, app link, App Intent or widget in the code has no row. Add the row; never weaken the check.
- **Keep the shared contracts** listed in `FEATURES.md`: stored raw values, `sunwatt://` links, provider contracts, limits and time zones, shared fixtures, texts from the `.xcstrings` catalogs, secret handling, attribution. A contract changes in both apps at once.
- **Port, do not reinvent.** The Apple code is the reference for each feature that exists there. Port its rules, edge cases and tests; do not design a second behaviour. When the Android app finds a better way, change both apps and record it in `DECISIONS.md`.
- **Platform-only work** (a surface one platform does not have) is fine: write `n/a` and the reason, and use the nearest equivalent on the other platform.
- **Starting work on one app:** first read the `planned` rows for that app in `FEATURES.md`. When a change touches only one app, say in the answer which `FEATURES.md` rows the other app still owes.

## Repository layout

| Path | What |
| --- | --- |
| `apple/` | The Apple app (iOS, iPadOS, macOS, watchOS). Every `apple/…` path below. Its scripts run from here and read secrets from `../private/`. |
| `android/` | The Android app (planned). Same product rules as here (DECISIONS D31). |
| `FEATURES.md` | Features of the product and their status in each app, plus the contracts both apps keep. |
| `scripts/check_parity.py` | Checks `FEATURES.md` against the code (DECISIONS D32). |
| `apple/project.yml` | XcodeGen spec, the source of truth. `apple/SunWatt.xcodeproj` is generated and git-ignored. Version (`MARKETING_VERSION`) and build number (`CURRENT_PROJECT_VERSION`) live only here. |
| `apple/App/` | App target (iOS + macOS): entry point, Siri shortcuts, watch sync, debug kiosk prefill, assets. |
| `apple/Widgets/` | Widget extension: widget, controls, App Intent config, intent strings. |
| `apple/Watch/`, `apple/WatchWidgets/` | watchOS app and complications. |
| `apple/Packages/SunWattKit/` | Shared package with almost all code. Targets are thin shells around it. |
| `apple/Packages/SunWattKit/Sources/SunWattSnapshots`, `SunWattWatchSnapshots` | Render widgets, app screens and watch views to PNG. |
| `apple/Screenshots/` | Design logs (`*/ITERATIONS.md`, in git). Renders and simulator captures land here locally and are git-ignored. |
| `docs/readme/` | The images the README shows (copies of renders; refresh them by hand when the look changes). |
| `apple/AppStore/` | Store metadata in 7 languages, privacy answers, review notes, `check_metadata.py`. |
| `docs/` | Website on GitHub Pages: privacy and support pages, shared by all platforms. Apple release steps: `apple/RELEASE.md`. |
| `apple/scripts/` | `verify.sh` (full check), `release.sh` (archive/upload), export options. |
| `private/` | Git-ignored, never committed: `kiosk.local` (the developer's kiosk link, one line, for debug prefill and live tests), `solaredge.local` / `pvoutput.local` (live tests), `appstore_key.json` (App Store Connect API key for `apple/scripts/upload_metadata.sh`, the `.p8` text inline in `key`). Put every other secret or personal value here too. |

### Inside `SunWattKit/Sources/SunWattKit`

- `Models/`: `SolarPlant` (one plant; the first lives in the original settings fields, further ones in `additionalPlants`), `PriceRegion` (8 regions, time zone, source), `PriceSeries` (+ analysis: min/max/cheapest hours, + building/aggregation 15 min → hourly), `SolarSnapshot`, `Settings`, `SunWattData`.
- `Services/`: `HTTPClient` protocol (stubbed in tests), `FusionSolarKioskClient`, `KioskEndpoint` (parse/validate link, `cacheID` = SHA-256 of host+token), `NorthboundClient` (FusionSolar API account), `SolarEdgeClient`, `PVOutputClient` (+ `SolarLocalTime` helpers), `LocalInverter` (`ModbusEndpoint`, `LocalInverterSolarClient`: cloud + inverter), `HuaweiModbus`, `SunSpec`, `Fronius` (`FroniusSolarAPIClient`, HTTP), `InverterFinder` ("Find inverter" scan), `HTMLEntities`, `PVPCClient`, `EnergyChartsClient`.
- `Models/SolarAccount`: `SolarProvider`, `SolarAPICredentials` (validated ID + key), `SolarAccount` (validated settings → client + `cacheID`). Every solar consumer goes through `SolarAccount(settings:)` / `SolarAccount(plant:)`; do not read `kioskURL` directly. Code written for one plant serves any plant through `SunWattSettings.focused(on:)`; with one plant nothing in the UI may change (`SunWattData.plantName` is nil, `otherPlants` empty).
- `Store/AppGroupStore`: settings + last good data in the App Group, local fallback when the group container is missing.
- `Timeline/`: `SunWattLoader` (fetch with deadline + cache window), `TimelineBuilder` (widget entries, stale flags), `PriceRangeDataSource`.
- `Views/`: widgets, shared chart components, app screens (`AppModel` drives the app), `Watch/`, `Intents/`.
- `DesignSystem/`: `Theme`, `Format` (locale-aware numbers), `L10n`.
- `Resources/*.xcstrings`: `Core` (data, errors, regions), `Widgets`, `App`, `Watch`, `Intents`.

## Rules that must not break

1. **Kiosk token and API keys are secrets.** Never commit a kiosk URL, a SolarEdge/PVOutput key or a FusionSolar API system code (or its session token), never log them, never put them in error text or descriptions (`HTTPFetch.perform(redacting:)`, custom `description`/`customMirror`), never write them to a disk cache (the dedicated ephemeral `URLSession` has no URL cache). They are stored in the keychain (`KeychainSecretStore`, DECISIONS D19), not in the settings plist. Release builds must not contain `kiosk.local`.
2. **No personal data in fixtures.** Real captures are fine, but replace station name, address and station id (`stationDn`) with demo values *before* the first commit. History was rewritten once to remove such data (DECISIONS D5); do not reintroduce it.
3. **Widget budget.** Widget extensions get ~30 s. `SunWattLoader.load(deadline:)` defaults to 15 s per source; keep every network path under that deadline and cancellable.
4. **Rate limits.** SolarEdge: 300 requests/day per site; `SolarProvider.minimumFetchInterval` (20 min) makes the loader reuse younger solar data, and a failure younger than that, even on explicit refreshes. PVOutput: 60/hour. **Energy-Charts rate-limits (HTTP 429).** One request per refresh (today+tomorrow as one range). Widgets, controls and intents pass `maxCacheAge: SunWattLoader.sharedCacheWindow` (120 s); only explicit refreshes use 0. A 429 falls back to cached data.
5. **Time zones.** Price day math uses `PriceRegion.calendar` (Lisbon for Portugal, CET elsewhere). Solar labels use Europe/Madrid. SolarEdge/PVOutput send local site times; they are read in the device time zone (`SolarLocalTime.deviceCalendar`). DST days have 23/25 hours, covered by real fixtures.
6. **Localization.** Every user-facing string goes in a catalog with all 6 translations (tests enforce this). Catalog keys are `extractionState: manual`; do not let Xcode re-extract into them. Numbers use `Format.locale` (user locale), not a fixed Spanish format.
7. **Attribution.** Energy-Charts "Fraunhofer ISE, CC BY 4.0" must stay visible in the app (dashboard, settings, onboarding).
8. **Privacy manifests** (`PrivacyInfo.xcprivacy`) exist for every target and the package bundle. Adding a required-reason API means updating them.
9. **No "add widget" API** exists; the app shows a guide and detects installed widgets via `WidgetCenter.currentConfigurations()`.
10. **Providers keep their contracts.** Every solar provider, price source and local inverter reader keeps the contract written on its protocol (`SolarDataSource`, `PriceDataSource`, `LocalInverterReader`) and has a case in `ProviderContractTests`. Change a contract only together with its doc comment, its contract tests and every conforming type.
11. **Both apps stay at parity.** Every feature has a row in `FEATURES.md` with its status in each app, and `scripts/check_parity.py` passes. A shared contract changes in both apps at once (see "Two apps, one product").

## Code standards and patterns

How the code is meant to look. Follow the surrounding code first; these rules say what that code is doing on purpose.

### Architecture

- **Thin targets, shared package.** App, widgets and watch targets only wire things up; logic, models and views live in `SunWattKit`, where they are testable on any platform with an SDK.
- **Protocols at every boundary.** Network (`HTTPClient`), data sources (`SolarDataSource`, `PriceDataSource`), inverters (`LocalInverterReader`, `ModbusTransport`) and secrets (`SecretStore`) sit behind protocols. Code that uses them never knows the concrete type; tests replace them with stubs.
- **Contracts are written on the protocol.** A doc comment states the data guarantees, the allowed errors and what each means, the time budget and the side effects. Conforming types follow it; `ProviderContractTests` checks it. When a client cannot meet the contract (e.g. negative night power), fix the client, not the contract.
- **One profile per variant.** What distinguishes providers lives in `SolarProviderProfile` (`SolarProvider.profile`) and `PriceRegionProfile` / `PriceSource` (`PriceRegion.profile`). Ask the profile (`profile.setup == .kioskLink`, `profile.source.isRetailTariff`, `profile.localInverter`) instead of comparing with a case (`== .fusionSolar`, `== .spain`).
- **Exhaustive switches, no `default:`** on `SolarProvider`, `PriceRegion`, `PriceSource`, `LocalInverterKind` and similar enums. A new case must not compile until every place that depends on it (texts, clients, profiles) has been written. Per-variant UI texts are such switches too.
- **Validated values, not raw settings.** Settings are parsed once into validated types (`SolarAccount(settings:)`, `SolarAPICredentials`, `KioskEndpoint`, `ModbusEndpoint`); everything else takes those. Invalid input is a `nil`, not a crash or a half-built object.
- **One way in for each job.** Solar always through `SolarAccount`, prices through `PriceDataSourceFactory`/`PriceSource.client`, loading through `SunWattLoader`, storage through `AppGroupStore`. Do not add a second path around them.

### Swift

- **Swift 6, strict concurrency.** Types crossing tasks are `Sendable` (`@unchecked Sendable` only with a lock and a comment). UI models are `@MainActor`. Use `async`/`await`; no completion handlers, no blocking waits, no `DispatchSemaphore`.
- **Cancellation and deadlines.** Every network or inverter path is cancellable and runs under a deadline (`SunWattLoader.race`). `CancellationError` passes through unwrapped; never turn it into a user-facing error.
- **Errors.** Throw `SunWattError` (or `CancellationError`); map anything else at the boundary (`HTTPFetch.perform`, `ModbusSession.run`). User-facing texts are localized and never contain secrets.
- **Injected dependencies and time.** Clients take `http`, `now`, `calendar`, `makeTransport` as parameters with production defaults. Do not call `Date()` or a shared singleton deep inside logic that tests need to control.
- **Value types and pure functions** for parsing and analysis (`parse(_:fetchedAt:)`, `PriceSeries` analysis, `merge`, `fillingIn`): input in, value out, easy to test with fixtures.
- **Stored data stays readable.** New `Codable` fields are optional or decoded with `decodeIfPresent` and a default, so settings and caches written by older versions still load. Rename stored keys only with a migration.
- **No force unwraps** except for constants known to be valid (fixed URLs, time zone identifiers). No `print` in the app and package code (the snapshot command-line tools may print progress); never log secrets.
- **Names and comments.** Plain names that say what a thing is. Doc comments explain why and the non-obvious rules (units, signs, time zones, limits), not what the next line does. Keep functions small; extract a helper when a block needs a comment to be understood.

### UI

- User-facing text only through the catalogs (`atext`, `wtext`, `L10n.string`), in all languages (Translations below); brand names verbatim.
- Numbers through `Format` (user locale), units in the view (`kW`, `kWh`, `MWh` from `Format.energy`), colours and spacing from `Theme` / `AppPalette`.
- After a visual change, render the screenshots and check them (Screenshots and design checks below).

### Tests

- Swift Testing. Every bug fix gets a test that fails without the fix; every new parser gets a real (anonymized) or synthetic fixture.
- No real network or inverter in tests: `StubHTTPClient`, `FakeModbusTransport`, fakes for sources, temporary stores.
- A new provider, price source or inverter kind adds one case to `ProviderContractTests` (`SolarContractCase`, `PriceContractCase`, `InverterContractCase`); keep the contract checks generic.
- Tests run on CI (macOS); in a Linux session, push and read the CI result before calling a change done.

## Workflows

### Setup (macOS only)

```bash
brew install xcodegen
xcodebuild -downloadPlatform iOS && xcodebuild -downloadPlatform watchOS
cd apple && xcodegen generate && open SunWatt.xcodeproj
```

The code needs Apple SDKs (SwiftUI, WidgetKit, AppIntents, Charts, CryptoKit). It does **not** build on Linux; a Linux container can only edit code, docs and metadata (and run `python3 apple/AppStore/check_metadata.py apple/AppStore/metadata`).

### Test

```bash
apple/scripts/verify.sh          # swift test + iOS Simulator tests + macOS and watchOS builds + unsigned Release archives with the App Intents symbol check → VERIFY OK / VERIFY FAILED
apple/scripts/verify.sh quick    # swift test only
cd apple/Packages/SunWattKit && swift test --filter LoaderTests   # one suite
```

- CI: `.github/workflows/verify.yml` runs `apple/scripts/verify.sh` on a GitHub-hosted Mac (`xcode-27` runner, unsigned builds) on every push to `main` and every pull request. Full logs are in the run's `verify-logs` artifact. Use it when no Mac is at hand (e.g. a Linux session).
- Tests use Swift Testing (`import Testing`, `@Suite`, `@Test`), not XCTest.
- Network is always stubbed (`StubHTTPClient`). Fixtures live in `Tests/SunWattKitTests/Fixtures/` (real responses, anonymized; `*-synthetic.json` are hand-made).
- Live tests are opt-in and slow (15 s between requests): `cd apple/Packages/SunWattKit && SUNWATT_LIVE=1 swift test --filter LiveTests`. They read `private/kiosk.local` and hit all 8 price regions. SolarEdge and PVOutput run only when `private/solaredge.local` / `private/pvoutput.local` exist (line 1: ID, line 2: API key; git-ignored).
- Simulator default: iPhone 18 Pro, iOS 27.0 (`SUNWATT_SIM` overrides). macOS builds run with `CODE_SIGNING_ALLOWED=NO` and only compile (DECISIONS D4).

### Screenshots and design checks

```bash
cd apple/Packages/SunWattKit
swift run SunWattSnapshots ../../Screenshots/widgets
swift run SunWattSnapshots --app ../../Screenshots/app
swift run SunWattWatchSnapshots ../../Screenshots/watch
```

After any visual change: render, look at the PNGs (light/dark, day/night/stale/error, long languages like German), log the critique and the changes in the folder's `ITERATIONS.md`. The PNGs are git-ignored (render before and after a change to compare); only the logs are committed. If the change alters a picture in the README, copy the new render into `docs/readme/`.

### Adding things

Everything below is done for both apps ("Two apps, one product"): add or update the `FEATURES.md` row, and build the Android side too, or mark it `planned` with the reason.

- **Solar provider**: add a case to `SolarProvider` and its `SolarProviderProfile` (name, setup kind, portal, request limit, local inverter kind), a client that keeps the `SolarDataSource` contract (written on the protocol), a case in `SolarAccount` (`init(settings:)`, `cacheID`, `client`), the per-provider texts in `SolarSetupViews` (the compiler points at each exhaustive `switch`), strings in all languages, fixtures + parser tests, **one case in `ProviderContractTests`** (the shared contract checks then run on it), and mention it in the privacy page and store texts. Same for a price source (`PriceDataSource`, `PriceContractCase`) or an inverter kind (`LocalInverterReader`, `InverterContractCase`).
- **Price region**: add a case to `PriceRegion` and its `PriceRegionProfile` (time zone, flag, `PriceSource` with the zone code) and its `displayName`, strings in `Core.xcstrings` in all languages, a real fixture + a `PriceContractCase` (the shared contract checks then run on it), and run the live test once. A new kind of source also needs a client that keeps the `PriceDataSource` contract and its texts in the exhaustive `switch`es on `PriceSource` (tariff name, note, attribution).
- **User-facing string**: add the key to the right catalog with all 7 languages.
- **Target setting / plist key**: change `apple/project.yml`, never the generated project.

### Translations (one Haiku subagent per locale, acting as LQA)

The `.xcstrings` catalogs are the single source of the app texts for both apps; Android `strings.xml` files are generated from them and never edited by hand. A text that only Android uses still goes in a catalog. Every new or changed user-facing string (string catalogs, store metadata, `docs/` pages) is translated like this:

1. Write the English source text first and add the key with its English value.
2. Start **one subagent per locale** (es, pt, fr, de, it, nl) on the **Haiku** model. Each one handles only its own language, so terms and grammar from one language never leak into another. Give each one:
   - the English strings with context (screen, where the text appears, space limits: widgets and buttons are short, App Store keywords max 100 characters),
   - the existing translations in its locale for the same feature (to match terminology and tone),
   - the locale's conventions: tú in Spanish; European Portuguese (pt-PT, not Brazilian); the formality each catalog already uses (e.g. `App.xcstrings` uses Sie in German, `Core`/`Widgets`/`Intents` use du); keep brand and product names (SunWatt, FusionSolar, SolarEdge, PVOutput, Kiosk, Red Eléctrica) and placeholders (`%@`, `%lld`) unchanged.
   - Each subagent works as an LQA (linguistic quality assurance) reviewer, not only a translator: it reports anything ambiguous in the source, and checks length, terminology consistency and natural phrasing.
3. **The main agent audits every result** before writing it to the files: placeholders kept, nothing left in English, no words from another language, consistent terms across the app, same formality, sensible length. Note: "solar system" is not "sistema solar" (that means the planets); use the local term for a solar installation or monitoring system.
4. When something looks wrong, send it back **to the subagent of that locale** (SendMessage) with the exact string and the problem, and apply only the corrected answer. Do not fix one language by copying from another.
5. Then run the catalog tests (`swift test --filter L10n`, `--filter ViewStrings`) and `python3 apple/AppStore/check_metadata.py apple/AppStore/metadata`.

### Release

Step by step in `apple/RELEASE.md`. `apple/scripts/release.sh` is a dry run by default; `--upload` changes the Apple Developer account and uploads to App Store Connect, so run it only when the owner says so. Check metadata with `python3 apple/AppStore/check_metadata.py apple/AppStore/metadata`. Open owner items are listed in `PROGRESS.md`. Privacy, support and marketing pages are `docs/` on GitHub Pages (`https://ideatic.github.io/sunwatt/`); edit them there.

## Working conventions

- Small, focused commits with plain-language messages (`Area: what changed`).
- Record each non-obvious decision in `DECISIONS.md` (decision, why, how to change it) and update `PROGRESS.md` when a step is done or a known issue changes.
- Run `python3 -I scripts/check_parity.py` and `apple/scripts/verify.sh` before merging (plus the Android build and tests when `android/` changed); report failures with their output, do not hide flaky tests (raise the timeout or fix the cause, and note it in `PROGRESS.md`).
- Before pushing, grep tracked files for `kk=` followed by a real token and for real plant names or addresses.
