# AGENTS.md

Guide for coding agents (and humans) working on SunWatt. Read this first, then `DECISIONS.md` (why things are the way they are) and `PROGRESS.md` (what is done and what is open).

## What the app is

SunWatt shows home energy data in widgets and an app:

- **Solar production** from one of three providers (`SolarProvider`, chosen under "Solar system"):
  - Huawei FusionSolar *kiosk link* (public read-only URL with a `kk=` token),
  - SolarEdge Monitoring API (site ID + API key; `overview` + `power`, 2 requests per fetch),
  - PVOutput (system ID + API key in headers; covers many inverter brands).
- **Electricity price**: Spain retail PVPC (Red Eléctrica, `apidatos.ree.es`); Portugal, France, Germany+Luxembourg, Austria, Belgium, Netherlands, North Italy day-ahead wholesale (Energy-Charts, `api.energy-charts.info`, Fraunhofer ISE, CC BY 4.0). Wholesale is labelled "Day-ahead price", never sold as retail.

Surfaces: widgets (small, medium, large, Lock Screen rectangular + circular), Control Center controls (price, solar), Siri/Shortcuts intents (price now, solar now, cheapest hours), the app (onboarding, dashboard, settings, widget guide), Apple Watch app + complications (circular, rectangular, inline, corner).

Platforms: iOS / iPadOS / macOS / watchOS **27.0** (Xcode 27). Languages: en (source), es, pt, fr, de, it, nl. Light and dark mode.

Product name: **SunWatt** (store name "SunWatt: Solar & Power Price"). Team `EX68NKELW9`, bundle IDs `com.ideatic.sunwatt`, `.widgets`, `.watchkitapp`, `.watchkitapp.widgets`, App Group `group.com.ideatic.sunwatt`.

## Repository layout

| Path | What |
| --- | --- |
| `project.yml` | XcodeGen spec, the source of truth. `SunWatt.xcodeproj` is generated and git-ignored. Version (`MARKETING_VERSION`) and build number (`CURRENT_PROJECT_VERSION`) live only here. |
| `App/` | App target (iOS + macOS): entry point, Siri shortcuts, watch sync, debug kiosk prefill, assets. |
| `Widgets/` | Widget extension: widget, controls, App Intent config, intent strings. |
| `Watch/`, `WatchWidgets/` | watchOS app and complications. |
| `Packages/SunWattKit/` | Shared package with almost all code. Targets are thin shells around it. |
| `Packages/SunWattKit/Sources/SunWattSnapshots`, `SunWattWatchSnapshots` | Render widgets, app screens and watch views to PNG. |
| `Screenshots/` | Rendered PNGs, simulator captures, design logs (`*/ITERATIONS.md`). |
| `AppStore/` | Store metadata in 7 languages, privacy answers, review notes, `check_metadata.py`. |
| `docs/` | Privacy/support pages (to host) and `RELEASE.md`. |
| `scripts/` | `verify.sh` (full check), `release.sh` (archive/upload), export options. |
| `Config/kiosk.local` | Git-ignored, one line: the developer's kiosk link for debug prefill and live tests. |

### Inside `SunWattKit/Sources/SunWattKit`

- `Models/`: `PriceRegion` (8 regions, time zone, source), `PriceSeries` (+ analysis: min/max/cheapest hours, + building/aggregation 15 min → hourly), `SolarSnapshot`, `Settings`, `SunWattData`.
- `Services/`: `HTTPClient` protocol (stubbed in tests), `FusionSolarKioskClient`, `KioskEndpoint` (parse/validate link, `cacheID` = SHA-256 of host+token), `SolarEdgeClient`, `PVOutputClient` (+ `SolarLocalTime` helpers), `HTMLEntities`, `PVPCClient`, `EnergyChartsClient`.
- `Models/SolarAccount`: `SolarProvider`, `SolarAPICredentials` (validated ID + key), `SolarAccount` (validated settings → client + `cacheID`). Every solar consumer goes through `SolarAccount(settings:)`; do not read `kioskURL` directly.
- `Store/AppGroupStore`: settings + last good data in the App Group, local fallback when the group container is missing.
- `Timeline/`: `SunWattLoader` (fetch with deadline + cache window), `TimelineBuilder` (widget entries, stale flags), `PriceRangeDataSource`.
- `Views/`: widgets, shared chart components, app screens (`AppModel` drives the app), `Watch/`, `Intents/`.
- `DesignSystem/`: `Theme`, `Format` (locale-aware numbers), `L10n`.
- `Resources/*.xcstrings`: `Core` (data, errors, regions), `Widgets`, `App`, `Watch`, `Intents`.

## Rules that must not break

1. **Kiosk token and API keys are secrets.** Never commit a kiosk URL or a SolarEdge/PVOutput key, never log them, never put them in error text or descriptions (`HTTPFetch.perform(redacting:)`, custom `description`/`customMirror`), never write them to a disk cache (the dedicated ephemeral `URLSession` has no URL cache). They are stored in the keychain (`KeychainSecretStore`, DECISIONS D19), not in the settings plist. Release builds must not contain `kiosk.local`.
2. **No personal data in fixtures.** Real captures are fine, but replace station name, address and station id (`stationDn`) with demo values *before* the first commit. History was rewritten once to remove such data (DECISIONS D5); do not reintroduce it.
3. **Widget budget.** Widget extensions get ~30 s. `SunWattLoader.load(deadline:)` defaults to 15 s per source; keep every network path under that deadline and cancellable.
4. **Rate limits.** SolarEdge: 300 requests/day per site; `SolarProvider.minimumFetchInterval` (20 min) makes the loader reuse younger solar data, and a failure younger than that, even on explicit refreshes. PVOutput: 60/hour. **Energy-Charts rate-limits (HTTP 429).** One request per refresh (today+tomorrow as one range). Widgets, controls and intents pass `maxCacheAge: SunWattLoader.sharedCacheWindow` (120 s); only explicit refreshes use 0. A 429 falls back to cached data.
5. **Time zones.** Price day math uses `PriceRegion.calendar` (Lisbon for Portugal, CET elsewhere). Solar labels use Europe/Madrid. SolarEdge/PVOutput send local site times; they are read in the device time zone (`SolarLocalTime.deviceCalendar`). DST days have 23/25 hours, covered by real fixtures.
6. **Localization.** Every user-facing string goes in a catalog with all 6 translations (tests enforce this). Catalog keys are `extractionState: manual`; do not let Xcode re-extract into them. Numbers use `Format.locale` (user locale), not a fixed Spanish format.
7. **Attribution.** Energy-Charts "Fraunhofer ISE, CC BY 4.0" must stay visible in the app (dashboard, settings, onboarding).
8. **Privacy manifests** (`PrivacyInfo.xcprivacy`) exist for every target and the package bundle. Adding a required-reason API means updating them.
9. **No "add widget" API** exists; the app shows a guide and detects installed widgets via `WidgetCenter.currentConfigurations()`.

## Workflows

### Setup (macOS only)

```bash
brew install xcodegen
xcodebuild -downloadPlatform iOS && xcodebuild -downloadPlatform watchOS
xcodegen generate && open SunWatt.xcodeproj
```

The code needs Apple SDKs (SwiftUI, WidgetKit, AppIntents, Charts, CryptoKit). It does **not** build on Linux; a Linux container can only edit code, docs and metadata (and run `python3 AppStore/check_metadata.py AppStore/metadata`).

### Test

```bash
scripts/verify.sh          # swift test + iOS Simulator tests + macOS build + watchOS build → VERIFY OK / VERIFY FAILED
scripts/verify.sh quick    # swift test only
cd Packages/SunWattKit && swift test --filter LoaderTests   # one suite
```

- CI: `.github/workflows/verify.yml` runs `scripts/verify.sh` on a GitHub-hosted Mac (`xcode-27` runner, unsigned builds) on every push to `main` and every pull request. Full logs are in the run's `verify-logs` artifact. Use it when no Mac is at hand (e.g. a Linux session).
- Tests use Swift Testing (`import Testing`, `@Suite`, `@Test`), not XCTest.
- Network is always stubbed (`StubHTTPClient`). Fixtures live in `Tests/SunWattKitTests/Fixtures/` (real responses, anonymized; `*-synthetic.json` are hand-made).
- Live tests are opt-in and slow (15 s between requests): `cd Packages/SunWattKit && SUNWATT_LIVE=1 swift test --filter LiveTests`. They read `Config/kiosk.local` and hit all 8 price regions. SolarEdge and PVOutput run only when `Config/solaredge.local` / `Config/pvoutput.local` exist (line 1: ID, line 2: API key; git-ignored).
- Simulator default: iPhone 18 Pro, iOS 27.0 (`SUNWATT_SIM` overrides). macOS builds run with `CODE_SIGNING_ALLOWED=NO` and only compile (DECISIONS D4).

### Screenshots and design checks

```bash
cd Packages/SunWattKit
swift run SunWattSnapshots ../../Screenshots/widgets
swift run SunWattSnapshots --app ../../Screenshots/app
swift run SunWattWatchSnapshots ../../Screenshots/watch
```

After any visual change: render, look at the PNGs (light/dark, day/night/stale/error, long languages like German), log the critique and the changes in the folder's `ITERATIONS.md`.

### Adding things

- **Solar provider**: add a case to `SolarProvider` (+ `displayName`, `shortName`, `portalURL`, `minimumFetchInterval`), a client conforming to `SolarDataSource`, a case in `SolarAccount` (`init(settings:)`, `cacheID`, `client`), help text in `SolarSetupEditor`, strings in all languages, fixture + parser tests, and mention it in the privacy page and store texts.
- **Price region**: add a case to `PriceRegion` (zone code, time zone, flag, source), add strings to `Core.xcstrings` in all languages, save a real fixture, add parser tests, run the live test once.
- **User-facing string**: add the key to the right catalog with all 7 languages.
- **Target setting / plist key**: change `project.yml`, never the generated project.

### Translations (one Haiku subagent per locale, acting as LQA)

Every new or changed user-facing string (string catalogs, App Store metadata, `docs/` pages) is translated like this:

1. Write the English source text first and add the key with its English value.
2. Start **one subagent per locale** (es, pt, fr, de, it, nl) on the **Haiku** model. Each one handles only its own language, so terms and grammar from one language never leak into another. Give each one:
   - the English strings with context (screen, where the text appears, space limits: widgets and buttons are short, App Store keywords max 100 characters),
   - the existing translations in its locale for the same feature (to match terminology and tone),
   - the locale's conventions: tú in Spanish; European Portuguese (pt-PT, not Brazilian); the formality each catalog already uses (e.g. `App.xcstrings` uses Sie in German, `Core`/`Widgets`/`Intents` use du); keep brand and product names (SunWatt, FusionSolar, SolarEdge, PVOutput, Kiosk, Red Eléctrica) and placeholders (`%@`, `%lld`) unchanged.
   - Each subagent works as an LQA (linguistic quality assurance) reviewer, not only a translator: it reports anything ambiguous in the source, and checks length, terminology consistency and natural phrasing.
3. **The main agent audits every result** before writing it to the files: placeholders kept, nothing left in English, no words from another language, consistent terms across the app, same formality, sensible length. Note: "solar system" is not "sistema solar" (that means the planets); use the local term for a solar installation or monitoring system.
4. When something looks wrong, send it back **to the subagent of that locale** (SendMessage) with the exact string and the problem, and apply only the corrected answer. Do not fix one language by copying from another.
5. Then run the catalog tests (`swift test --filter L10n`, `--filter ViewStrings`) and `python3 AppStore/check_metadata.py AppStore/metadata`.

### Release

Step by step in `docs/RELEASE.md`. `scripts/release.sh` is a dry run by default; `--upload` changes the Apple Developer account and uploads to App Store Connect, so run it only when the owner says so. Check metadata with `python3 AppStore/check_metadata.py AppStore/metadata`. Open owner items are listed in `PROGRESS.md` (host `docs/`, replace `REPLACE-ME`, App Store Connect record).

## Working conventions

- Small, focused commits with plain-language messages (`Area: what changed`).
- Record each non-obvious decision in `DECISIONS.md` (decision, why, how to change it) and update `PROGRESS.md` when a step is done or a known issue changes.
- Run `scripts/verify.sh` before merging; report failures with their output, do not hide flaky tests (raise the timeout or fix the cause, and note it in `PROGRESS.md`).
- Before pushing, grep tracked files for `kk=` followed by a real token and for real plant names or addresses.
