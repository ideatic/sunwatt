# App design iterations

Rendered with `swift run HomeDashSnapshots --app ../../Screenshots/app` (from `Packages/HomeDashKit`), Spanish locale unless the file name ends in `-en`, `-de`, `-fr`. `phone-*` is 393x852 pt (iPhone), `mac-*` is a 900x640 pt window; `*-full` is the phone screen at natural height. `sim-*.png` are real iOS 27 simulator captures (`-demo`, iPhone 18 Pro).

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
