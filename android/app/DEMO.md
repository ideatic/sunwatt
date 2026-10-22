# Demo mode (debug builds only)

Shows the app in a fixed state with sample data and no network. Release builds ignore every option below
(`app/src/release/.../debug/DemoLaunch.kt` is an empty stub).

```sh
adb shell am start -S -n com.ideatic.sunwatt/.MainActivity --ez demo true --es scenario day --es screen dashboard
```

Use `-S` so a running app restarts: the demo state is read when the activity is created.

| Option | Values |
| --- | --- |
| `--ez demo true` | Turns the demo on. Nothing is read from or written to the store, no request is made. |
| `--es scenario` | `day` (default), `night`, `stale`, `error`, `loading`, `noSolar`, `home`, `plants`, `kioskExpiring`, `kioskExpired`, `tomorrow`, `selection`, `widgetAdded` |
| `--es screen` | `dashboard` (default), `settings`, `onboarding`, `widgetGuide`, `kioskHelp` |
| `--ei widgets <n>` | Number of widgets "added" (the widget card). `widgetAdded` is 2. |
| `--ez seed true` | Also writes the scenario into the real store (settings with a fake kiosk link `kk=DEMO0000`, demo plants, solar and prices as fetched now, moved to today). Widgets, tiles and the watch then show it. |

`seed` replaces the stored settings. Without `demo`, the app then tries the fake kiosk link on the network and falls back
to the seeded data as stale data; combine `--ez demo true --ez seed true` to keep the app itself quiet.

Scenarios: `day` 13:40 producing 5.3 kW; `night` 22:10, no production; `stale` solar fetched two hours earlier; `error` both
sources failed; `loading` placeholders; `noSolar` solar not set up; `home` with a meter (consumption, grid); `plants` three
plants (pager); `kioskExpiring` the "renew soon" notice; `kioskExpired` the expired-link card; `tomorrow` prices of two
days, starting on tomorrow; `selection` a chart point selected on both charts.

The in-memory demo uses the sample day of `SampleData` (Saturday 17 October 2026); `seed` moves the same content to today.
Screenshots of every scenario: `DashboardScreenshotTest` (`app/src/androidTest/.../ui/dashboard/`), see its header.
