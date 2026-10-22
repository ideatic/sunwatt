# SunWatt for Android

The Android app (phone, widgets, Quick Settings tiles, Wear OS). Read the top-level `AGENTS.md` ("Two apps, one product"), `FEATURES.md` and `DECISIONS.md` (D31–D38) first, then `PLAN.md` here. The Apple app in `../apple/` is the reference for behaviour.

## Modules

| Module | What |
| --- | --- |
| `core/` | Pure Kotlin (JVM): the port of `SunWattKit` logic. Rules for the port: `core/CONVENTIONS.md`. |
| `ui-shared/` | Android library: Keystore cipher, colours, chart models and renderers (phone, widgets and watch). |
| `app/` | Phone app: Compose screens, Glance widgets, Quick Settings tiles, WorkManager, app shortcuts. Debug demo mode: `app/DEMO.md`. |
| `wear/` | Wear OS app and complications. |
| `build-logic/` | Gradle plugin `sunwatt.xcstrings`: `.xcstrings` catalogs → `strings.xml` (`build-logic/README.md`). |
| `strings/` | `Android.xcstrings`: texts only the Android app uses, 7 languages (D37). |
| `play/` | Google Play listing texts (fastlane `supply` layout) and their check. |

## Build and test

Needs JDK 21 and the Android SDK (`local.properties` with `sdk.dir`, git-ignored).

```bash
cd android
./gradlew :core:test :ui-shared:testDebugUnitTest :app:testDebugUnitTest :wear:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease :wear:assembleDebug :wear:assembleRelease :app:lintDebug :wear:lintDebug
python3 -I ../scripts/check_parity.py
python3 -I play/check_listing.py play/listings
```

The `core` tests read the shared fixtures in `../apple/Packages/SunWattKit/Tests/SunWattKitTests/Fixtures/`. The emulator test run and its log are in `Screenshots/ITERATIONS.md`.

Secrets and local test data go in the top-level `../private/` (git-ignored), never in this folder.
