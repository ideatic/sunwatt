# AppViewModel API (`ui/AppViewModel.kt`)

Get it in any screen: `val vm = LocalAppViewModel.current` (provided by `AppRoot`). Screen signatures do not change.
State: `val ui by vm.state.collectAsStateWithLifecycle()` gives `AppUiState` (`ui/model/AppUiState.kt`): `isReady`, `settings`,
`data`, `now`, `isLoading`, `hasLoaded`, `widgetCount`, `showsOnboarding`, `isDemo`, `entry`, `isSolarConfigured`,
`kioskExpiryNoticeDate`. `vm.settings` is the current `SunWattSettings`. `vm.isDemo`.

Settings (all immutable `copy`, no suspend; saved in the background, widgets and data are refreshed when needed):
- `updateSettings { s -> s.copy(...) }`: saves; a change of region, of a valid account or of "something typed" triggers a
  refresh 1.2 s later (debounced); `showTomorrowPrices` too. A new kiosk link gets `kioskLinkSavedAt = now`.
- `completeOnboarding()`, `dismissKioskExpiryNotice()`.
- Plants: `settingsFocusedOn(id)` (the plant as "the" solar settings), `updatePlant(id) { s -> s.copy(...) }`,
  `addPlant(): String` (new id), `removePlant(id)`. The first plant id is `SolarPlant.PRIMARY_ID`.
- Inverter fields per kind (`ui/model/LocalInverterFields.kt`): `settings.localInverterHost(kind)`,
  `.withLocalInverterHost(kind, host)`, `.localInverterUnitID(kind): Int?` (null for Fronius), `.withLocalInverterUnitID(kind, id)`.

Connection tests (suspend, return `kotlin.Result`; failures are `SunWattError`; show with `error.textRes()`):
- `testKiosk(text)`, `testSolarAPI(provider, siteID, apiKey)`, `testNorthbound(host, userName, systemCode)`: `Result<SolarSnapshot>`.
  For a saved account the result is stored and the dashboard refreshes without asking the provider again.
- `localInverterKind(plantId = PRIMARY_ID): LocalInverterKind`.
- `testInverter(address, plantId = PRIMARY_ID): Result<InverterFinder.Match>` (`address` text to save, `unitID`, `model`, `reading`).
- `findInverters(plantId = PRIMARY_ID): List<InverterFinder.Match>` (scans the device's Wi-Fi/Ethernet subnet).

Data (the dashboard and AppRoot call these; settings screens rarely need them): `refresh(maxCacheAge = ZERO)` (explicit when
zero), `start()`, `onResume()`, `open(SunWattLink)`, `runClock()`, `runLive()`, `refreshWidgetCount()`.

Demo (debug builds): `vm.demo` is non-null; every test returns sample data and nothing is saved.
Widgets: after data or settings change the VM calls `WidgetUpdates.requestUpdate(context)` (`ui/model/WidgetUpdates.kt`).
Needs `android.permission.ACCESS_NETWORK_STATE` (added to the manifest) for "Find inverter".
