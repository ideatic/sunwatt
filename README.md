# SunWatt

Widgets for your home energy: solar production (Huawei FusionSolar kiosk link, SolarEdge or PVOutput) and the electricity price (Spain PVPC, plus day-ahead prices for Portugal, France, Germany & Luxembourg, Austria, Belgium, Netherlands and North Italy).

Platforms: iOS 27, iPadOS 27, macOS 27, watchOS 27. Languages: English, Spanish, Portuguese, French, German, Italian, Dutch.

![Large widget](Screenshots/widgets/large-day-light.png)

## What is in it

- **Widgets** (iPhone, iPad, Mac): small, medium, large; Lock Screen rectangular and circular. Configurable: solar / price / both, whole day or previous 6 h + next 12 h. Tap a widget to refresh it.
- **Controls** (Control Center / Lock Screen): current price, current solar power.
- **Siri and Shortcuts**: "What's the electricity price in SunWatt?", solar production, cheapest hours.
- **App**: onboarding, dashboard with interactive charts, settings (country, kiosk link with "Test connection" and a how-to), guide to add widgets.
- **Apple Watch**: app (price and solar pages) and complications (circular, rectangular, inline, corner). Settings come from the iPhone.

## Repository layout

| Path | What |
| --- | --- |
| `project.yml` | XcodeGen spec. Source of truth for the Xcode project (`SunWatt.xcodeproj` is generated and git-ignored). |
| `App/` | App target (iOS + macOS): entry point, Siri shortcuts, watch sync, assets. |
| `Widgets/` | Widget extension (iOS + macOS): widget, controls, App Intent configuration. |
| `Watch/`, `WatchWidgets/` | watchOS app and complications. |
| `Packages/SunWattKit/` | Shared Swift package: models, clients, store, timeline, design system, all views, intents. Tested with `swift test`. |
| `Packages/SunWattKit/Sources/SunWattSnapshots/` | Renders every widget and app screen to PNG (`Screenshots/`). |
| `Screenshots/` | Rendered widgets, app screens, watch, simulator captures, and the design iteration logs. |
| `AppStore/`, `docs/` | Store texts in 7 languages, privacy answers, review notes, privacy/support web pages. |
| `scripts/verify.sh` | Regenerates the project, runs all tests, builds iOS Simulator, macOS and watchOS. |
| `DECISIONS.md`, `PROGRESS.md` | Decisions with reasons, and the build log of the project. |

## Setup

1. Xcode 27 with the iOS 27 and watchOS 27 simulator runtimes (`xcodebuild -downloadPlatform iOS`, `... watchOS`).
2. XcodeGen: `brew install xcodegen`.
3. Generate the project and open it:

```bash
xcodegen generate && open SunWatt.xcodeproj
```

4. Optional, for debug builds: put your kiosk link in `Config/kiosk.local` (one line, git-ignored). Debug builds use it to pre-fill Settings. Release builds never contain it.

## Build and test

```bash
scripts/verify.sh
```

Runs `swift test`, the iOS Simulator tests, and the macOS and watchOS builds. It ends with `VERIFY OK` or `VERIFY FAILED`. Full logs go to `build/logs/`.

The same check runs on GitHub (`.github/workflows/verify.yml`, Xcode 27 Mac runner, unsigned) on every push to `main` and every pull request.

Live check against the real services (kiosk from `Config/kiosk.local`, all 8 price regions, 15 s apart because Energy-Charts rate-limits):

```bash
cd Packages/SunWattKit && SUNWATT_LIVE=1 swift test --filter LiveTests
```

Regenerate the screenshots:

```bash
cd Packages/SunWattKit && swift run SunWattSnapshots ../../Screenshots/widgets && swift run SunWattSnapshots --app ../../Screenshots/app
```

## Signing and App Group

- Team: ideatic Development, S.L.U. (`EX68NKELW9`), automatic signing.
- Bundle IDs: `com.ideatic.sunwatt` (app), `.widgets`, `.watchkitapp`, `.watchkitapp.widgets`.
- App Group `group.com.ideatic.sunwatt` on all four targets. It shares settings and the last good data between the app and the widgets (and, on the watch, between the watch app and its complications).
- macOS: a `group.` App Group needs a provisioning profile, so local macOS builds without signing (`CODE_SIGNING_ALLOWED=NO`) only compile. Without the group container the store falls back to local storage.
- Release: see `docs/RELEASE.md` and `scripts/release.sh` (safe dry run by default; `--upload` sends to App Store Connect).

## Add the widgets

**iPhone / iPad**: touch and hold the Home Screen → Edit → Add Widget → search "SunWatt" → pick a size → Add Widget. Touch and hold the widget → Edit Widget to choose solar / price / both and the price window. Lock Screen: touch and hold the Lock Screen → Customize → Lock Screen → tap the widget area → SunWatt.

**Control Center**: open Control Center → + → Add a Control → SunWatt.

**Mac**: Control-click the desktop → Edit Widgets… → search "SunWatt" → drag a widget to the desktop or Notification Center.

**Apple Watch**: install from the iPhone's Watch app; edit a watch face → Complications → SunWatt.

## Data sources

- Solar, one of (Settings → Solar system):
  - **Huawei FusionSolar**: the kiosk link (sign in to the FusionSolar web portal → open the plant → "Kiosk" at the top → turn on the toggle → copy the link). Huawei caches kiosk data for up to about 30 minutes; the app always shows the data time.
  - **SolarEdge**: site ID + API key from the monitoring portal (Admin → Site Access → API Access). Monitoring API, 15-minute curve; at most one fetch every 20 minutes (300 requests/day limit).
  - **PVOutput**: system ID + API key from pvoutput.org Settings (turn on API Access). Covers many inverter brands that upload to PVOutput (Fronius, SMA, Enphase, GoodWe, Growatt…).
- Spain: PVPC from Red Eléctrica (`apidatos.ree.es`), no token.
- Other countries: day-ahead prices from Energy-Charts (Fraunhofer ISE), CC BY 4.0.
