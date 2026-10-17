# Decisions

Each entry: decision, why, how to change it.

## D1. SDK and deployment target: iOS 27.0 / macOS 27.0
- Xcode 27.0 (27A266a) with iOS 27.0 and macOS 27.0 SDKs is installed. The iOS 27.0 simulator runtime was not installed; it was downloaded with `xcodebuild -downloadPlatform iOS` (8 GB). Simulator used: iPhone 18 Pro (iOS 27.0).
- Change: `deploymentTarget` in `project.yml` and `platforms` in `Packages/SunWattKit/Package.swift`.

## D2. Project generation: XcodeGen
- `project.yml` is the source of truth. XcodeGen 2.46 was installed with Homebrew. `SunWatt.xcodeproj` is git-ignored and regenerated with `xcodegen generate` (also done by `scripts/verify.sh`).
- Why: reproducible, reviewable diffs, no merge conflicts in pbxproj.
- Change: commit the `.xcodeproj` (remove it from `.gitignore`) if you prefer to edit in Xcode.

## D3. One local package, one library target
- `Packages/SunWattKit` with folders `Models/`, `Services/`, `Store/`, `Timeline/`, `DesignSystem/`, `Views/`, `Intents/`, `Watch/`. Widget views also live in the package, so the snapshot renderer and tests can render them with `ImageRenderer` on macOS.
- Why: separate modules buy nothing at this size and add `public` churn.

## D4. Signing
- `DEVELOPMENT_TEAM` is `EX68NKELW9` (ideatic Development, S.L.U.), automatic signing. iOS Simulator builds work, App Group included.
- macOS: a `group.` App Group entitlement needs a provisioning profile, which exists only after the first `scripts/release.sh --upload` (or signing in Xcode). Until then `scripts/verify.sh` builds macOS with `CODE_SIGNING_ALLOWED=NO` (compile + link only), so the local macOS app and widget do not run with the App Group.
- The store falls back to a local container when the App Group container is unavailable, so the app still works (without sharing) when unsigned.
- Change: `DEVELOPMENT_TEAM` in `project.yml` (see README).

## D5. Kiosk data facts (from the real response, 2026-10-17 01:20)
- `powerCurve.xAxis`: 288 labels "HH:mm" (5 min). `powerCurve.activePower`: 288 values, missing = `"-"` (single dash). `powerCurve.currentPower` is a string. `realKpi` came as numbers; the parser accepts numbers or strings.
- No server timestamp in the response. `SolarSnapshot.fetchedAt` = device clock; `dataAsOf` = time of the last non-nil curve point.
- Fixture `kiosk-night.json` is real, but the station name, address and id were replaced with demo values. Git history was rewritten on 2026-10-17 (`git filter-repo --replace-text`): no commit has the real values. `MORNING_REPORT.md` was removed from history at the same time.

## D6. PVPC data facts
- `time_trunc=hour` returns PVPC (id 1001) hourly (24 points) and "Precio mercado spot" (id 600) in 15-min (96 points). `quarter-hour`, `fifteen-minutes`, `minute` are rejected (400). The model still supports 15-min points (`PriceGranularity`) and aggregates to hourly.
- Tomorrow before publication returns `{"errors":[{"status":"502",...}]}`: saved as `pvpc-error-unavailable.json`. Today and tomorrow are separate requests, so a missing tomorrow never breaks today.
- Real DST fixtures: 2026-03-29 (23 points) and 2025-10-26 (25 points, two "02:00" with +02:00 and +01:00).

## D7. Kiosk URL for debug builds
- Lives in git-ignored `Config/kiosk.local`. Never in source, never logged.

## D8. More countries (user request, 2026-10-17 night)
- Spain keeps the retail PVPC price (Red Eléctrica). Portugal, France, Germany+Luxembourg, Austria, Belgium, Netherlands and North Italy use day-ahead wholesale prices from Energy-Charts (`api.energy-charts.info/price?bzn=...`, Fraunhofer ISE, CC BY 4.0, no token). All 8 verified live on 2026-10-17.
- Why Energy-Charts: free, no key, one schema for many zones. Wholesale ≠ retail: the UI labels it "Day-ahead price".
- Energy-Charts rate-limits hard (HTTP 429 after a few fast requests). One request per refresh (today+tomorrow as a range). A 429 falls back to cached data.
- Portugal: a one-day request misses the last Lisbon hour (API quirk); the range request (today+tomorrow) includes it once tomorrow is published.
- Time zones: price day math uses `PriceRegion.calendar` (Lisbon for Portugal, CET for the rest). Solar curve labels use Europe/Madrid (the user's plant).
- Change: add a case to `PriceRegion` with its zone code and time zone.

## D9. Languages and number format
- en (source), es, pt, fr, de, it, nl. String catalogs: `Core.xcstrings` (data/errors/regions; not named Localizable so Xcode cannot dump untranslated keys into it; all keys `extractionState: manual`), `Widgets.xcstrings` (widget views), `App.xcstrings` (app screens), `Widgets/Resources/Localizable.xcstrings` (App Intent strings). Tests check every key has all 6 translations.
- Numbers follow the user's locale (`Format.locale`, default `autoupdatingCurrent`), so Spanish users get the comma decimal separator. The spec asked for a Spanish number format; this keeps that for Spanish and stays correct for the other languages.
- Change: set `Format.locale` to a fixed locale at app start.

## D10. "Add widget" button
- iOS 27 / macOS 27 have no public API to add a widget for the user (checked `WidgetKit.swiftinterface`). The app shows a step-by-step guide and uses `WidgetCenter.currentConfigurations()` to show whether a SunWatt widget is already installed.

## D11. Network budget in widgets
- `SunWattLoader.load(deadline: 15)` races each fetch against a 15 s deadline (widget extensions get ~30 s). Dedicated ephemeral `URLSession.sunWatt` (10 s request / 12 s resource timeout, no URL cache so the kiosk token is not written to disk).

## D12. Live tests are opt-in
- `SUNWATT_LIVE=1 swift test --filter LiveTests` hits the real kiosk (URL from `Config/kiosk.local`) and all 8 price regions, 15 s apart; SolarEdge and PVOutput too when `Config/solaredge.local` / `Config/pvoutput.local` exist. Not part of the normal run.

## D13. Product name: SunWatt (user decision)
- "HomeDash" sounded like a smart-home app. The name is now **SunWatt** (App Store name "SunWatt: Solar & Power Price").
- Full rename (user decision, before any release): display names, strings, store texts, and also code names (`SunWattKit`, targets, schemes, types, folders) and IDs (`com.ideatic.sunwatt*`, App Group `group.com.ideatic.sunwatt`). Done in one pass after all parallel branches were merged. No migration needed: nothing was released.
- Not renamed: the repo folder on disk (`.../varios/homedash`). Rename it in Finder if you want.

## D14. Shared cache window and cache identity (review fixes)
- `SunWattLoader.load(deadline:maxCacheAge:)`: with `maxCacheAge > 0`, data stored less than that many seconds ago is returned as `.fresh` without network, when every source in use is recent (solar: configured, same kiosk link, same Madrid day; prices: same region, a point covers now). Widget timelines, controls and intents use `SunWattLoader.sharedCacheWindow` (120 s); `RefreshWidgetIntent` and explicit refreshes in the app use 0. Why: after one refresh every widget reloads at once, and N parallel requests make Energy-Charts answer 429.
- The app no longer reloads the widgets right after a settings change that needs a fetch (region, kiosk link, tomorrow prices); `AppModel.refresh()` reloads them once the new data is stored.
- `SolarSnapshot.endpointID` = SHA-256 of kiosk host + token (`KioskEndpoint.cacheID`, never the token). A cached snapshot with another or no identifier is ignored (old caches are refetched once).
- After midnight (Europe/Madrid) a snapshot from an earlier day shows no curve, power or daily energy (`SunWattEntry.solarForDisplay`) and is marked stale.
- Not done: requests in flight are not shared between widgets started at the same instant on a cold cache; only the window protects them.

## D15. More solar providers: SolarEdge and PVOutput (user request, 2026-10-17)
- `SunWattSettings.solarProvider` (`fusionSolar` default, `solarEdge`, `pvOutput`) + `solarSiteID` + `solarAPIKey`. Old stored settings decode as FusionSolar; an unknown provider value falls back to FusionSolar. `kioskURL` stays, so switching back keeps the link.
- Why these two: both have an official, documented, free API that works with an ID + key, no OAuth. SolarEdge is the biggest brand after Huawei in Spain; PVOutput covers many other brands (Fronius, SMA, Enphase, GoodWe, Growatt…) through their uploaders. Not done: Fronius local API (home Wi-Fi only, needs HTTP + local network permission), Home Assistant (needs remote access), Enphase/SMA (OAuth app registration).
- SolarEdge: `GET /site/{id}/overview` (power now, energy day/month/year/lifetime, last update) and `GET /site/{id}/power` for today (15-minute curve), sent together. Key in the `api_key` query item (that is how the API works); redacted from error messages. 401/403 → `SunWattError.solarCredentialsRejected`.
- SolarEdge limit is 300 requests a day per site. `SolarProvider.minimumFetchInterval` = 20 min: the loader reuses a snapshot of the same account younger than that, even on an explicit refresh. At most 144 requests a day per device, so iPhone + Watch together stay under 300.
- PVOutput: `GET /service/r2/getstatus.jsp?h=1&limit=288&asc=1`, key and system ID in `X-Pvoutput-Apikey` / `X-Pvoutput-SystemId` headers. "No status found" (400, before the first upload of the day) is a day without production. "Exceeded … requests" (403) is a rate limit, not a wrong key. "Power now" is the latest status if it is at most 30 min old, else 0.
- Both send local site times without a zone; they are read in the device time zone (the plant is almost always where the phone is). The charts still use Europe/Madrid (D8), the same as for FusionSolar.
- Cache identity: `SolarAccount.cacheID` = SHA-256 of provider + ID + key (FusionSolar keeps `KioskEndpoint.cacheID`, so existing caches stay valid). A snapshot from another provider or account is never shown.
- One ID + key pair is stored, not one per provider. Choosing SolarEdge or PVOutput after any other provider clears it (a key never works on the other service). Switching away and back means typing the key again.
- Known edge: the 20-minute reuse and the stale checks use the Madrid day; outside that time zone (Portugal, Canary Islands) the hour around midnight can show the previous day's snapshot a little longer.
- Test data: SolarEdge and PVOutput fixtures are synthetic, written from the documented formats (no real account was available). Run a live check with a real site before release.
- Change: add a case to `SolarProvider` and `SolarAccount` (see AGENTS.md, "Adding things").

## D16. Home consumption, only with a meter at home (user request, 2026-10-17)
- `SolarSnapshot.home: HomeEnergy?`: consumption now, grid now (+ buying, − selling), today's consumption, import and export, consumption curve, and self-sufficiency (1 − import / consumption). Nil when the plant has no meter at home: export-only plants (like the developer's) and the FusionSolar kiosk show nothing new. User rule: no consumption numbers unless the plant reports them.
- SolarEdge: the curve request is now `powerDetails` (meters Production, Consumption, FeedIn, Purchased, SelfConsumption) instead of `power`, so still 2 requests per fetch. Without a Production meter in the answer it falls back to `power` (one more request). "Now" is the latest 15-minute average if at most 30 min old; today's totals are the 15-minute values summed.
- PVOutput: the history's consumption columns (energy Wh, power W) when the uploader sends them. Import and export are worked out from the power readings of consecutive statuses (gaps over 30 min skipped).
- Shown in the app's solar card (home now, buying/selling, used today and share from the panels) and as one line next to "Production" in the large widget. Not in small/medium widgets, Lock Screen, watch or Siri yet.
- Test data is synthetic (SolarEdge format from the open-source client `clambin/solaredge`, PVOutput columns from `openclimatefix/pvoutput`).

## D17. FusionSolar kiosk link expiry (user request, 2026-10-17)
- Kiosk links last one year. Huawei's exact answer for an expired link is not documented anywhere we found, so any rejection counts: `success: false` or HTTP 401/403/404 → `SunWattError.kioskLinkRejected` (5xx and network errors do not count).
- One rejection can be a passing problem at Huawei. The loader records when rejections started (`AppGroupStore.noteKioskRejected`, per link) and only after `SunWattLoader.kioskExpiredAfter` (30 min) sets `SunWattData.solarLinkExpired`: cached data is then dropped and every surface asks for a new link (app card with "Replace link", widget "Kiosk link expired", watch, Siri). Any success, or a different link, ends it. Before 30 min the cached data is shown as stale, as for other errors.
- Advance notice: `SunWattSettings.kioskLinkSavedAt` is set whenever a valid link is saved (and on first launch for links saved before this existed). From 30 days before saved + 1 year the dashboard shows "Your kiosk link may stop working around <date>" with "Renew link"; the user can close it (`kioskExpiryNoticeDismissed`, reset with a new link). The real expiry can be earlier: Huawei counts from when the kiosk was turned on, not from when it was pasted in SunWatt.
- To do when a real expired (or turned-off) kiosk response is captured: add it as a fixture and narrow the detection if needed.

## D18. Huawei inverter on the home network (Modbus TCP) (user request, 2026-10-17)
- Optional for FusionSolar: Settings → "Live data at home" → the dongle's IP address. `SolarAccount.fusionSolar(kiosk, local:)` (since D23 the dongle goes through the same `withLocal` path as every provider); `LocalInverterSolarClient` reads the inverter (5 s limit) and the kiosk together. Inverter answers → its power, today's and total energy replace the kiosk's, a curve point is added, `SolarSnapshot.isLive` (dashboard "Live" badge). No answer (away, Modbus off) → the kiosk snapshot as before. Cache ID stays the kiosk's.
- Protocol: Modbus TCP port 502, function 0x03 only (read-only), three requests: 32080 (+10: power I32 kW/1000, status 32089), 32106 (+10: total 32106 and today 32114, U32 kWh/100), 37100 (+15: meter status, meter power 37113 I32 W, > 0 feeding the grid; skipped without a meter). Network.framework `NWConnection`, 1 s pause after connecting (Huawei dongles drop requests sent right away). Unit ID: "Test inverter" tries the saved one, 1 and 0 and stores the one that answers.
- Needs Modbus TCP "Enable (unrestricted)" on the dongle (installer setting). The developer's plant: SUN2000-15KTL-M5 + SDongleA-05 (V200R022C10SPC103), not yet tested on the real device.
- While the app is open, `AppModel.runLive()` re-reads every 10 s (curve: one point a minute, not stored). Widgets use it on their normal refresh. watchOS: no raw TCP for apps, the watch keeps the kiosk.
- Local Network permission: `NSLocalNetworkUsageDescription` (app and widget extension), localized in `InfoPlist.xcstrings`.
- Known limit: at home with the inverter answering, an expired kiosk link is not flagged (live data keeps working); it shows away from home.
- "Find inverter" (`HuaweiInverterFinder`): Huawei dongles do not announce themselves (no Bonjour/mDNS), so the app scans. Addresses: the IPv4 subnets of the Wi-Fi/Ethernet interfaces (`getifaddrs`, names `en…`), narrowed to the /24 around the device (a /16 would be 65,000 addresses). Each address gets a TCP connect to port 502 (1.2 s limit, 48 at a time, about 6 s for a /24); the few that accept are read like an inverter (model 30000…30014, then the normal reading; unit ID 1, then 0). Only devices that answer that read are offered; one match is filled in and saved, several are listed. Read-only like the live data. Nothing found → help text (same Wi-Fi, Modbus TCP enabled, Local Network permission; the first scan triggers the iOS permission prompt, during which connections fail, so a second tap may be needed). To change: `HuaweiInverterFinder.concurrency`, `probeTimeout`, `neighbours(address:mask:)`.

## D19. Full code review fixes (2026-10-17)
- Secrets: the kiosk link and the SolarEdge/PVOutput key are stored in the keychain (`KeychainSecretStore`, generic password, service `com.ideatic.sunwatt`, access group = App Group so the widgets read it, readable after first unlock, not synced). The settings plist keeps them blank. When the keychain cannot be used (unsigned/CI builds without the group) they stay in the plist as before. Older installs are read from the plist and moved on the next save. The watch gets them in the settings sync (WatchConnectivity) and stores them in its own keychain.
- SolarEdge request budget: a failed fetch is remembered per account (`AppGroupStore.noteSolarFailure`); for `minimumFetchInterval` (20 min) refreshes reuse its message instead of asking again. "Test connection" is the fetch: it cancels the refresh scheduled by saving the fields, stores its snapshot (or failure) and refreshes without new solar requests. The shared cache window accepts solar data the provider would not fetch again yet, so SolarEdge users' widgets no longer all fetch prices at once.
- Watch app: activations and the periodic refresh use the shared cache window (each wrist raise restarted a full fetch); a settings change still fetches at once.
- Store writes: compare-and-write under `NSFileCoordinator` (app and widget extension are separate processes); a stored time more than 10 min in the future (device clock moved back) no longer blocks writes.
- Smaller: today's "Cheapest upcoming hours" ranks today's hours only; Siri says "tomorrow" for tomorrow's hours; the price chart keeps 0 in range when every price is negative; month/year totals of an earlier month/year are not shown as the current ones after midnight; docs give the right `check_metadata.py` command.

## D20. SunSpec inverters on the home network (user request, 2026-10-17)
- Optional for SolarEdge and PVOutput: Settings → "Live data at home" → the inverter's address (`SunWattSettings.sunSpecHost`, "host" or "host:port"; `sunSpecUnitID`). `SolarAccount.solarEdge/pvOutput(credentials, local:)`; the address is not part of `cacheID`, so adding it keeps the cloud cache. An address that does not parse is ignored (the cloud keeps working), like the Huawei one.
- Protocol: SunSpec over Modbus TCP, read-only (`SunSpecClient`): marker "SunS" at 40000 (else 0, 50000), walk the model chain, model 1 (manufacturer + model name) and the first inverter model, 101–103 (integers with scale factors) or 111–113 (floats). Power and the lifetime counter only. Covers SolarEdge (port 1502, unit 1, turned on in SetApp/by the installer), SMA (502, unit 126), Fronius (502, unit 1, int+SF or float), Kostal, ABB/FIMER and others. "Test inverter" and "Find inverter" try ports 502 and 1502 and unit IDs 1, 126, 2, and save the port and unit that answered.
- SunSpec has no "today" register: today's energy is the cloud figure plus what the lifetime counter grew since the last live reading of the same day (`LocalInverterSolarClient.dailyEnergy`, never less than the cloud's). Not computed across days or when the counter goes back.
- Meters (SunSpec models 201–204/211–214) are not read: sign conventions differ between brands and meters often sit on another unit ID. The cloud's home consumption is kept next to the live power (`keepsCloudHome`).
- SolarEdge's request limit stays: while the cloud may not be asked (`minimumFetchInterval`, or a failure younger than it), the loader still reads the inverter alone (`SunWattLoader.makeLiveSolar`, `SolarAccount.liveOnlyClient()`). The limit counts from `SolarSnapshot.cloudFetchedAt`, since every live read moves `fetchedAt`. When the combined fetch gets only the inverter (cloud failed), the failure is noted so the cloud is not retried before the interval. In the shared cache window a live snapshot counts only for the window itself, so widgets at home keep reading the inverter.
- Generic pieces: `ModbusEndpoint` (was `HuaweiLocalEndpoint`, kept as a typealias), `LiveInverterReading`, `LocalInverterReader` (Huawei and SunSpec), `ModbusSession`, `LocalInverterSolarClient` (was `HuaweiLocalSolarClient`), `InverterFinder` + `LocalInverterKind`.
- Known limits: one inverter per address (multi-inverter SolarEdge sites show the one read live); not tested on real SunSpec hardware yet; the watch keeps the cloud.

## D21. Provider contracts and profiles (user request, 2026-10-17)
- The contracts of `SolarDataSource`, `PriceDataSource` and `LocalInverterReader` (and `ModbusTransport`) are written on the protocols: snapshot/series shape (sorted, one day, never negative production, `fetchedAt` = client clock, loader-owned fields left alone), allowed errors (`CancellationError` unwrapped, otherwise `SunWattError`, with what each case means), no secrets in error texts, time budget, read-only inverters.
- `ProviderContractTests` runs the same checks on every provider: solar clients against their fixtures (plus the Huawei and SunSpec live clients), price sources for every Energy-Charts fixture and PVPC, inverter readers against fake devices. A new provider adds one case and gets the checks.
- Production is clamped to ≥ 0 in the cloud clients (`SolarSnapshot.clampingProduction()`): inverters report a standby draw at night as small negative power.
- `SolarProviderProfile` (`SolarProvider.profile`) holds what distinguishes providers (names, setup kind, portal, request limit, local inverter kind, whether the cloud's home consumption is kept). Code asks the profile (`setup == .kioskLink`, `keepsCloudHome`, …) instead of comparing with `.fusionSolar`; per-provider UI texts are exhaustive `switch`es so a new case does not compile until it has them. `SolarAccount.api(_:_:local:)` builds the account of an ID + key provider.
- The inverter error texts no longer assume a Huawei dongle (they are shown to SunSpec users too).
- Prices the same way (D21, follow-up): `PriceRegionProfile` (`PriceRegion.profile`: time zone, flag, `PriceSource`) and `PriceSource` (`.redElectrica`, `.energyCharts(zone:)`: retail or wholesale, publisher name, licence, client). Code and texts switch on the source instead of comparing with `.spain`; `PriceRegionProfileContractTests` checks every region's profile against its client and that every kind of source has a contract case.

## D22. Several solar plants (user request, 2026-10-17)
- `SolarPlant` describes one plant (provider, kiosk link or ID + key, inverter on the home network, name). The first plant stays in the original settings fields (`SunWattSettings.primaryPlant`), so a single-plant install keeps its settings, keychain items and `solar.json`; further plants are `additionalPlants`, with keychain items `plant.<id>.kioskURL` / `.apiKey` and caches `solar-<id>.json`. Failures and kiosk rejections are stored per account / link.
- Code written for "the" plant serves every plant: `SunWattSettings.focused(on:)` puts a plant in the first place (loader, accounts, editors via `AppModel.settings(focusedOn:)` / `updatePlant`).
- The loader loads all plants at once, each with its own cache, limits and failures. `SunWattData` shows one plant (`solar`, `solarStatus`, `plantID`, `plantName`) and lists the rest in `otherPlants`; `showingPlant(at:)` switches. With one plant `otherPlants` is empty and `plantName` nil: every surface reads as before.
- Widgets: a "Solar plant" parameter ("All plants, in turn" by default, or one plant). Rotating widgets get an entry every quarter hour (`TimelineBuilder.rotationInterval`), the plant's turn worked out from the time alone so all widgets agree; the plant name is shown next to the solar icon. A pinned plant keeps the hourly entries. With one plant the parameter has no effect (the Edit Widget sheet still lists it: widget parameters cannot be hidden at run time).
- App: the dashboard's solar card becomes a horizontal pager (page dots) with several plants; Settings gets "Add another plant", tabs per plant, a name field and "Remove this plant" (the first plant cannot be removed, only changed). Live inverter reads in the app follow the first plant only.
- Siri and the solar control give the total, then each plant ("By plant: …"); the watch app has a solar page per plant and its complications show the total (`SunWattEntry.combinedPlants`, `combinedSolarForDisplay`, which drop a plant's yesterday figures like `solarForDisplay`).

## D23. FusionSolar API account (Northbound API) (user request, 2026-10-17)
- A fourth provider, `SolarProvider.fusionSolarAPI`, shown in Settings as a second way to connect FusionSolar ("Kiosk link" / "API account"; `SolarProviderProfile.pickerGroup`). The installer creates the account in FusionSolar (Company Management → Northbound Management). Unlike a kiosk link it does not expire.
- Stored in the existing plant fields: user name in `siteID`, system code in `apiKey` (keychain, never in the plist), server in `northboundHost` (empty: `eu5.fusionsolar.huawei.com`). `NorthboundCredentials` accepts only `*.fusionsolar.huawei.com` servers, so the code is never sent elsewhere.
- `NorthboundClient`: log in, keep the session token in memory (`NorthboundSessionStore`, not on disk), log in again once when the session ended (failCode 305). One fetch: station KPI (today, month, total) + inverter power now (getDevList once, then getDevRealKpi), and the hourly curve at most once an hour (a failed curve keeps the old one). Rate limit (failCode 407) → `SunWattError.rateLimited`; fetches limited to one every 10 minutes (`minimumFetchInterval`).
- The Huawei dongle on the home network works with it as with the kiosk link.
- Not tested against a real account (no account available); fixtures are synthetic, built from Huawei's interface documentation. To change the interfaces read, edit `NorthboundClient.fetch()` and its fixtures.
