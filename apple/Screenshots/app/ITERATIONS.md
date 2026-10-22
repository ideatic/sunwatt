# App design iterations

Rendered with `swift run SunWattSnapshots --app ../../Screenshots/app` (from `Packages/SunWattKit`), Spanish locale unless the file name ends in `-en`, `-de`, `-fr`. `phone-*` is 393x852 pt (iPhone), `mac-*` is a 900x640 pt window; `*-full` is the phone screen at natural height. `sim-*.png` are real iOS 27 simulator captures (`-demo`, iPhone 18 Pro).

Probe first: `ImageRenderer` on macOS draws `TextField`, `List`, segmented `Picker`, `Link` and `ScrollView` as yellow "prohibited" placeholders or nothing. Screens are therefore split into pure content views (VStack and cards) that snapshots render, and thin screen wrappers (ScrollView, sheets, refresh). Text field, switch, segmented control and link have drawn look-alikes in snapshot mode (`\.appSnapshot`).

## Iteration 1 (first render)
- Onboarding pages were blank on the Mac snapshot (the pager used a `ScrollView` there). Fixed: snapshot mode renders the page directly.
- Mac window: content taller than the window pushed the title bar and the date header out of the frame. Fixed with explicit window-height frames, top-aligned and clipped.
- Settings: the toggle title "Show tomorrow's prices when available" was truncated to one line. Fixed (`fixedSize` vertical).
- Kiosk guide: the "Open FusionSolar" link lost the amber button style in snapshots (a bare label). Fixed with a drawn button.
- Mac settings: eight country rows made the sheet three screens tall. Fixed: two columns and tighter rows on desktop.
- Demo error scenario showed an English sample message in a Spanish screen; now uses the localized package message.

## Iteration 2
- Mac dashboard: solar and price cards had different heights; both now stretch to the taller one.
- Checked German (long compounds, "Günstigste kommende Stunden", step titles that wrap), French (France prices), English: no truncation in dashboard, settings, onboarding, guides. Check light and dark: contrast of the dark label on the amber button, orange text on white (darker orange in light mode), success/warning tints.
- Chart callouts: solar (12:40, 4,6 kW) and price (19:00-20:00, 0,198 EUR/kWh) fit inside the chart with `.fit(to: .chart)` and extra headroom above the data.

## Simulator pass
- Dashboard, onboarding, settings, widget guide, kiosk guide in light and dark match the snapshots. Number format follows the device region (comma decimals), text follows the app language.
- `sim-dashboard-live.png` (real network, debug prefill) is not committed: it shows the real plant name.

## Open points
- Add-widget status needs a real device to confirm `WidgetCenter.currentConfigurations()`; in the simulator it returns 0 until a widget is placed.
- The Mac Settings window and sheets are verified through snapshots only; the unsigned Debug build launches (`open`) but window capture was not automated.

## Iteration 3: charts and iPad (current code)
Snapshots now include `ipad-<screen>-<scheme>.png` (1032x1376 pt portrait) and `ipad-landscape-<screen>-<scheme>.png` (1376x1032 pt) for dashboard, settings, onboarding (welcome, country, solar) and widget guide. `sim-*.png` are iOS 27 simulator captures: iPhone 18 Pro (light, `sim-dashboard-dark`, Spanish `-es`) and iPad Pro 13-inch (M5) as `sim-ipad-*`.
- App charts: the full-height grey "now" column is gone. The current bar is full opacity with a `.primary` 0.6 outline and a down-triangle marker. Past bars use `Theme.Price.pastOpacity(for:)` and `pastColor` (no brown bars in dark mode). Minimum bar height, trailing Y axis from `ChartLayout.priceTicks` and `solarTicks`, grid opacity 0.07, peak label and glow dot sizes as in the widget `.full` charts. Selection and callout are kept (the selected bar gets the outline instead of the marker).
- iPad render 1: the dashboard was a stretched phone column. Added `AppIdiom.pad`: two columns (`ViewThatFits`, one column in a narrow split view), taller charts, 1100 pt max width. The first snapshot pushed the widget card to the bottom (the columns took infinite height); fixed with `fixedSize(vertical:)`.
- iPad render 2: onboarding content hugged the top of a 1376 pt screen; added top padding on iPad. The two-column country list in the settings sheet truncated "Germany & Luxemb..." in the simulator, so iPad uses one column. Sheets use `.presentationSizing(.page)`.
- iPhone simulator: the top Y label "0,20 EUR" wrapped onto two lines at the larger system text size. The label now has `lineLimit(1)` and a 46 pt width.
- Simulator captures show the current smooth sample curve (5,3 kW, 16,2 kWh) and match the snapshots.

## Iteration 4: other integrations in onboarding and widget guide
- The onboarding widget page and the widget guide now list Apple Watch complications (iPhone only), Siri and Shortcuts, and Lock Screen and Control Center (not on Mac) in one card (`MoreWaysCard`).
- Render 1: the snapshot tool runs on macOS, so `#if os(iOS)` hid the iPhone rows in every PNG. Rows are now hidden with `appIdiom` at run time (watch: `.phone` only; Lock Screen and Control Center: not `.desktop`), so snapshots show them.
- Render 2: on iPhone the page needed room for the card, so the widget preview is shorter (132 pt) and smaller. Checked phone light, dark, German and English, iPad and Mac: no truncation, no overflow, the page fits without scrolling on the iPhone 17 size. Added `onboardingWidget` to the iPad and en/de snapshot lists.

## Iteration 5: price chart keeps its scale while a bar is touched
- User report (iPhone): touching the dashboard price chart made every bar shorter. The Y domain grew from 14 % to 38 % headroom while a bar was selected, to make room for the callout.
- Change: the headroom no longer depends on the selection (14 % when the current hour is shown, for the "now" marker). The callout floats over the bars and stays inside the chart (`overflowResolution` fit to chart), as in the solar chart, whose scale never changed.
- Not re-rendered (Linux session): the snapshot tool needs macOS. To check on device or with `swift run SunWattSnapshots --app`: the `dashboardSelection` kind, and a selected tallest bar (callout over it).

## Iteration 6: several plants and FusionSolar API account (first render on a Mac)
New kinds: `dashboardPlants` (3 demo plants: Home, Barn, Beach house), `settingsPlants` (2 plants), `settingsFusionSolarAPI`, `onboardingSolarAPI`. Phone and Mac light/dark, iPad, full-height phone, English and German. `AppModel(demo:)` builds the other plants' data from `settings.additionalPlants` (the sample day scaled); single-plant demos are unchanged.
- Render 1, dashboard: the solar card was an empty gap above the page dots. `ImageRenderer` draws no `ScrollView`, so the pager (and the plant tabs in Settings, same cause) were blank. In snapshots the pager shows the first page and the tabs lie in a plain row. The app keeps the scroll views.
- Render 1, API account: the system code showed in clear text, like the SolarEdge/PVOutput key still does. The system code is now a `SecureField` in the app (`AppTextField(isSecret:)`) and dots in snapshots. The other API keys are left as they were (not asked).
- Render 2 checked: page dots visible in light and dark; tabs "Home" / "Barn" readable in German, no truncation; "Weitere Anlage hinzufügen" fits; API labels fit in German; with one plant no tabs, name field or dots appear.
- Not visible in snapshots: the second tab selected and a third tab overflowing (scroll view). The Mac and iPad settings sheet is shorter than the card, so the plant tabs show only in the phone full-height PNG.
- Many existing PNGs in this folder changed in the commit: the committed ones were older than the code (Settings got longer with the provider picker). A render of the previous commit gives the same pixels as the new render for every old kind: nothing single-plant changed because of this iteration.
- Render 3 (review): in Settings the provider buttons (FusionSolar / SolarEdge / PVOutput) showed small text with SolarEdge, the API account and on Mac/iPad. The settings sheet has a fixed height in snapshots, so the long card squeezed the one-line labels vertically and `minimumScaleFactor` shrank them. The labels now keep their height (`fixedSize(vertical:)`); the scale factor still handles long names. 41 PNGs changed: these labels, and Mac/iPad settings move by a few points.

## Iteration 7: "Password (system code)" and hidden API keys (2026-10-17)
- FusionSolar's portal labels the Northbound credential "Password"; the API calls it systemCode. The field now reads "Password (system code)" (es "Contraseña (código del sistema)", de "Passwort (Systemcode)", ...); hint, help text and the two error messages use the same words. Translated per locale (Haiku LQA); es/pt first suggested an abbreviation, the full form fits the label row.
- SolarEdge/PVOutput API key fields are secret (dots), like the system code.
- Checked: onboarding API account (es), Settings API account (de, full height): labels on one line, no truncation.
- Widget guide and onboarding widget PNGs changed too: their widget previews show the new medium layout.

## 2026-10-17: Settings button in the Mac toolbar

- Critique (owner): on the Mac the Settings window was only in the app menu (and ⌘,); the dashboard had no visible
  way in. The iPhone and iPad have a gear in the header.
- Change: a gear `SettingsLink` next to Refresh in the Mac window toolbar (hidden during onboarding, like Refresh).
  The Mac title-bar mock in the renders shows it too. Other PNGs in this folder changed only because earlier
  commits (gauge centring, AM/PM times) had not been rendered here yet.
