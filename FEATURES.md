# Features and compatibility: Apple and Android

SunWatt is one product with two apps: `apple/` (iOS, iPadOS, macOS, watchOS) and `android/`. This file is the single list of what the product does, and the status of each feature in each app. `scripts/check_parity.py` checks it on every push (CI job `Parity`) and in `apple/scripts/verify.sh`.

## Rules

1. **Every feature has one row here.** A change that adds, removes or changes a feature updates its row in the same change.
2. **A feature is done only when it is done in both apps.** Build it in both apps in the same change when you can. When one app must wait, set that app to `planned` and say why in Notes. `planned` is a debt: the next work on that app starts with the `planned` rows.
3. **`n/a` needs a reason** in Notes (for example: the platform has no such surface). Use the nearest platform equivalent instead of `n/a` when one exists (Control Center control → Quick Settings tile, Siri shortcut → app shortcut or Assistant, watch complication → Wear OS tile or complication).
4. **Same behaviour, same data.** Both apps keep the contracts below. A change to a contract changes both apps (or sets the other app's row to `planned`) and updates this file.
5. **The check is the guardrail.** `scripts/check_parity.py` finds the providers, price regions, inverter kinds, widget options, app links, intents and widgets in the code and fails when one has no row here. Never silence it; add the row.

Status values: `done`, `planned`, `n/a`.

## Shared contracts (both apps must keep these)

- **Stored values.** The raw strings of `SolarProvider`, `PriceRegion`, `WidgetContent` and `PriceWindow` (the part after the prefix in the IDs below, for example `fusionSolarAPI`, `italyNorth`) are the same in both apps. They are stored settings values and the shared names for logs, fixtures and any future settings export.
- **App links.** `sunwatt://<link>` (rows `link-*`) opens the app and does the same thing in both apps.
- **Provider contracts.** Each solar provider, price source and local inverter reader keeps the contract written on its Swift protocol (`SolarDataSource`, `PriceDataSource`, `LocalInverterReader` in `apple/Packages/SunWattKit`). The Android port keeps the same guarantees, errors and time budget, and runs the same contract cases.
- **Fixtures.** The real and synthetic responses in `apple/Packages/SunWattKit/Tests/SunWattKitTests/Fixtures/` are the shared test data. Android tests read them from that folder (do not copy them). A parser change in one app is tested against the same fixture in the other.
- **Limits and timing.** Request limits (`SolarProvider.minimumFetchInterval`, Energy-Charts one request per refresh, PVOutput 60/hour), the 15 s source deadline, the 120 s shared cache window and the time zone rules (AGENTS.md rules 3 to 5) are the same in both apps.
- **Texts.** The `.xcstrings` catalogs are the single source of the user-facing texts. Android strings are generated from them, never written by hand. A new text goes in a catalog with all 7 languages, also when only Android uses it.
- **Secrets.** Same rules in both apps (AGENTS.md rule 1): kiosk tokens, API keys and system codes stay in the platform keystore, never in logs, files or error texts.
- **Attribution.** Energy-Charts "Fraunhofer ISE, CC BY 4.0" is visible in both apps.

When the Android code gets its own enums for the stored values above, extend `scripts/check_parity.py` in the same change so it compares the Kotlin raw values with the Swift ones.

## Solar providers

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| solar-fusionSolar | Huawei FusionSolar kiosk link | done | done | Fixture and contract tests pass; tried live with a real kiosk link on the emulator. |
| solar-solarEdge | SolarEdge Monitoring API | done | done | Fixture and contract tests pass; not tried with a live account. |
| solar-pvOutput | PVOutput | done | done | Fixture and contract tests pass; not tried with a live account. |
| solar-fusionSolarAPI | Huawei FusionSolar API account (Northbound) | done | done | Fixture and contract tests pass; not tried with a live account. Session shared between app and widgets in one process (D28, D36); the watch has its own session. |
| solar-fronius | Fronius inverter on the home network | done | done | Fixture and contract tests pass; not tried with a real inverter. Local network only (D30). |
| solar-multiplePlants | More than one solar plant | done | done | Add and remove plants tried on the emulator. |

## Local inverter (live data at home)

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| inverter-huawei | Huawei dongle over Modbus TCP | done | done | Tests with a fake Modbus transport; not tried with a real dongle. |
| inverter-sunSpec | SunSpec inverter over Modbus TCP | done | done | Tests with a fake Modbus transport; not tried with a real inverter. |
| inverter-fronius | Fronius Solar API over HTTP | done | done | Tests with stubbed HTTP; not tried with a real inverter. |
| inverter-finder | "Find inverter" scan of the home network | done | done | Tests with fakes; not tried on a real home network. |

## Electricity price regions

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| price-spain | Spain, PVPC retail (Red Eléctrica) | done | done | Live data tried on the emulator. |
| price-portugal | Portugal, day-ahead (Energy-Charts) | done | done | Fixture and contract tests; Energy-Charts live data tried on the emulator. |
| price-france | France, day-ahead | done | done | Fixture and contract tests. |
| price-germany | Germany + Luxembourg, day-ahead | done | done | Fixture and contract tests. |
| price-austria | Austria, day-ahead | done | done | Fixture and contract tests. |
| price-belgium | Belgium, day-ahead | done | done | Fixture and contract tests. |
| price-netherlands | Netherlands, day-ahead | done | done | Fixture and contract tests. |
| price-italyNorth | North Italy, day-ahead | done | done | Fixture and contract tests. |
| price-cheapestHours | Cheapest hours, min and max of the day | done | done | Same analysis as Apple, tested. |

## Widgets and system surfaces

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| surface-SunWattWidget | Home screen widget: small, medium, large; Lock Screen rectangular and circular (iOS) | done | done | Android: Glance home screen widgets small, medium, large, light and dark. No Lock Screen widgets on Android phones (n/a). |
| widget-content-both | Widget shows solar and price | done | done | Widget configuration screen. |
| widget-content-solarOnly | Widget shows only solar | done | done | Widget configuration screen. |
| widget-content-priceOnly | Widget shows only price | done | done | Widget configuration screen. |
| price-window-wholeDay | Price chart: whole day | done | done |  |
| price-window-around | Price chart: 6 h before, 12 h after | done | done |  |
| widget-tapOpensApp | Tap a widget: the app opens and loads fresh data (D25) | done | done | Tried on the emulator. |
| surface-PriceControl | Control: current price (Control Center) | done | done | Android: Quick Settings tile. At the small tile size the system shows only the icon. |
| surface-SolarControl | Control: current solar power (Control Center) | done | done | Android: Quick Settings tile. At the small tile size the system shows only the icon. |
| surface-SunWattWatchWidget | Watch complication: price | done | done | Android: Wear OS complication (short, ranged, long text). Checked in a preview on the Wear OS emulator. |
| surface-SunWattSolarComplication | Watch complication: solar | done | done | Android: Wear OS complication (short, ranged, long text). Checked in a preview on the Wear OS emulator. |

## Voice and shortcuts

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| intent-GetCurrentPriceIntent | Ask the current price (Siri, Shortcuts) | done | done | Android: app shortcut that shows the answer. No Google Assistant voice phrase yet. |
| intent-GetSolarProductionIntent | Ask the solar production (Siri, Shortcuts) | done | done | Android: app shortcut that shows the answer. No Google Assistant voice phrase yet. |
| intent-GetCheapestHoursIntent | Ask the cheapest hours (Siri, Shortcuts) | done | done | Android: app shortcut that shows the answer. No Google Assistant voice phrase yet. |

## App links

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| link-refresh | `sunwatt://refresh`: open the app and load fresh data | done | done | Tried on the emulator. |

## App

| ID | Feature | Apple | Android | Notes |
| --- | --- | --- | --- | --- |
| app-onboarding | Onboarding with "Skip, prices only" | done | done |  |
| app-dashboard | Dashboard with interactive solar and price charts | done | done |  |
| app-settings | Settings: country, solar system, "Test connection", how-to | done | done |  |
| app-widgetGuide | Guide to add the widgets, detects installed widgets | done | done | Android: pins a widget from the app (`requestPinAppWidget`) and shows the steps when the home screen cannot. |
| app-watchApp | Watch app: solar and price pages, settings from the phone | done | planned | Android: Wear OS app pages and complications work on the emulator. Settings sync from the phone (Data Layer) is built but not tested end to end: the emulators were not paired. |
| app-languages | 7 languages: en, es, pt, fr, de, it, nl | done | done | Android strings generated from the `.xcstrings` catalogs (D35). |
| app-darkMode | Light and dark mode | done | done |  |
| app-attribution | Energy-Charts attribution visible | done | done |  |
| app-secretsKeystore | Secrets in the platform keystore (keychain) | done | done | Android: secret fields encrypted with an Android Keystore key (D37). |
