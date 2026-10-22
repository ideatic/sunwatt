# Conventions for `:core` (binding for every later piece)

- **Time.** `java.time.Instant` for every Swift `Date`; `ZoneId` for `TimeZone`; `java.time` (`ZonedDateTime`, `LocalDate`) for every Swift `Calendar` math. A type that needs the clock takes `now: () -> Instant` (default `Instant::now`). Never call `Instant.now()` or `System.currentTimeMillis()` inside logic.
- **Durations.** `kotlin.time.Duration` (`20.minutes`). A stored interval is a `Long` of seconds (`PricePoint.durationSeconds`), as in the Swift JSON.
- **Coroutines.** Every network or inverter call is `suspend` and cancellable. A `catch (e: Exception)` or `catch (e: Throwable)` is preceded by `catch (e: CancellationException) { throw e }`. A `CancellationException` is never wrapped in a `SunWattError`.
- **Errors.** `SunWattError` is the sealed list of the Swift cases. No localized text in core; the app maps cases to strings. A message never contains a secret.
- **Stored enums.** Declared one entry per line, `NAME("rawValue"),`, last entry ending in `;`, with `val rawValue: String` and `fromRaw(raw): X?`. They serialize as the raw string through `RawValueEnumSerializer`. `scripts/check_parity.py` reads these lines.
- **JSON.** Always `SunWattJson`. Every stored field has a default (so data from older versions loads), except fields the Swift type also requires (`fetchedAt`, `points`). Property names are the stored keys and equal the Swift names (`kioskURL`, `endpointID`). Dates are ISO 8601 UTC, whole seconds (`InstantIso8601Serializer`).
- **Lenient decoding.** Only `SunWattSettings` and `SolarPlant` fall back (unknown `priceRegion` to Spain, unknown provider to FusionSolar, an unreadable `additionalPlants` list to empty). Everywhere else an unknown enum value fails, as in Swift.
- **Immutability.** Models are immutable `data class`es; Swift's mutating code becomes `copy(...)` (`withPrimaryPlant`, `updatingPlant`, `focused`).
- **Secrets.** A type that holds a kiosk link, an API key or a system code overrides `toString()` to print `***` for it. `HttpRequest.toString()` never prints the URL or headers.
- **Fixtures.** Tests read the Apple package fixtures through `Fixtures.bytes(name)` / `Fixtures.text(name)` (system property `sunwatt.fixtures`, set by `core/build.gradle.kts`). Never copy a fixture.
- **HTTP.** Code uses `HttpClient` / `HttpRequest` / `HttpResponse` and `HttpFetch`; no OkHttp type leaks out of `OkHttpSunWattClient`. Tests use `StubHttpClient`.
- **Deadlines.** `SunWattLoader.race(deadline) { ... }` is the only deadline helper (timeout -> `SunWattError.Network("timed out")`, returns even if the work ignores cancellation, cancellation is thrown). Do not write another.
- **Loader and cancellation.** `SunWattLoader.load` throws `CancellationException` (saving nothing) when its caller is cancelled; the Swift loader returns `cached()` there. One process-wide lock serializes the network part of every load.
- **Errors in data.** `SourceStatus.Error` carries `message`, `code` and `detail`; `.error` gives the `SunWattError` back so the app can localize it.
