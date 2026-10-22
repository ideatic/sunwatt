# Android end-to-end test log

Date: 2026-10-22. Debug build of `android/` (app `com.ideatic.sunwatt`, watch app: same id).
Devices: phone `emulator-5554` (Pixel 10a, Android 17, 1080x2424, 420 dpi, font scale 1.0 for every result below). Watch `emulator-5556` (Wear OS 6, 454x454): **it was not in `adb devices` when the watch steps started, so steps 15 to 17 were not run** (the run was told not to start emulators).
Screenshots are not committed (scratchpad `e2e/`). Apple references: `apple/Screenshots/{app,widgets}` renders. Solar was tested with demo/seed data and fake credentials; prices are live (PVPC, Energy-Charts). No real plant name, link or token is recorded here.

An earlier pass was done at font scale 1.3 by mistake (left over from another test); it is not used for results. At 130 % nothing was judged; all pass/fail below is at 1.0.

## Checklist

| # | Step | Result | Note |
| --- | --- | --- | --- |
| 1 | Clean install, cold start | pass, see D1 | First start after install `TotalTime` 8.1 s (14.9 s on the first run, host under load); later cold starts 1.2 to 1.3 s. |
| 2 | Onboarding, 4 providers, "Skip, prices only", live PVPC dashboard, attribution | pass | Welcome, country, solar (FusionSolar kiosk/API, SolarEdge, PVOutput, Fronius forms), widget, done. Dashboard: price now, chart, min 0.013 / avg 0.208 / max 0.375, cheapest hours, footer "Prices: Red Eléctrica". |
| 3 | Germany: live Energy-Charts, attribution; back to Spain | pass | Today/Tomorrow tabs, 0.072 to 0.271 range, "Prices: Energy-Charts (Fraunhofer ISE), CC BY 4.0" on dashboard and settings. |
| 4 | Settings: invalid/fake kiosk, SolarEdge, FusionSolar API, Fronius, Find inverter, secrets | pass | Invalid link: "not a kiosk link" text. Fake well-formed link: Test connection says Huawei no longer accepts the link (also a dashboard card). SolarEdge: "ID must be a number" then "API key or the ID was rejected". API account: "did not accept the user name or the password". Fronius 192.168.1.250: "The inverter did not answer…". Find inverter ended in about 25 s with "No inverter found". `settings.json`: fake key and `kk` token absent in plain text (kioskURL is ciphertext). |
| 5 | Second plant add and remove | pass | Add: second plant form with name, provider, inverter. Remove: confirm dialog, `additionalPlants` back to `[]`. |
| 6 | Demo scenarios light, day+plants dark, day de/es | pass, see D2 | All 12 scenarios compared to Apple renders: same layout and numbers. Dark and German/Spanish fine. |
| 7 | `sunwatt://refresh` | pass | App opens, "Updated" time moved 3:23 PM to 3:25 PM. |
| 8 | Widgets: pin small, medium, large; theme; configure; tap | pass, see D3 D4 | Pin dialogs show correct previews. Home screen small, medium, large render like Apple. Switching to dark: widgets followed the theme (checked 8 to 12 s after the switch). Config: price only + "6 h before, 12 h after" on medium, solar only on small: both applied. Tap opens the app with data. |
| 9 | Quick Settings tiles | pass, see D5 | Both tiles added with `cmd statusbar add-tile`; both are icon-only tiles (sun, bolt); tapping either opens the app. |
| 10 | App shortcuts and answer screens | pass | 3 static shortcuts (price, solar, cheapestHours) exist. The 3 answer cards show right values and chart. |
| 11 | Background refresh | pass | `dumpsys jobscheduler` shows `RefreshWorker` (network constraint, next run in 11 min), `RedrawWorker`, `SessionWorker`. |
| 12 | de, fr, nl | pass | Dashboard and settings fully translated, nothing English except brand names and "Enable (unrestricted)" (Huawei menu term). Locale reset to default after. |
| 13 | Widget guide | pass with note | Small/Medium/Large buttons open the system pin dialog. "Add the price/solar tile": no dialog could be seen because the tiles were already added (D5). |
| 14 | `assembleRelease` | pass | `app-release-unsigned.apk` builds. `:wear:assembleDebug` also builds. |
| R | Real FusionSolar kiosk (debug prefill) | pass | Clean install, onboarding to "Open SunWatt": dashboard shows live solar power, kWh today, month, year and a production curve from the real plant, plus PVPC. |
| 15 | Watch app | not run | Watch emulator not connected. |
| 16 | Complications preview | not run | Same reason. |
| 17 | Phone to watch sync | not tested end to end | Emulators not paired. |

## Defects and observations (most serious first)

- **D0, doc.** `FEATURES.md` still shows Android `planned` for every row, although the Android app implements them. Parity record is stale (not fixed here, tests do not change files).
- **D1, startup.** Cold start after a fresh install is slow: `am start -W` TotalTime 8.1 s (and 14.9 s once) before the first launch has finished compiling; 1.2 to 1.3 s afterwards. With `--ez demo true -S` the app showed the splash icon for about 8 s once. Emulator load was high (host load average about 15), so this may be the emulator; recheck on a device with a release build and baseline profile.
- **D2, demo/screenshots.** `06-plants-light.png` first capture was the splash icon (screenshot taken too early), retaken. No app defect.
- **D3, widget pinning from adb.** The debug receiver crashes if the app is not in the foreground (`IllegalStateException: Calling application must have a foreground activity`). This is the Android rule, not an app defect; start the app first (`pin()` in the commands below does that).
- **D4, launcher.** With a full home screen the Pixel Launcher crashed ("Can't find space to add the item") when a third widget was added. Emulator launcher behaviour; cleared with `pm clear` of the launcher. Also, after `am force-stop com.ideatic.sunwatt` the two widgets disappeared from the home screen view (still in `dumpsys appwidget`); cause not checked, could be launcher behaviour on force-stop.
- **D5, tiles.** The tiles in the shade are icon-only (no label, no value), so the price or solar number cannot be read from the panel. `cmd statusbar add-tile` showed no system dialog, so the "Add the price tile" dialog (step 13) was not seen; check by hand on a device without the tiles added.
- **D6, large text.** Not judged (the 130 % pass was discarded). The emulator font scale was reset to 1.0 before the final screenshots.
- **D1, follow-up.** Cold start rechecked: SunWatt TotalTime 10 to 11 s, but logcat shows about 7 s before any app code runs (APK load), and the system Settings app also takes 4.4 to 7.6 s to cold start. qemu used 240 % CPU with host load average about 15. Conclusion: emulator load, not app code. Real phone (Redmi Note 8 Pro, Android 10, release build signed with the debug key): cold start 250 to 430 ms. The first release run crashed at start (R8 removed `WorkDatabase_Impl.<init>()`); fixed with a keep rule in `app/` and `wear/` `proguard-rules.pro`. Still to do: a baseline profile.
- **Pin buttons.** The dashboard card "Add the widget" shows Small, Medium, Large (programmatic pin, `WidgetPinning`) and "Show me how" (demo day scenario). Pass.
- Small: Large widget price chart shows a grey "now" column (Apple large widget has it too); the pin picker label "SunWatt" under the large preview is clipped by the system dialog.

## Real phone: Redmi Note 8 Pro, Android 10, MIUI 12 (2026-10-22)

| Check | Result |
| --- | --- |
| Release build starts | Failed first: R8 removed `WorkDatabase_Impl.<init>()`. Fixed with a keep rule (app and wear). Then pass, cold start 250 to 430 ms |
| R8 rules review (all manifest, reflection and serialization paths, both apps) | No other problem |
| Debug kiosk prefill from `private/kiosk.local` | Pass; the stored link is encrypted |
| Pin a widget from the app | MIUI adds it on a new page with no dialog, so the tap looked dead and each tap added one more. Fix: a toast when the widget is placed. MIUI never sends the pin callback (`WidgetPinnedReceiver`), so the first update of the new widget shows it too, once per request within 60 s (`PinConfirmation`). Checked: Redmi one toast, Pixel launcher on Android 17 one toast |
| Widget size | MIUI sends no size options (all `-1`); Glance then drew at the 250 dp XML minimum (content in a corner, flat charts, overlapping labels). Fix: estimate from the screen width and the cell count (`sizeWithoutLauncherSize`). Checked on the phone: pass |
| Capsules before Android 12 | Glance `cornerRadius` does nothing, so they were grey boxes. Fix: tinted capsule drawable. Checked on the phone: pass |
| Stale capsule on fresh data | The widget read `loader.cached()`, which marks everything stale. Fix: `loader.stored(freshFor)` (test `storedIsFreshForRecentSourcesOnly`). Checked on the phone: pass |

## Recheck of the Redmi fixes on Android 17 (emulator-5554, 2026-10-22)

| Check | Result |
| --- | --- |
| Launcher sends sizes (`DebugWidgetReceiver --ez sizes true`) | Yes, so the size estimate stays off |
| Pin a small widget: "Widget added to your Home Screen." toast | Pass |
| Widgets light and dark: capsules round, fresh price has no badge, 39 min old solar keeps its badge | Pass |
| Release build (signed with the debug key): starts, no crash, widgets refreshed by WorkManager | Pass. Cold start 9.5 to 18 s with host load average 24 (system Settings: 6.6 s), so emulator load again |

## Watch (emulator-5556, Wear OS 6, round)

| Check | Result |
| --- | --- |
| Day, night, stale, error pages (`SeedActivity`) | Pass |
| Not set up: opens on the price page | Pass |
| Complications preview: short, ranged, long text, price and solar | Pass |
| Cold start | Some starts still on the splash after 3 s (same emulator load as D1) |
| Phone to watch settings sync (Data Layer `/sunwatt/settings`) | Not tested end to end: pairing the emulators needs the companion app and a Google sign-in |

## Commands to repeat the run

```sh
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.10+7/Contents/Home
ADB=~/Library/Android/sdk/platform-tools/adb; cd android
$ADB -s emulator-5554 uninstall com.ideatic.sunwatt
ANDROID_SERIAL=emulator-5554 ./gradlew -q :app:installDebug
$ADB -s emulator-5554 shell am start -W -n com.ideatic.sunwatt/.MainActivity          # step 1
# steps 2 to 5: onboarding and settings by hand (uiautomator dump for coordinates)
$ADB -s emulator-5554 shell run-as com.ideatic.sunwatt cat files/settings.json | grep -c FAKEKEY   # step 4, expect 0
for s in day night stale error loading noSolar home plants kioskExpiring kioskExpired tomorrow selection; do
  $ADB -s emulator-5554 shell am start -S -n com.ideatic.sunwatt/.MainActivity --ez demo true --es scenario $s
  sleep 6; $ADB -s emulator-5554 exec-out screencap -p > 06-$s-light.png; done          # step 6
$ADB -s emulator-5554 shell cmd uimode night yes                                          # dark
$ADB -s emulator-5554 shell cmd locale set-app-locales com.ideatic.sunwatt --locales de-DE   # "" to reset
$ADB -s emulator-5554 shell am start -a android.intent.action.VIEW -d sunwatt://refresh   # step 7
$ADB -s emulator-5554 shell am start -S -n com.ideatic.sunwatt/.MainActivity --ez demo true --ez seed true --es scenario day
$ADB -s emulator-5554 shell am broadcast -n com.ideatic.sunwatt/.widget.DebugWidgetReceiver --es size small   # medium, large; app must be in foreground; then tap "Add to home screen"
$ADB -s emulator-5554 shell am start -n com.ideatic.sunwatt/.widget.WidgetConfigActivity -a android.appwidget.action.APPWIDGET_CONFIGURE --ei appWidgetId <id>
$ADB -s emulator-5554 shell cmd statusbar add-tile com.ideatic.sunwatt/com.ideatic.sunwatt.tile.PriceTileService   # and SolarTileService
$ADB -s emulator-5554 shell am start -n com.ideatic.sunwatt/.shortcuts.AnswerActivity --es answer price   # solar, cheapestHours
$ADB -s emulator-5554 shell dumpsys jobscheduler | grep -A3 com.ideatic.sunwatt
ANDROID_SERIAL=emulator-5554 ./gradlew -q :app:assembleRelease
# watch (needs emulator-5556 running):
ANDROID_SERIAL=emulator-5556 ./gradlew -q :wear:installDebug
$ADB -s emulator-5556 shell am start -n com.ideatic.sunwatt/.wear.demo.SeedActivity --es scenario day   # night stale error unconfigured off
$ADB -s emulator-5556 shell am start -n com.ideatic.sunwatt/.wear.demo.ComplicationPreviewActivity
```
