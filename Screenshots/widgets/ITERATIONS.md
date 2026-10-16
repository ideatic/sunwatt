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

## Open points
- Current price bar is marked by a soft full-height column behind it plus full opacity (no stroke outline). A stroke needs `chartOverlay` geometry and was not worth the fragility.
- Real accented/vibrant tinting only shows on device.
