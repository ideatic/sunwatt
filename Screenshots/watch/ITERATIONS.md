# Watch snapshot iterations

Renderer: `swift run HomeDashWatchSnapshots Screenshots/watch` (from `Packages/HomeDashKit`). Screen 208x248 pt (46 mm), 3x.
Complication sizes are approximations (circular 50, rectangular 172x76, inline 190x24, corner 62); the corner arc is a stand-in for the system `widgetLabel` gauge.

## Iteration 1 (first render)
- Price page: figure, level, bars, cheapest-hour chips fit in one screen. OK.
- Solar page: curve was a fixed 60 pt tall and left a large empty area below. Fix: curve fills the free height.
- Corner stand-in: gauge arc had no visible value at the day minimum, and its track was white on the light scheme. Fix: minimum visible dot, track uses primary at 20 %.

## Iteration 2
- Solar page: the curve touched the rounded screen corners. Fix: 8 pt bottom padding.
- Checked unconfigured ("Set up SunWatt on your iPhone" + Spain hint), error, stale, night (idle), German strings: text fits, no clipping.
- Circular at 50 pt: "0,018" fits inside the gauge with minimumScaleFactor. No change.

## Iteration 3 (real watchOS 27 simulator, Series 12 46 mm, `-demo`)
Captured with `xcrun simctl io <udid> screenshot` (`sim-price.png`, `sim-solar.png`; `-solar` launch argument opens the solar page).
- System text styles render larger than in the renderer, so the 36 pt figure shrank to its scale floor. Fix: figure sizes 30 (price) and 32 (solar).
- The vertical page indicator overlapped the data time. Fix: 10 pt trailing padding on both pages.
