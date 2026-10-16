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
- Fixture `kiosk-night.json` contains the station name and address of your plant (no `kk`). Remove it from the repo if that matters.

## D6. PVPC data facts
- `time_trunc=hour` returns PVPC (id 1001) hourly (24 points) and "Precio mercado spot" (id 600) in 15-min (96 points). `quarter-hour`, `fifteen-minutes`, `minute` are rejected (400). The model still supports 15-min points (`PriceGranularity`) and aggregates to hourly.
- Tomorrow before publication returns `{"errors":[{"status":"502",...}]}`: saved as `pvpc-error-unavailable.json`. Today and tomorrow are separate requests, so a missing tomorrow never breaks today.
- Real DST fixtures: 2026-03-29 (23 points) and 2025-10-26 (25 points, two "02:00" with +02:00 and +01:00).

## D7. Kiosk URL for debug builds
- Lives in git-ignored `Config/kiosk.local`. Never in source, never logged.
