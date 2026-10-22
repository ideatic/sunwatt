# Decisions

Each entry: decision, why, how to change it.

## D1. SDK and deployment target: iOS 27.0 / macOS 27.0
- Xcode 27.0 (27A266a) with iOS 27.0 and macOS 27.0 SDKs is installed. The iOS 27.0 simulator runtime was not installed; it was downloaded with `xcodebuild -downloadPlatform iOS` (8 GB). Simulator used: iPhone 18 Pro (iOS 27.0).
- Change: `deploymentTarget` in `apple/project.yml` and `platforms` in `apple/Packages/SunWattKit/Package.swift`.

## D2. Project generation: XcodeGen
- `apple/project.yml` is the source of truth. XcodeGen 2.46 was installed with Homebrew. `apple/SunWatt.xcodeproj` is git-ignored and regenerated with `xcodegen generate` (also done by `apple/scripts/verify.sh`).
- Why: reproducible, reviewable diffs, no merge conflicts in pbxproj.
- Change: commit the `.xcodeproj` (remove it from `.gitignore`) if you prefer to edit in Xcode.

## D3. One local package, one library target
- `apple/Packages/SunWattKit` with folders `Models/`, `Services/`, `Store/`, `Timeline/`, `DesignSystem/`, `Views/`, `Intents/`, `Watch/`. Widget views also live in the package, so the snapshot renderer and tests can render them with `ImageRenderer` on macOS.
- Why: separate modules buy nothing at this size and add `public` churn.

## D4. Signing
- `DEVELOPMENT_TEAM` is `EX68NKELW9` (ideatic Development, S.L.U.), automatic signing. iOS Simulator builds work, App Group included.
- macOS uses a team-prefixed App Group, `EX68NKELW9.com.ideatic.sunwatt` (`Constants.appGroupID` under `#if os(macOS)`, `apple/App/SunWatt-macOS.entitlements`, `apple/Widgets/SunWattWidgets-macOS.entitlements`, set with `CODE_SIGN_ENTITLEMENTS[sdk=macosx*]`). iOS and watchOS keep `group.com.ideatic.sunwatt`.
  - Why (2026-10-17): a `group.` ID on the Mac needs a provisioning profile that lists it. The local Debug build got the generic "Mac Team Provisioning Profile: *", macOS blocked the group, the app saved settings in its private container, and the Mac widget said "Solar not set up". A team-prefixed group needs no profile entry. The Mac and iOS apps never share a container, so different IDs cost nothing.
  - The keychain group for the secrets is the same string, allowed by `keychain-access-groups` in the Mac entitlements (the team profile covers `EX68NKELW9.*`).
  - At launch the app moves settings from the private fallback into the group (`AppGroupStore.adoptSettings(from:)`) and moves any secret still in the plist into the keychain (`moveSecretsToKeychain()`).
  - Change: to go back to one ID, register `group.com.ideatic.sunwatt` for the Mac App IDs (profile with the App Group), delete the two `-macOS` entitlements files and the `#if`.
- `apple/scripts/verify.sh` builds macOS with `CODE_SIGNING_ALLOWED=NO` (compile + link only).
- The store falls back to a local container when the App Group container is unavailable, so the app still works (without sharing) when unsigned.
- Change: `DEVELOPMENT_TEAM` in `apple/project.yml` (see README).

## D5. Kiosk data facts (from the real response, 2026-10-17 01:20)
- `powerCurve.xAxis`: 288 labels "HH:mm" (5 min). `powerCurve.activePower`: 288 values, missing = `"-"` (single dash). `powerCurve.currentPower` is a string. `realKpi` came as numbers; the parser accepts numbers or strings.
- No server timestamp in the response. `SolarSnapshot.fetchedAt` = device clock; `dataAsOf` = time of the last non-nil curve point.
- Fixture `kiosk-night.json` is real, but the station name, address and id were replaced with demo values. Git history was rewritten on 2026-10-17 (`git filter-repo --replace-text`): no commit has the real values. `MORNING_REPORT.md` was removed from history at the same time.

## D6. PVPC data facts
- `time_trunc=hour` returns PVPC (id 1001) hourly (24 points) and "Precio mercado spot" (id 600) in 15-min (96 points). `quarter-hour`, `fifteen-minutes`, `minute` are rejected (400). The model still supports 15-min points (`PriceGranularity`) and aggregates to hourly.
- Tomorrow before publication returns `{"errors":[{"status":"502",...}]}`: saved as `pvpc-error-unavailable.json`. Today and tomorrow are separate requests, so a missing tomorrow never breaks today.
- Real DST fixtures: 2026-03-29 (23 points) and 2025-10-26 (25 points, two "02:00" with +02:00 and +01:00).

## D7. Kiosk URL for debug builds
- Lives in git-ignored `private/kiosk.local`. Never in source, never logged.

## D8. More countries (user request, 2026-10-17 night)
- Spain keeps the retail PVPC price (Red Eléctrica). Portugal, France, Germany+Luxembourg, Austria, Belgium, Netherlands and North Italy use day-ahead wholesale prices from Energy-Charts (`api.energy-charts.info/price?bzn=...`, Fraunhofer ISE, CC BY 4.0, no token). All 8 verified live on 2026-10-17.
- Why Energy-Charts: free, no key, one schema for many zones. Wholesale ≠ retail: the UI labels it "Day-ahead price".
- Energy-Charts rate-limits hard (HTTP 429 after a few fast requests). One request per refresh (today+tomorrow as a range). A 429 falls back to cached data.
- Portugal: a one-day request misses the last Lisbon hour (API quirk); the range request (today+tomorrow) includes it once tomorrow is published.
- Time zones: price day math uses `PriceRegion.calendar` (Lisbon for Portugal, CET for the rest). Solar curve labels use Europe/Madrid (the user's plant).
- Change: add a case to `PriceRegion` with its zone code and time zone.

## D9. Languages and number format
- en (source), es, pt, fr, de, it, nl. String catalogs: `Core.xcstrings` (data/errors/regions; not named Localizable so Xcode cannot dump untranslated keys into it; all keys `extractionState: manual`), `Widgets.xcstrings` (widget views), `App.xcstrings` (app screens), `apple/Widgets/Resources/Localizable.xcstrings` (App Intent strings). Tests check every key has all 6 translations.
- Numbers follow the user's locale (`Format.locale`, default `autoupdatingCurrent`), so Spanish users get the comma decimal separator. The spec asked for a Spanish number format; this keeps that for Spanish and stays correct for the other languages.
- Change: set `Format.locale` to a fixed locale at app start.

## D10. "Add widget" button
- iOS 27 / macOS 27 have no public API to add a widget for the user (checked `WidgetKit.swiftinterface`). The app shows a step-by-step guide and uses `WidgetCenter.currentConfigurations()` to show whether a SunWatt widget is already installed.

## D11. Network budget in widgets
- `SunWattLoader.load(deadline: 15)` races each fetch against a 15 s deadline (widget extensions get ~30 s). Dedicated ephemeral `URLSession.sunWatt` (10 s request / 12 s resource timeout, no URL cache so the kiosk token is not written to disk).

## D12. Live tests are opt-in
- `SUNWATT_LIVE=1 swift test --filter LiveTests` hits the real kiosk (URL from `private/kiosk.local`) and all 8 price regions, 15 s apart; SolarEdge and PVOutput too when `private/solaredge.local` / `private/pvoutput.local` exist. Not part of the normal run.

## D13. Product name: SunWatt (user decision)
- "HomeDash" sounded like a smart-home app. The name is now **SunWatt** (App Store name "SunWatt: Solar & Power"; 2026-10-17, was "… Power Price", changed to match the App Store Connect record).
- Full rename (user decision, before any release): display names, strings, store texts, and also code names (`SunWattKit`, targets, schemes, types, folders) and IDs (`com.ideatic.sunwatt*`, App Group `group.com.ideatic.sunwatt`). Done in one pass after all parallel branches were merged. No migration needed: nothing was released.
- Not renamed: the repo folder on disk (`.../varios/homedash`). Rename it in Finder if you want.

## D14. Shared cache window and cache identity (review fixes)
- `SunWattLoader.load(deadline:maxCacheAge:)`: with `maxCacheAge > 0`, data stored less than that many seconds ago is returned as `.fresh` without network, when every source in use is recent (solar: configured, same kiosk link, same Madrid day; prices: same region, a point covers now). Widget timelines, controls and intents use `SunWattLoader.sharedCacheWindow` (120 s); explicit refreshes (a widget or control tap opens `sunwatt://refresh`, the refresh in the app) use 0. Why: after one refresh every widget reloads at once, and N parallel requests make Energy-Charts answer 429.
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

## D24. Store texts: ASO rules (LQA + ASO review, 2026-10-17)
- No third-party trademarks or market-operator names (Huawei, FusionSolar, SolarEdge, PVOutput, EPEX, OMIE, Apple Watch, ...) in the keyword field, subtitle or name. Why: App Review guideline 2.3.7. The descriptions name the supported services, only to say what SunWatt works with.
- Keywords never repeat a word of the name or subtitle (Apple already indexes those) and fill close to 100 characters with what users of that country search.
- Wholesale prices are never sold as a retail tariff: "day-ahead"/"mercado diario"/"Börsenstrompreis"/"prix spot (day-ahead)"; Italy says it is not the PUN, Portugal avoids "preço da luz" in the name.
- Localized names keep the "SunWatt:" prefix (en-US "SunWatt: Solar & Power", the App Store Connect record name).
- Every description lists the same facts: both Control Center controls (price and solar), Watch app and complications with price and solar, several plants, optional live data at home. Siri is described, never quoted (phrases differ per language).
- Italian app strings: "link Kiosk" (never "chiosco"), price label "Day-ahead" / "Prezzo day-ahead", Siri phrases "… su SunWatt", typographic apostrophe.

## D25: A widget tap opens the app and refreshes

- Decision: tapping a widget opens the app (`widgetURL(SunWattLink.refreshURL)`, scheme `sunwatt` in apple/project.yml). The app treats it as an explicit refresh (`AppModel.open(_:)`, no cache window) and reloads the widgets. Control Center controls still refresh in place.
- Why: the in-place refresh (`Button(intent: RefreshWidgetIntent())` over the widget) only dimmed the widget for a moment; widgets cannot show a spinner, so users could not tell what was happening. The app shows the loading state and the full data.
- How to change: put the overlay button back in `SunWattWidgetEntryView` (git history has it) and the "Tap to refresh" texts.


## D26: Archives keep global symbols (`STRIP_STYLE: non-global`)

- Decision: `apple/project.yml` sets `STRIP_STYLE: non-global` for every target. `apple/scripts/check_appintents_symbols.sh` reads every `Metadata.appintents/extract.packagedata` in a built app and checks that the bundle's binary exports each listed package symbol. `apple/scripts/release.sh` runs it before export; `apple/scripts/verify.sh` (CI, every push and pull request) archives iOS and macOS unsigned and runs it too.
- Why: the app and widget metadata (`Metadata.appintents/extract.packagedata`) include `SunWattKitIntentsPackage` by its symbol name. On install, iOS (`linkd`) looks for that symbol in the binary. The archive stripped all global symbols from the app binary, so `linkd` logged "did not match any imported or exported symbol" and "Could not create bundle record for com.ideatic.sunwatt". Then no App Intent of SunWatt was known: widgets from TestFlight stayed placeholders ("No AppIntent in snapshot(for:with:)"), and Siri shortcuts failed. Debug and simulator builds are not stripped, so they worked. Seen on an iPhone with build 202610172346, 2026-10-18.
- Why an archive in CI: Debug and simulator builds are never stripped, so only an archive shows the problem. An unsigned archive with `STRIP_STYLE=all` fails the check (tested 2026-10-18).
- How to change: only with another way to keep that symbol (for example `-exported_symbol` linker flags), and the check must still pass.

## D27: Which secrets are in the keychain is written down; old installs' secrets are not used

- Decision: every settings save writes `sunwatt.keychainSecrets` (the names of the secrets now in the keychain and a new revision). The getter reads only the listed secrets from the keychain; the others come from the plist. Settings saved before this key existed read every keychain secret, as before. Without stored settings the getter returns defaults without secrets, and the app deletes all SunWatt keychain items at launch (`forgetSecretsOfEarlierInstall`). Secrets read once are reused while the plist bytes and the revision stay the same.
- Why: (1) a keychain write or delete that failed left the old keychain value, which won over the new plist value, so a changed or cleared kiosk link came back; (2) iOS keeps keychain items when the app is deleted, so a reinstall brought the old kiosk link back (seen 2026-10-18); (3) each settings read cost 2 + 2 per plant keychain round trips.
- How to change: keep the rule "the plist value wins unless the last save put the secret in the keychain". A cache must stay keyed on something every process changes on save (the revision).

## D28: FusionSolar API sessions are shared through the keychain

- Decision: `NorthboundSessionStore.shared` keeps the session (token, plant, inverters, hourly curve) in the App Group keychain, one item per account (`northbound.<sha256>`), and reads it before each use.
- Why: Huawei allows one session per account. With a session per process, the app and the widget extension ended each other's session (failCode 305) and spent the login quota, and a new widget process needed up to 6 calls one after another within the 15 s deadline. The token is a secret, so it never goes to a file (AGENTS.md rule 1).
- How to change: any other shared place must be the keychain, not the plist or the cache files.

## D29: Watch app pages: solar first, pager outside the clock

- Decision: the watch app shows a solar page per plant first, then the price page (the iPhone dashboard order). It opens on the price page only when no solar plant is set up. The vertical pager (`WatchRootView`) sits at the root of the window; each page has its own `TimelineView(.everyMinute)`. Page tags are plant ids, not positions.
- Why: on a real watch the Digital Crown and swipes did nothing while the whole pager sat inside a minute `TimelineView` (the likely cause; nothing else in the watch views takes gestures or the crown). Owner asked for solar first.
- How to change: keep the clock inside the pages. The `-price` launch argument opens on the price page for simulator captures (it replaced `-solar`).
- Tapping a bar of the watch price chart shows that hour's price, level and hour in the big figure, with the marker on that bar (`PriceBarChart.marked`, `PriceModel.showing`). A second tap, a tap on the current hour or 8 s (`WatchPricePage.selectionTimeout`) go back to now. Taps only, so vertical swipes still page.

## D30: Fronius through the Solar API on the home network

- Decision: a fifth provider, `SolarProvider.fronius` (setup `.homeNetwork`): only the inverter's address (`froniusHost`), read with `FroniusSolarAPIClient`: one GET of `/solar_api/v1/GetPowerFlowRealtimeData.fcgi` (port 80, no login) per reading, through the same `LocalInverterSolarClient` path as a Huawei plant set up with the dongle alone. `LocalInverterKind.fronius` makes "Find inverter" and "Test inverter" work (probe port 80, then the Solar API answer; other devices on port 80 are ignored). The reading carries the home consumption (`P_Load`, right also with a battery) and the grid power (`P_Grid`).
- Why: free, no account and no key. Fronius's cloud (Solar.web Query API) is paid, so there is no cloud source: away from home the app and widgets show the last data. Fronius owners who want data away from home can use PVOutput (the help text says so). The watch does not read it (like the Modbus readers; no plain HTTP in its plist).
- Limits: GEN24 inverters send no energy-today figure (`E_Day` null), so today's energy is only known from a previous reading of the day; GEN24 ships with the Solar API off (to confirm on a real inverter). Fixtures are synthetic, built from public sample answers (Symo and GEN24 shapes).
- Plain HTTP: `NSAllowsLocalNetworking` in the app and widget plists. `ModbusEndpoint` also holds the HTTP address (`defaultPort` per kind, unit ID unused). `LocalInverterReader`'s contract now covers HTTP readers (GET only, other devices read as unreachable); `HTTPInverterReaderContractTests` checks them. The "inverter did not answer" text names the Solar API too.
- How to change: a real capture from a Fronius inverter (anonymised) should replace the synthetic fixtures; the inverter archive (`GetArchiveData.cgi`) could fill today's curve and energy on GEN24.

## D31: One repo for all platforms, one folder per platform

- Decision: the Apple app lives in `apple/` (Xcode project spec, targets, `SunWattKit`, scripts, App Store files, design logs). The Android app gets `android/`. The top level keeps only what all platforms share: `docs/` (GitHub Pages serves it from the root), `private/` (secrets; Apple scripts and tests read it as `../private`), `.github/`, and the project notes (`README.md`, `AGENTS.md`, `DECISIONS.md`, `PROGRESS.md`).
- Why: both apps use the same providers, price sources, store texts and secrets, and decisions such as rate limits and time zones apply to both. Separate folders keep each build system (XcodeGen + SwiftPM, Gradle) self-contained. The files were moved with `git mv`, so `git log --follow` keeps their history.
- How to change: a new shared piece (for example common fixtures or translations) goes in a new top-level folder that both apps read. The Mac CI job skips changes only under `android/` (`paths-ignore` in `.github/workflows/verify.yml`).

## D32: Feature parity between the two apps is checked by a script

- Decision: `FEATURES.md` lists every feature with its status in each app (`done`, `planned`, `n/a` with a reason) and the contracts both apps keep. `scripts/check_parity.py` reads the Apple code (`SolarProvider`, `PriceRegion`, `LocalInverterKind`, `WidgetContent`, `PriceWindow`, `SunWattLink`, every `AppIntent`, `Widget` and `ControlWidget`) and fails when one has no row, when a status is invalid, or when Android is `done` without Android code. It runs in the CI job `Parity` (Linux, every push and pull request, also Android-only ones) and as the first step of `apple/scripts/verify.sh`. The pull request template asks for both apps.
- Why: a feature added to one app is easy to forget in the other. Exhaustive switches stop a half-done case inside one app (AGENTS.md, Architecture); this check does the same across the two apps. Text rules alone are not enough, so the check is mechanical.
- How to change: when the Android code gets its own enums for the stored values, extend the script so it also reads the Kotlin raw values and compares them with the Swift ones. Add a new source of feature IDs to `ENUMS` or `code_ids()` in the script, not as a special case in `FEATURES.md`.

## D33: Android stack and minimum version

- Decision: the Android app is Kotlin with Jetpack Compose and Material 3, Glance widgets, Quick Settings tiles for the controls, WorkManager for background refresh, coroutines, kotlinx.serialization and OkHttp (behind an own `HttpClient` interface). Build: Gradle 9.8, AGP 9.4, version catalog. `minSdk` 29 (Android 10), `targetSdk` 36. The plan is `android/PLAN.md`.
- Why: native code gives the fastest cold start and widget update, and the smallest APK. Compose and Glance are declarative like SwiftUI and WidgetKit, so screens port one by one. Coroutine cancellation maps to Swift task cancellation. `minSdk` 29 gives `java.time` without desugaring and drops old code paths, and still covers about 95 % of active devices.
- How to change: a lower `minSdk` needs core library desugaring for `java.time`. Another HTTP library must stay behind `HttpClient`.

## D34: Android logic is a pure Kotlin module that reads the Apple fixtures

- Decision: `android/core` is a Kotlin JVM library (no Android types) with the port of `SunWattKit` logic: models, solar and price clients, inverter readers, loader, timeline, formatting. Its tests read the fixtures in place from `apple/Packages/SunWattKit/Tests/SunWattKitTests/Fixtures/` (system property `sunwatt.fixtures`) and mirror the Swift tests, `ProviderContractTests` included. Stored enums are written one entry per line, `NAME("rawValue"),`, so `scripts/check_parity.py` compares their raw values with the Swift ones.
- Why: one set of fixtures keeps the parsers of both apps in step (FEATURES.md, "Fixtures"). JVM tests are fast and need no emulator. Conventions for the port (time with `java.time.Instant`, injected clock, rethrow `CancellationException`, secrets hidden in `toString`) are in `android/core/CONVENTIONS.md`.
- How to change: if the fixtures move, change the path in `android/core/build.gradle.kts` and in the Swift `FixtureLoader`.

## D35: Android strings are generated from the `.xcstrings` catalogs at build time

- Decision: the Gradle plugin `sunwatt.xcstrings` (`android/build-logic`) converts `Core`, `Widgets`, `App`, `Intents`, `Watch` (SunWattKit) and `apple/Widgets/Resources/Localizable.xcstrings` into `values*/strings_<table>.xml` for en, es, pt, fr, de, it, nl under `build/`. Resource names are `<table>_<slug of the English key>` (see `android/build-logic/README.md`). Placeholders become Android ones (`%@` → `%s`, `%lld` → `%d`, indexed when a string has more than one). A missing translation fails the build. The `InfoPlist` and Siri phrase catalogs are not converted (iOS only).
- Why: the catalogs are the single source of texts (FEATURES.md, "Texts"). Generated files in `build/` cannot be edited by hand by mistake, and a changed catalog reaches Android on the next build.
- How to change: a text only Android uses still goes in a catalog with all 7 languages. A rename of an English key changes the Android resource name, so the Kotlin code that uses it must change in the same commit.

## D36: FusionSolar API session on Android stays in the app process

- Decision: on Android the app, its widgets and its Quick Settings tiles run in one process, so one in-process `NorthboundSessionStore` holds the session. The token is kept in memory and, encrypted with the Keystore key, in the app's private storage so a new process does not log in again. The Wear OS app has its own session and keeps the 10 min fetch interval.
- Why: D28 solves a problem of iOS (app and widget extension are separate processes). Huawei allows one session per account and limits logins; one store per process keeps that rule on Android too. The token is a secret (AGENTS.md rule 1): never in a plain file, a log or an error text.
- How to change: if a widget or tile ever moves to its own process, the store must be shared across processes (for example a bound service), never through a plain file.

## D37: Android settings store, Android-only texts

- Decision: on Android the settings are one JSON file in the app's private storage, written atomically. Each secret field (kiosk link, API key, system code, the same fields of further plants) holds only its AES-GCM ciphertext; the key lives in the Android Keystore (`KeystoreSecretCipher`). A secret that cannot be decrypted (for example after a restore to a new phone) reads as empty, and the other settings stay. Caches (solar per plant, prices, failures, kiosk rejections) are plain JSON files next to it. `android:allowBackup` is off.
- Why: one atomic file cannot get out of step with the secrets, so the keychain index of D27 is not needed. Android deletes app files and Keystore keys on uninstall, so the iOS problem of D27 (old secrets after a reinstall) does not exist. Backups could not decrypt the secrets anyway.
- Texts only the Android app uses (widget guide steps, Quick Settings tiles, Android variants of iOS-worded help texts) are in `android/strings/Android.xcstrings`: the same format and the same 7 languages as the Apple catalogs, converted by the same plugin (table prefix `android`). Translated by one Haiku agent per locale, as in AGENTS.md.
- How to change: if the Apple app ever needs one of these texts, move the key to an Apple catalog and delete it here in the same change.

## D38: Shared Android UI code is a library

- Decision: `android/ui-shared` (Android library) holds what the phone app and the Wear OS app both use: `KeystoreSecretCipher` (the watch uses its own key alias), the colours, and the chart models and renderers. The renderers draw on `android.graphics.Canvas`, so the same code draws in Compose (app, watch) and into bitmaps for Glance widgets.
- Why: one chart look on the phone, the widgets and the watch, and no copied security code.
- How to change: code only one app uses stays in that app. Nothing in `ui-shared` may depend on `:app` or `:wear`.

## D39: Android release signing and version codes

- Decision: release builds are signed with an upload key in the top-level `private/` (`android_upload.jks`, passwords in `android_upload.properties`, both git-ignored). Google keeps the app signing key (Play App Signing). Without the properties file the release build stays unsigned (CI). One version for both apps in `android/gradle.properties` (`sunwatt.versionCode`, `sunwatt.versionName`); the phone bundle gets code 2n − 1 and the watch bundle 2n (n = `sunwatt.versionCode`), because the phone and watch bundles share one Play listing and every bundle needs its own code. A first try added 1,000,000,000 to the watch code; Play refuses such a jump ("version code is significantly higher"), as it eats the room left below the maximum code. Uploads go through fastlane `supply` (`android/fastlane/`), with the Play service account key in `private/google-service-account.json`.
- Why: with Play App Signing a lost upload key can be reset by Google; a lost app signing key could not. One version number keeps the two apps in step.
- How to change: to rotate the upload key, make a new one and ask Google to register it (Play Console, App integrity). Raise `sunwatt.versionCode` by one for every upload.

## D40: Store-only languages (Chinese, Polish)

- Decision: the store listings also ship in Simplified Chinese (Play `zh-CN`, App Store `zh-Hans`), Traditional Chinese (`zh-TW` / `zh-Hant`) and Polish (`pl-PL` / `pl`), while the app UI stays in its 7 languages. These listings say which languages the app has. Their Play images reuse the English screenshots with local captions. The App Store texts are ready in `apple/AppStore/metadata/` and go up with the next Apple release.
- Why: Huawei FusionSolar is widely used by Chinese-speaking owners and Poland is a large home-solar market. A local listing helps them find the app at low cost; translating the app itself is a bigger job (all catalogs, tests, both apps).
- How to change: to translate the app into one of these languages, add it to the `.xcstrings` catalogs (both apps read them), to `XcstringsConverter.TRANSLATIONS`, and retake the screenshots in that language.
