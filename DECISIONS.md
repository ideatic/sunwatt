# Decisions

Each entry: decision, why, how to change it.

## D1. SDK and deployment target: iOS 27.0 / macOS 27.0
- Xcode 27.0 (27A266a) with iOS 27.0 and macOS 27.0 SDKs is installed. The iOS 27.0 simulator runtime was not installed; it was downloaded with `xcodebuild -downloadPlatform iOS` (8 GB). Simulator used: iPhone 18 Pro (iOS 27.0).
- Change: `deploymentTarget` in `project.yml` and `platforms` in `Packages/HomeDashKit/Package.swift`.

## D2. Project generation: XcodeGen
- `project.yml` is the source of truth. XcodeGen 2.46 was installed with Homebrew. `HomeDash.xcodeproj` is git-ignored and regenerated with `xcodegen generate` (also done by `scripts/verify.sh`).
- Why: reproducible, reviewable diffs, no merge conflicts in pbxproj.
- Change: commit the `.xcodeproj` (remove it from `.gitignore`) if you prefer to edit in Xcode.

## D3. One local package, one library target
- `Packages/HomeDashKit` with folders `Models/`, `Services/`, `Store/`, `DesignSystem/`, `Views/`. Widget views also live in the package, so the snapshot renderer and tests can render them with `ImageRenderer` on macOS.
- Why: separate modules buy nothing at this size and add `public` churn.

## D4. Signing
- `DEVELOPMENT_TEAM` is empty, `CODE_SIGN_IDENTITY = "-"`. iOS Simulator builds work like this, App Group included.
- macOS: a `group.` App Group entitlement needs a provisioning profile, so Xcode refuses to sign without a team. Tonight the macOS build is verified with `CODE_SIGNING_ALLOWED=NO` (compile + link only). The macOS app and widget can not run until you set your team.
- The store falls back to a local container when the App Group container is unavailable, so the app still works (without sharing) when unsigned.
- Change: set `DEVELOPMENT_TEAM` in `project.yml` (see README).

## D5. Kiosk data facts (from the real response, 2026-10-17 01:20)
- `powerCurve.xAxis`: 288 labels "HH:mm" (5 min). `powerCurve.activePower`: 288 values, missing = `"-"` (single dash). `powerCurve.currentPower` is a string. `realKpi` came as numbers; the parser accepts numbers or strings.
- No server timestamp in the response. `SolarSnapshot.fetchedAt` = device clock; `dataAsOf` = time of the last non-nil curve point.
- Fixture `kiosk-night.json` is real, but the station name, address and id were replaced with demo values. NOTE: the first commit (9ddfaba) still has the real name and address in history; squash or rewrite before pushing anywhere public.

## D6. PVPC data facts
- `time_trunc=hour` returns PVPC (id 1001) hourly (24 points) and "Precio mercado spot" (id 600) in 15-min (96 points). `quarter-hour`, `fifteen-minutes`, `minute` are rejected (400). The model still supports 15-min points (`PriceGranularity`) and aggregates to hourly.
- Tomorrow before publication returns `{"errors":[{"status":"502",...}]}`: saved as `pvpc-error-unavailable.json`. Today and tomorrow are separate requests, so a missing tomorrow never breaks today.
- Real DST fixtures: 2026-03-29 (23 points) and 2025-10-26 (25 points, two "02:00" with +02:00 and +01:00).

## D7. Kiosk URL for debug builds
- Lives in git-ignored `Config/kiosk.local`. Never in source, never logged.

## D8. More countries (user request, 2026-10-17 night)
- Spain keeps the retail PVPC price (Red Eléctrica). Portugal, France, Germany+Luxembourg, Austria, Belgium, Netherlands and North Italy use day-ahead wholesale prices from Energy-Charts (`api.energy-charts.info/price?bzn=...`, Fraunhofer ISE, CC BY 4.0, no token). All 8 verified live on 2026-10-17.
- Why Energy-Charts: free, no key, one schema for many zones. Wholesale ≠ retail: the UI labels it "Day-ahead price".
- Energy-Charts rate-limits hard (HTTP 429 after a few fast requests). One request per refresh (today+tomorrow as a range). A 429 falls back to cached data.
- Portugal: a one-day request misses the last Lisbon hour (API quirk); the range request (today+tomorrow) includes it once tomorrow is published.
- Time zones: price day math uses `PriceRegion.calendar` (Lisbon for Portugal, CET for the rest). Solar curve labels use Europe/Madrid (the user's plant).
- Change: add a case to `PriceRegion` with its zone code and time zone.

## D9. Languages and number format
- en (source), es, pt, fr, de, it, nl. String catalogs: `Localizable.xcstrings` (data/errors/regions), `Widgets.xcstrings` (widget views), `App.xcstrings` (app screens), `Widgets/Resources/Localizable.xcstrings` (App Intent strings). Tests check every key has all 6 translations.
- Numbers follow the user's locale (`Format.locale`, default `autoupdatingCurrent`), so Spanish users get the comma decimal separator. The spec asked for a Spanish number format; this keeps that for Spanish and stays correct for the other languages.
- Change: set `Format.locale` to a fixed locale at app start.

## D10. "Add widget" button
- iOS 27 / macOS 27 have no public API to add a widget for the user (checked `WidgetKit.swiftinterface`). The app shows a step-by-step guide and uses `WidgetCenter.currentConfigurations()` to show whether a HomeDash widget is already installed.

## D11. Network budget in widgets
- `HomeDashLoader.load(deadline: 15)` races each fetch against a 15 s deadline (widget extensions get ~30 s). Dedicated ephemeral `URLSession.homeDash` (10 s request / 12 s resource timeout, no URL cache so the kiosk token is not written to disk).

## D12. Live tests are opt-in
- `HOMEDASH_LIVE=1 swift test --filter LiveTests` hits the real kiosk (URL from `Config/kiosk.local`) and all 8 price regions, 15 s apart. Not part of the normal run.

## D13. Product name: SunWatt (user decision)
- "HomeDash" sounded like a smart-home app. The visible name is now **SunWatt** (App Store name "SunWatt: Solar & Power Price").
- Only user-visible text changes (display names, strings, store texts). Code names (`HomeDashKit`, targets, folders) and bundle IDs (`com.ideatic.homedash*`, App Group `group.com.ideatic.homedash`) stay, because users never see them and changing them breaks signing and stored data.
- Change: `CFBundleDisplayName` in project.yml + the string catalogs + `AppStore/metadata/*/name.txt`.
