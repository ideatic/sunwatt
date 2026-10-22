import Foundation
import Testing
@testable import SunWattKit

// MARK: Fake SunSpec inverter

/// Registers of `text` as SunSpec string registers (two ASCII characters each, zero padded to `count`).
func sunSpecText(_ text: String, count: Int) -> [UInt16] {
    var bytes = Array(text.utf8)
    bytes += Array(repeating: 0, count: max(0, count * 2 - bytes.count))
    return (0..<count).map { UInt16(bytes[2 * $0]) << 8 | UInt16(bytes[2 * $0 + 1]) }
}

/// A SunSpec map at `base`: common model (manufacturer, model) and an inverter model, 2.912 kW and 18,420 kWh in total.
/// `float`: model 113 (Fronius style) instead of 103 with scale factors (SolarEdge style).
func fakeSunSpecRegisters(base: UInt16 = 40000, float: Bool = false, powerW: Double = 2912, lifetimeWh: Double = 18_420_000) -> [UInt16: UInt16] {
    var values: [UInt16] = [0x5375, 0x6E53]
    let common = sunSpecText(float ? "Fronius" : "SolarEdge", count: 16)
        + sunSpecText(float ? "Fronius Symo 8.2-3-M" : "SE5000H-RW000BNN4", count: 16)
        + Array(repeating: 0, count: 33)
    values += [1, UInt16(common.count)] + common
    var inverter = [UInt16](repeating: 0, count: float ? 60 : 50)
    if float {
        let w = Float(powerW).bitPattern, wh = Float(lifetimeWh).bitPattern
        inverter[20] = UInt16(w >> 16); inverter[21] = UInt16(w & 0xFFFF)
        inverter[30] = UInt16(wh >> 16); inverter[31] = UInt16(wh & 0xFFFF)
    } else {
        // W with scale factor -1 (29120 × 0.1), WH with scale factor 0.
        inverter[12] = UInt16(bitPattern: Int16(powerW * 10))
        inverter[13] = UInt16(bitPattern: -1)
        let wh = UInt32(lifetimeWh)
        inverter[22] = UInt16(wh >> 16); inverter[23] = UInt16(wh & 0xFFFF)
        inverter[24] = 0
    }
    values += [float ? 113 : 103, UInt16(inverter.count)] + inverter
    values += [0xFFFF, 0]
    var registers: [UInt16: UInt16] = [:]
    for (i, value) in values.enumerated() { registers[base + UInt16(i)] = value }
    return registers
}

private let edgeCredentials = SolarAPICredentials(siteID: "1234567", apiKey: "ABCDEFGH12345678")!

@Suite struct SunSpecTests {
    private func client(_ registers: [UInt16: UInt16], unit: UInt8 = 1) -> SunSpecClient {
        SunSpecClient(endpoint: ModbusEndpoint(host: "x", port: 1502), makeTransport: { _ in FakeModbusTransport(registers: registers, unit: unit) })
    }

    @Test func readsTheIntegerModel() async throws {
        let (model, reading) = try await client(fakeSunSpecRegisters()).identifyLive()
        #expect(model == "SolarEdge SE5000H-RW000BNN4")
        #expect(abs(reading.activePowerKW - 2.912) < 1e-9)
        #expect(reading.totalEnergyKWh == 18420)
        #expect(reading.dailyEnergyKWh == nil && reading.meterActivePowerKW == nil)
    }

    @Test func readsTheFloatModel() async throws {
        let (model, reading) = try await client(fakeSunSpecRegisters(float: true)).identifyLive()
        // The model name already starts with the manufacturer: not repeated.
        #expect(model == "Fronius Symo 8.2-3-M")
        #expect(abs(reading.activePowerKW - 2.912) < 1e-6)
        #expect(reading.totalEnergyKWh == 18420)
    }

    @Test func findsTheMarkerAtOtherBases() async throws {
        let reading = try await client(fakeSunSpecRegisters(base: 0)).readLive()
        #expect(abs(reading.activePowerKW - 2.912) < 1e-9)
    }

    @Test func otherDevicesAreNotSunSpec() async {
        // A Huawei dongle answers Modbus but has no SunSpec marker.
        await #expect(throws: ModbusFrame.Failure.unexpectedDevice) { try await client(fakeInverterRegisters()).readLive() }
        // Wrong unit ID: Modbus exceptions only.
        await #expect(throws: ModbusFrame.Failure.unexpectedDevice) { try await client(fakeSunSpecRegisters(), unit: 126).readLive() }
        let down = SunSpecClient(endpoint: ModbusEndpoint(host: "x"), makeTransport: { _ in FakeModbusTransport(registers: [:], reachable: false) })
        await #expect(throws: SunWattError.inverterUnreachable) { try await down.readLive() }
    }

    @Test func notImplementedValues() {
        var data = [UInt16](repeating: 0, count: 50)
        data[12] = 0x8000
        #expect(SunSpecClient.inverterReading(model: 103, data: data) == nil)
        data[12] = 500
        data[13] = 0x8000
        #expect(SunSpecClient.inverterReading(model: 101, data: data) == nil)
        data[13] = UInt16(bitPattern: 1)
        // Lifetime counter 0: not implemented.
        #expect(SunSpecClient.inverterReading(model: 101, data: data) == LiveInverterReading(activePowerKW: 5))
        #expect(SunSpecClient.inverterReading(model: 103, data: Array(data.prefix(10))) == nil)
        var floats = [UInt16](repeating: 0, count: 60)
        floats[20] = 0x7FC0
        #expect(SunSpecClient.inverterReading(model: 113, data: floats) == nil)
        #expect(abs((SunSpecClient.scaled(12, UInt16(bitPattern: -2)) ?? 0) - 0.12) < 1e-12)
        #expect(SunSpecClient.scaled(12, 40) == nil)
    }

    // MARK: Today's energy from the lifetime counter

    @Test func todayFromTheLifetimeCounter() {
        var cloud = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12, 0))
        cloud.dailyEnergyKWh = 4
        let first = LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW: 3, totalEnergyKWh: 18420), into: cloud, at: madrid(2026, 6, 1, 12, 5), keepsBaseHome: true)
        #expect(first.dailyEnergyKWh == 4 && first.cumulativeEnergyKWh == 18420)
        #expect(first.cloudFetchedAt == madrid(2026, 6, 1, 12, 0))
        #expect(first.home == cloud.home)
        let second = LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW: 3, totalEnergyKWh: 18420.5), into: first, at: madrid(2026, 6, 1, 12, 15))
        #expect(second.dailyEnergyKWh == 4.5)
        #expect(second.cloudFetchedAt == madrid(2026, 6, 1, 12, 0))
        // A new day: no figure until the cloud gives one.
        #expect(LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW: 0, totalEnergyKWh: 18421), into: second, at: madrid(2026, 6, 2, 0, 5)).dailyEnergyKWh == nil)
        // A counter that went back adds nothing.
        #expect(LocalInverterSolarClient.dailyEnergy(lifetimeKWh: 100, at: madrid(2026, 6, 1, 12, 20), previous: second) == 4.5)
    }

    @Test func fillingInKeepsCloudFiguresForAnInverterAlone() {
        var cloud = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12, 0))
        cloud.dailyEnergyKWh = 4
        cloud.home = HomeEnergy(consumptionKW: 0.8, gridKW: -2, dailyConsumptionKWh: 6)
        let alone = LocalInverterSolarClient.merge(LiveInverterReading(activePowerKW: 3, totalEnergyKWh: 18420), into: nil, at: madrid(2026, 6, 1, 12, 5), keepsBaseHome: true)
        let filled = SunWattLoader.fillingIn(alone, from: cloud)
        #expect(filled.dailyEnergyKWh == 4)
        #expect(filled.home == cloud.home)
        #expect(filled.cloudFetchedAt == madrid(2026, 6, 1, 12, 0))
        #expect(filled.stationName == "Casa")
    }

    // MARK: Account

    @Test func solarEdgeWithAnInverter() throws {
        var settings = SunWattSettings(solarProvider: .solarEdge, solarSiteID: "1234567", solarAPIKey: "ABCDEFGH12345678")
        let cloudOnly = try #require(SolarAccount(settings: settings))
        #expect(cloudOnly.localInverter == nil && cloudOnly.liveOnlyClient() == nil)
        settings.sunSpecHost = "192.168.1.40:1502"
        let account = try #require(SolarAccount(settings: settings))
        #expect(account == .solarEdge(edgeCredentials, local: ModbusEndpoint(host: "192.168.1.40", port: 1502)))
        // Adding the inverter keeps the cloud cache.
        #expect(account.cacheID == cloudOnly.cacheID)
        #expect(account.client() is LocalInverterSolarClient)
        #expect(account.liveOnlyClient() is LocalInverterSolarClient)
        #expect(account.localReader() is SunSpecClient)
        settings.solarProvider = .pvOutput
        #expect(SolarAccount(settings: settings)?.localInverter?.port == 1502)
        // An address that does not parse is ignored: the cloud keeps working.
        settings.sunSpecHost = "not an address!"
        #expect(SolarAccount(settings: settings) == .pvOutput(edgeCredentials))
        // FusionSolar does not read the SunSpec address.
        settings.solarProvider = .fusionSolar
        settings.kioskURL = testKioskURL
        settings.sunSpecHost = "192.168.1.40"
        #expect(SolarAccount(settings: settings)?.localInverter == nil)
    }

    @Test func settingsWithoutSunSpecFieldsStillDecode() throws {
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: Data(#"{"kioskURL":"x"}"#.utf8))
        #expect(settings.sunSpecHost.isEmpty && settings.sunSpecUnitID == 1)
    }

    @Test func endpointText() {
        #expect(ModbusEndpoint(host: "192.168.1.40", port: 1502).text == "192.168.1.40:1502")
        #expect(ModbusEndpoint(host: "192.168.1.40").text == "192.168.1.40")
        #expect(ModbusEndpoint.hasPort("192.168.1.40:502") && !ModbusEndpoint.hasPort(" 192.168.1.40 "))
    }
}

// MARK: Loader with SolarEdge's request limit

@Suite struct SunSpecLoaderTests {
    private let now = madrid(2026, 6, 1, 12, 30)

    private func settings() -> SunWattSettings {
        SunWattSettings(showTomorrowPrices: false, solarProvider: .solarEdge, solarSiteID: "1234567", solarAPIKey: "ABCDEFGH12345678",
                        sunSpecHost: "192.168.1.40:1502")
    }

    private func inverter(_ registers: [UInt16: UInt16]?) -> SunSpecClient {
        SunSpecClient(endpoint: ModbusEndpoint(host: "192.168.1.40", port: 1502), makeTransport: { _ in
            registers.map { FakeModbusTransport(registers: $0) } ?? FakeModbusTransport(registers: [:], reachable: false)
        })
    }

    @Test func inverterIsReadWhileTheCloudWaits() async {
        let (store, _) = storeTempStore()
        store.settings = settings()
        let id = SolarAccount(settings: settings())!.cacheID
        var cloud = storeSampleSolar(fetchedAt: now.addingTimeInterval(-5 * 60), endpointID: id)
        cloud.dailyEnergyKWh = 4
        store.saveSolar(cloud)
        let cloudSource = CallCountingSolar(.success(storeSampleSolar(fetchedAt: now)))
        let local = inverter(fakeSunSpecRegisters())
        let loader = { (date: Date) in
            SunWattLoader(store: store, makeSolar: { _ in cloudSource }, prices: FakePlain(result: .success(storeSamplePrices())), now: { date },
                          makeLiveSolar: { _ in LocalInverterSolarClient(local: local, cloud: nil, keepsCloudHome: true, now: { date }) })
        }
        let first = await loader(now).load()
        #expect(cloudSource.count == 0)
        #expect(first.solarStatus == .fresh && first.solar?.isLive == true)
        #expect(abs((first.solar?.currentPowerKW ?? 0) - 2.912) < 1e-9)
        #expect(first.solar?.dailyEnergyKWh == 4)
        #expect(first.solar?.cloudFetchedAt == now.addingTimeInterval(-5 * 60))
        // 10 minutes later: the live snapshot is new, but the cloud limit still counts from the cloud fetch.
        _ = await loader(now.addingTimeInterval(10 * 60)).load()
        #expect(cloudSource.count == 0)
        _ = await loader(now.addingTimeInterval(16 * 60)).load()
        #expect(cloudSource.count == 1)
    }

    @Test func awayTheStoredCloudDataIsReused() async {
        let (store, _) = storeTempStore()
        store.settings = settings()
        let id = SolarAccount(settings: settings())!.cacheID
        let cloud = storeSampleSolar(fetchedAt: now.addingTimeInterval(-5 * 60), endpointID: id)
        store.saveSolar(cloud)
        let cloudSource = CallCountingSolar(.success(storeSampleSolar(fetchedAt: now)))
        let away = inverter(nil)
        let data = await SunWattLoader(store: store, makeSolar: { _ in cloudSource }, prices: FakePlain(result: .success(storeSamplePrices())), now: { now },
                                       makeLiveSolar: { _ in LocalInverterSolarClient(local: away, cloud: nil) }).load()
        #expect(cloudSource.count == 0)
        #expect(data.solar == cloud && data.solarStatus == .fresh)
    }

    @Test func aFailingCloudIsNotAskedAgainWhileTheInverterAnswers() async {
        let (store, _) = storeTempStore()
        store.settings = settings()
        let cloudSource = CallCountingSolar(.failure(.http(status: 429)))
        let local = inverter(fakeSunSpecRegisters())
        let loader = { (date: Date) in
            SunWattLoader(store: store,
                          makeSolar: { _ in LocalInverterSolarClient(local: local, cloud: cloudSource, keepsCloudHome: true, now: { date }) },
                          prices: FakePlain(result: .success(storeSamplePrices())), now: { date },
                          makeLiveSolar: { _ in LocalInverterSolarClient(local: local, cloud: nil, keepsCloudHome: true, now: { date }) })
        }
        let first = await loader(now).load()
        #expect(cloudSource.count == 1 && first.solar?.isLive == true)
        let second = await loader(now.addingTimeInterval(5 * 60)).load()
        #expect(cloudSource.count == 1)
        #expect(second.solar?.isLive == true && second.solarStatus == .fresh)
        _ = await loader(now.addingTimeInterval(21 * 60)).load()
        #expect(cloudSource.count == 2)
    }
}

// MARK: Test and find

@MainActor @Suite struct SunSpecSetupTests {
    private func model(host: String = "") -> AppModel {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(showTomorrowPrices: false, onboardingCompleted: true, solarProvider: .solarEdge,
                                         solarSiteID: "1234567", solarAPIKey: "ABCDEFGH12345678", sunSpecHost: host)
        return AppModel(launch: AppLaunchOptions(), store: store,
                        loader: SunWattLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .success(storeSamplePrices()))))
    }

    @Test func testFindsThePortAndUnitID() async {
        let model = model()
        #expect(model.localInverterKind == .sunSpec)
        // SMA style: unit 126, on 1502 here.
        model.makeModbusTransport = { endpoint in
            endpoint.port == 1502 ? FakeModbusTransport(registers: fakeSunSpecRegisters(), unit: 126) : FakeModbusTransport(registers: [:], reachable: false)
        }
        guard case .success(let match) = await model.testInverter("192.168.1.40") else { Issue.record("expected success"); return }
        #expect(match.address == "192.168.1.40:1502" && match.unitID == 126)
        #expect(match.model == "SolarEdge SE5000H-RW000BNN4")
        // A port typed by the user is the only one tried.
        if case .failure(let error) = await model.testInverter("192.168.1.40:502") {
            #expect(error as? SunWattError == .inverterUnreachable)
        } else { Issue.record("expected failure") }
    }

    @Test func liveTickReadsTheSunSpecInverter() async {
        let model = model(host: "192.168.1.40:1502")
        model.makeModbusTransport = { _ in FakeModbusTransport(registers: fakeSunSpecRegisters()) }
        await model.liveTick()
        #expect(model.data?.solar?.isLive == true)
        #expect(abs((model.data?.solar?.currentPowerKW ?? 0) - 2.912) < 1e-9)
    }

    @Test func findLooksOnBothPorts() async {
        let finder = InverterFinder(
            kind: .sunSpec,
            candidates: { ["192.168.1.30", "192.168.1.40"] },
            probe: { host, port in host == "192.168.1.40" && port == 1502 || host == "192.168.1.30" && port == 502 },
            makeTransport: { endpoint in
                // .30: a Huawei dongle, not SunSpec.
                endpoint.host == "192.168.1.40" ? FakeModbusTransport(registers: fakeSunSpecRegisters()) : FakeModbusTransport(registers: fakeInverterRegisters())
            })
        let matches = await finder.find()
        #expect(matches.map(\.address) == ["192.168.1.40:1502"])
        #expect(matches.first?.unitID == 1)
    }

    @Test func demoFindsASampleForEachKind() async {
        let edge = AppModel(demo: .day, settings: SunWattSettings(solarProvider: .solarEdge))
        #expect(await edge.findInverters().map(\.address) == ["192.168.1.40:1502"])
        #expect(AppModel(demo: .day, settings: SunWattSettings(kioskURL: testKioskURL)).localInverterKind == .huawei)
    }
}
