# Widget design iterations

Rendered with `swift run HomeDashSnapshots ../../Screenshots/widgets` (from `Packages/HomeDashKit`).
The system tint of accented/vibrant modes is not simulated; those files show the monochrome hierarchy only.

## Iteration 1 (first render, 67 PNGs)
Critique of the first render:
- Accented/vibrant: chart marks turned system blue. Hierarchical styles (`.secondary`, `HierarchicalShapeStyle`) resolve to the accent color inside Charts.
- Rules, grid lines and the current-hour highlight column looked blue-gray in light mode for the same reason.
- Large, daytime: the "5,0 kW" peak label sat on top of the current-time rule and the glow dot (peak is at 13:25, now is 13:40).
- Both sources failing showed "Solar data unavailable", which is wrong when prices failed too.
- Small, night: "Sin producción" at 22 pt wrapped to two heavy lines.
- Data-time label (13:30) too faint in light mode (tertiary).
- France sample: min price printed as "-0,000".
- Console warning: Charts does not accept the default custom anchor on `AxisValueLabel`.

Changes:
- All chart marks use explicit `Color.primary` with opacity (palette and chart files).
- Peak label only shows when the peak is more than 90 minutes before the latest point, or when idle.
- New "Data unavailable" state (`SourceStateView.Kind.unavailable`) when both sources fail.
- Idle figure font 0.5 x figure size.
- Data time uses `.secondary`.
- `Format.eurPerKWh` normalizes values that round to zero. Sample data uses -3.4 EUR/MWh for the negative hour.
- `AxisValueLabel(anchor: .top)`. Warning gone.

## Iteration 2
Critique:
- Past bars (0.35) almost vanish in light mode, especially the cheap green hours.
- Future bars (0.75) look washed next to the current bar.
- Checked longer languages (de, fr), English, accented/vibrant, accessories, stale, not-configured and error states: no truncation, no overlap.

Changes:
- `Theme.Price.pastOpacity` 0.35 -> 0.42, `futureOpacity` 0.75 -> 0.82.

## Iteration 3 (tech lead review, polish pass)
Critique:
- Grey full-height column behind the current bar was louder than the bar; at cheap hours the bar is tiny.
- Dark mode: past bars turned maroon/brown (low opacity over near-black).
- Mini solar chart: the area's vertical edge under the glowing dot looked like a stem.
- Large: unit printed "EUR/KWH"; no Y scales; error state was an empty card; sample curve had teeth; fresh time next to the stale pill was ambiguous.

Changes:
- Price bars: column removed. Current bar is full opacity with a `.primary` 0.6 outline (a slightly larger rectangle behind it) and a small down triangle above it. Minimum bar height 7 % (mini) / 3.5 % (full) of the Y span, so cheap hours keep their color. Y domain gets 14 % headroom for the marker.
- Dark past bars: opacity 0.72 and the hue blended 52 % toward grey (`Theme.Price.pastColor`, `pastOpacity(for:)`). Light stays 0.42.
- `.mini` solar chart has no area fill, only line, dot and glow. `.full` keeps area and dashed now rule.
- `ChartCaption`: uppercase applies to the title only, unit stays "EUR/kWh".
- Both full charts: trailing Y axis (up to 3 values, last one with unit "kW" / "EUR"), `.caption2` secondary, grid opacity 0.07, labels in a fixed 34 pt column so both plots align.
- `SourceStateView`: symbol, title, hint, optional "Last update HH:mm". Large unavailable state keeps section headers with dimmed skeleton charts. New strings (6 languages): "Solar not set up", "Set up the kiosk in HomeDash", "Open HomeDash to check the connection", "Last update %@".
- Sample data: smooth bell with one wide Gaussian cloud dip (6 %), no sawtooth. Peak 5.3 kW.
- Fresh data time: clock symbol + secondary time, no pill. Stale: pill with `clock.arrow.circlepath` (unchanged).
- Charts console warning about custom anchors on the trailing axis fixed with `anchor: .leading`.

## Iteration 4
Critique (all 67 PNGs reviewed: light/dark, day/night/stale/error, accented/vibrant, en/de/fr):
- First dark pass was still too brown (opacity 0.62, 38 % blend). Raised to 0.72 / 52 %: dusty and clean.
- Trailing Y labels first sat on top of the bars (anchor). Fixed with `anchor: .leading`; plot now ends before the labels.
- Cloud dip 9 % made a visible shoulder in the night curve. Reduced to 6 %.
- Accented/vibrant: current bar reads by the triangle and full opacity, no hue needed. No truncation in de/fr.
- Not covered by a snapshot: "Last update" line (no scenario with cache plus failure; HomeDashSnapshots is owned by the app agent).

## Open points
- Real accented/vibrant tinting only shows on device.
