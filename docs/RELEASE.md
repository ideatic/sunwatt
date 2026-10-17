# SunWatt: release steps

Team: ideatic Development, S.L.U. (`EX68NKELW9`). Bundle ID: `com.ideatic.sunwatt`. Version `1.0.0` (set in `project.yml`, `MARKETING_VERSION`). Build number: `CURRENT_PROJECT_VERSION` in `project.yml`, set by `scripts/release.sh`.

## a) Create the app record (App Store Connect)

1. appstoreconnect.apple.com > Apps > "+" > New App.
2. Platforms: iOS and macOS. (watchOS comes with the iOS app.)
3. Name: `SunWatt: Solar & Power Price`.
4. Primary language: English (US). Reason: the metadata base is en-US, and the store falls back to it for languages you do not fill. Spanish is also fine if most users are in Spain. The other 6 languages stay as extra localizations.
5. Bundle ID: `com.ideatic.sunwatt`. It is not in the list until step c) has run once. If you want to create the record first, register the ID by hand under Certificates, Identifiers & Profiles > Identifiers (App Groups capability on, group `group.com.ideatic.sunwatt`).
6. SKU: `sunwatt-ios-001` (any unique text, never shown to users).
7. User access: Full Access.

## b) Host `docs/` and fix the URLs

1. Publish the `docs/` folder with GitHub Pages (repo Settings > Pages > Deploy from branch > `/docs`) or on your own site.
2. Replace `REPLACE-ME` in:
   - `AppStore/metadata/*/privacy_url.txt`, `support_url.txt`, `marketing_url.txt`
   - `AppStore/app_privacy.md`, `AppStore/review_notes.txt` (contact e-mail)
   - `docs/privacy.html`, `docs/support.html` (contact e-mail)
3. Check: `python3 AppStore/check_metadata.py AppStore/metadata`.

## c) First build: `scripts/release.sh --upload`

Do a safe run first: `scripts/release.sh`. It archives and exports to `build/export/`. Before step c) it stops with `No profiles for 'com.ideatic.sunwatt' were found`. That is expected.

Then run `scripts/release.sh --upload`. Xcode must be signed in to the ideatic account (Xcode > Settings > Accounts). This run uses `-allowProvisioningUpdates` and changes the Apple Developer account:
- registers 4 App IDs: `com.ideatic.sunwatt`, `.widgets`, `.watchkitapp`, `.watchkitapp.widgets`
- registers the App Group `group.com.ideatic.sunwatt` and links it to the IDs
- creates App Store provisioning profiles (iOS, watchOS, macOS)
- creates a distribution certificate if none works
- uploads the iOS build (with the watch app) and the macOS build to App Store Connect

The script writes the new build number into `project.yml`. Commit it after the upload (`git commit -am "Build <number>"`), so the next upload gets a higher number.

Processing takes 5 to 30 minutes. You get an e-mail. If the upload says the app record is missing, do step a) and run again.

## d) TestFlight

1. App Store Connect > your app > TestFlight. Wait for the build to leave "Processing".
2. Export compliance: no question, the build has `ITSAppUsesNonExemptEncryption = NO`.
3. Internal Testing > "+" group > add yourself (team members only, no review needed) > add the build.
4. Install with the TestFlight app. Check: onboarding, "Skip, prices only", widgets (Home Screen, Lock Screen), watch app, Mac app.

## e) App Privacy

App Store Connect > App Privacy. Answers are in `AppStore/app_privacy.md`: "Data Not Collected". Add the privacy policy URL from step b). Export compliance: "No" to non-exempt encryption. Age rating: all "None", result 4+.

## f) Screenshots

Follow `AppStore/SCREENSHOTS.md` (sizes, order, 7 languages). Upload for iPhone 6.9", iPad 13", Mac, Apple Watch.

## f2) Upload the store texts: `scripts/upload_metadata.sh`

Uploads `AppStore/metadata/` (7 locales) with fastlane deliver. It never uploads a binary, never submits for review and never releases.

1. Install: `brew install fastlane`.
2. API key: App Store Connect > Users and Access > Integrations > App Store Connect API > Team Keys > "+". Role: App Manager. Download the `.p8` file once (Apple shows it only once).
3. Write `private/appstore_key.json` (git-ignored; the script's default, or set `ASC_API_KEY_PATH` to another path). Put the whole text of the `.p8` in `key`, with each line break written as `\n`. Then delete the `.p8` file. Placeholder values:

```json
{
  "key_id": "XXXXXXXXXX",
  "issuer_id": "00000000-0000-0000-0000-000000000000",
  "key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
  "duration": 1200,
  "in_house": false
}
```

4. Dry run (default): `scripts/upload_metadata.sh`. It checks the metadata and prints what it would upload. It does not contact Apple and needs no key.
5. Real upload, only when the owner says so: `scripts/upload_metadata.sh --upload`. It runs deliver twice (`ios`, then `osx`). Use `--platform ios|osx|both` to limit it.
6. Screenshots are off by default. `--screenshots <dir>` adds them and overwrites the store screenshots. deliver reads `<dir>/<locale>/*.png` (folders `en-US`, `es-ES`, ...). The folders in `Screenshots/` are not in that layout yet. See step f).

The app record (step a) must exist first.

## g) macOS: one record or two?

Recommended: one record (Universal Purchase). The Mac app uses the same bundle ID `com.ideatic.sunwatt`, so one app record covers iOS, iPadOS, macOS and watchOS. Users buy (or here, get) the app once and it works on all their devices. One page, one set of reviews, one privacy answer set. The macOS build uploads into the same record when you add the macOS platform in step a).

Separate Mac record: only if you want a different name, price, or release date for the Mac. It needs a different bundle ID, which breaks the shared App Group on iOS and Mac and the shared code path. Not worth it for a free app.

Mac notes: the Mac app is sandboxed with the team-prefixed App Group (`EX68NKELW9.com.ideatic.sunwatt`, no profile entry needed). The signed archive needs the profile from step c). See `DECISIONS.md` D4.

## h) Submit

Select the build in the version page, fill in the review notes from `AppStore/review_notes.txt`, then "Add for Review". Review takes about 1 to 2 days.
