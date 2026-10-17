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
