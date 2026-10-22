# SunWatt for Android: plan

This file is the plan for the Android app. The product rules are in the top-level `AGENTS.md` ("Two apps, one product") and `FEATURES.md`. The decisions are D33 and later in `DECISIONS.md`.

## Goal

A fast native Android app with the same features, behaviour and data as the Apple app. The Apple code is the reference. We port its rules, edge cases and tests. We do not design a second behaviour.

## Stack

| Part | Choice | Why |
| --- | --- | --- |
| Language | Kotlin | Native, null safety, coroutines. |
| UI | Jetpack Compose + Material 3 | Declarative like SwiftUI, so the views port screen by screen. Dynamic colour off: the SunWatt palette is the brand. |
| Widgets | Jetpack Glance | Compose-style home screen widgets. |
| Controls | Quick Settings tiles (`TileService`) | Android equivalent of the Control Center controls. |
| Background refresh | WorkManager (periodic 15 min, the Apple `refreshInterval`) | The system batches it; it survives reboots. |
| Async | Kotlin coroutines | Cancellation maps 1:1 to Swift task cancellation. |
| JSON | kotlinx.serialization | No reflection, small, fast cold start. |
| HTTP | OkHttp, behind our own `HttpClient` interface | Tests use a stub; the core does not depend on OkHttp types. |
| Secrets | Android Keystore (AES-GCM key) encrypting the secret fields | Same rule as the keychain (AGENTS.md rule 1). |
| Build | Gradle 9.8, AGP 9.4, version catalog | |
| Min SDK | 29 (Android 10) | `java.time` without desugaring, fewer old code paths, ~95 % of active devices. D33. |
| Wear OS | Wear OS app + complications, in this repo, after the phone app | Equivalent of the Watch app (FEATURES rows `app-watchApp`, `surface-SunWattWatchWidget`, `surface-SunWattSolarComplication`). |

## Modules

```
android/
  build-logic/   Included build: Gradle plugin `sunwatt.xcstrings` (strings generator)
  core/          Pure Kotlin (JVM) library: the port of SunWattKit logic. No Android types.
                 Tests run on the JVM and read the shared fixtures from ../apple/.
  app/           Android app: Compose UI, Glance widgets, Quick Settings tiles, WorkManager,
                 settings store + Keystore, app links, app shortcuts.
  wear/          Wear OS app and complications (phase 5).
```

`core` holds everything that is not UI or platform: models, the 5 solar clients, the local inverter readers, the 2 price clients, price analysis, `SolarAccount`, loader (deadlines, cache window, rate limits), timeline and stale rules, number formatting. Because it is pure JVM, its tests are fast and run without an emulator.

## What is reused from the Apple app, and how

| From | How |
| --- | --- |
| `SunWattKit` logic (Models, Services, Timeline, DesignSystem/Formatters) | Ported to Kotlin in `core`, file by file, with the same contracts (`SolarDataSource`, `PriceDataSource`, `LocalInverterReader`) and the same stored raw values. |
| Test fixtures (`apple/Packages/SunWattKit/Tests/SunWattKitTests/Fixtures/`) | Read in place by the `core` tests (system property `sunwatt.fixtures`). Not copied. |
| Swift tests | Each `*Tests.swift` file for ported logic gets a Kotlin twin. `ProviderContractTests` is mirrored with the same cases. |
| Texts (`.xcstrings`) | `build-logic` converts the catalogs to `values*/strings_<table>.xml` at build time (7 languages). Generated, never edited. A new Android-only text still goes in a catalog with all 7 languages. The `InfoPlist` and Siri phrase catalogs are not converted (iOS only). |
| Design (`Theme`, `AppPalette`, chart rules, screenshots in `apple/Screenshots/`) | Colours and spacing ported to a Compose theme; the views follow the Apple renders. |
| Store texts (`apple/AppStore/metadata/<locale>/`) | Google Play listing in fastlane `supply` layout under `android/play/listings/<locale>/`: `title.txt` from `name.txt`; `short_description.txt` (80 chars) and `full_description.txt` (4000 chars) are new texts, because the Apple ones name Siri, Apple Watch and Control Center. Written in English first, then translated by the LQA process (AGENTS.md "Translations"). Nothing is uploaded. |
| Privacy and support pages (`docs/`) | Shared. They need an Android section later (a change outside `android/`). |

## Phases and FEATURES.md rows

A row becomes `done` for Android only when a user of the Android app can use the feature. Ported and tested core logic alone is noted in the row's Notes, but the status stays `planned`.

1. **Foundation.** Gradle project, `core` models and contracts, strings generator. No row is done yet.
2. **Sources (core).** Price: `price-*` (8 regions), `price-cheapestHours`. Solar: `solar-fusionSolar`, `solar-solarEdge`, `solar-pvOutput`, `solar-fusionSolarAPI`, `solar-fronius`. Inverter: `inverter-huawei`, `inverter-sunSpec`, `inverter-fronius`, `inverter-finder`. Four parallel pieces with no shared files: price, cloud solar, Northbound, local inverters.
3. **Pipeline (core).** `SolarAccount`, `SunWattLoader` (15 s deadline, 120 s cache window, provider fetch intervals), `TimelineBuilder` (stale flags), `SunWattData`, `Format`, the provider contract tests. Rows: `solar-multiplePlants` (logic part).
4. **App (phone).** Settings store with Keystore secrets (`app-secretsKeystore`), onboarding (`app-onboarding`), dashboard (`app-dashboard`), settings (`app-settings`), widgets (`surface-SunWattWidget`, `widget-content-*`, `price-window-*`, `widget-tapOpensApp`), Quick Settings tiles (`surface-PriceControl`, `surface-SolarControl`), widget guide with `requestPinAppWidget` (`app-widgetGuide`), app link (`link-refresh`), app shortcuts (`intent-*`), languages, dark mode, attribution.
5. **Wear OS.** Wear app with solar and price pages, settings from the phone (Data Layer), complications.
6. **Check on emulators.** Phone (light/dark, 7 languages, widgets, tiles) and Wear OS. Screenshots and a log in `android/Screenshots/ITERATIONS.md`.

## Risks

- **Local network access.** Android 16+ adds a local network permission for apps that target it. The Modbus and Fronius readers and "Find inverter" need it. Check on Android 17 before release.
- **Background limits.** WorkManager runs at most every 15 min and can be delayed (Doze). Widgets show the stale state like on Apple (45 min).
- **FusionSolar API session.** Huawei allows one session per account. On Android the widgets, tiles and app run in one process, so a single in-process session store (secret, encrypted at rest) is enough. The watch has its own session: same rate limit rule as Apple (one fetch per 10 min per device).
- **Rate limits.** Energy-Charts answers 429 to parallel requests. The loader's shared cache window (120 s) must be one per process, also when several widgets and a tile refresh at the same time.
- **CI.** No Android CI job yet. It needs a change in `.github/workflows/` (outside `android/`).
- **Wear OS emulator.** No Wear OS system image is installed on the development Mac yet.
