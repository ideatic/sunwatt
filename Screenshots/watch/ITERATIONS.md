# Watch snapshot iterations

Renderer: `swift run SunWattWatchSnapshots ../../Screenshots/watch` (from `Packages/SunWattKit`). Screen 208x248 pt (46 mm), 3x.
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

## Iteration 4 (colored price gauge + "Solar today" complication)
Renderer now also writes `complication-solar-<family>-<state>-<scheme>.png` (states: day, night, stale, error, unconfigured, midnight), `-dark-accented` variants for circular and corner (solar and price), and the price circular gauge. The macOS renderer draws the system gauge with the whole gradient on the track and a dot at the value; watchOS fills only up to the value. Accented renders drop colors but cannot emulate the system tint.
- Render 1: stale state replaced the sun with a clock, so the complication lost its identity; inline error text "Solar data unavailable" was cut off.
- Fix: sun/moon stays (muted when stale), the clock sits beside the data time; inline error uses a short new string "Solar unavailable" (7 languages).

## Iteration 5
- Re-rendered all families. Circular: value + "kWh" fit in the ring, sun at the gauge gap. Rectangular: headline, kW + time row, mini curve all fit at 172x76. Inline and corner fit in Spanish. Accented circular: ring position (marker dot) still reads.
- Open point: on a real watch the circular gauge label placement is decided by watchOS; check on device.
