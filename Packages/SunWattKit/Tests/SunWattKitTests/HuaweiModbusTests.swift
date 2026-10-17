import Foundation
import Testing
#if canImport(Network)
import Network
#endif
@testable import SunWattKit

// MARK: Fake dongle

/// Holding registers of a fake SUN2000 behind a dongle: 2,912 kW, 5,12 kWh today, 44.150,55 kWh in total, on-grid.
func fakeInverterRegisters(powerW: Int32 = 2912, meterW: Int32? = nil) -> [UInt16: UInt16] {
    var registers: [UInt16: UInt16] = [:]
    for address in UInt16(32080)...32115 { registers[address] = 0 }
    let power = UInt32(bitPattern: powerW)
    registers[32080] = UInt16(power >> 16)
    registers[32081] = UInt16(power & 0xFFFF)
    registers[32089] = 0x0200
    registers[32106] = UInt16(4_415_055 >> 16)
    registers[32107] = UInt16(4_415_055 & 0xFFFF)
    registers[32115] = 512
    if let meterW {
        for address in UInt16(37100)...37114 { registers[address] = 0 }
        registers[37100] = 1
        let meter = UInt32(bitPattern: meterW)
        registers[37113] = UInt16(meter >> 16)
        registers[37114] = UInt16(meter & 0xFFFF)
    }
    return registers
}

/// Answer to a "read holding registers" request: the registers, or exception 2 (illegal address) when one is missing.
func modbusResponse(to request: [UInt8], registers: [UInt16: UInt16], unit: UInt8 = 1) -> Data {
    let address = UInt16(request[8]) << 8 | UInt16(request[9])
    let count = UInt16(request[10]) << 8 | UInt16(request[11])
    let values = (0..<count).map { registers[address &+ $0] }
    guard request[6] == unit else {
        return Data([request[0], request[1], 0, 0, 0, 3, request[6], 0x83, 0x0B])
    }
    guard values.allSatisfy({ $0 != nil }) else {
        return Data([request[0], request[1], 0, 0, 0, 3, request[6], 0x83, 0x02])
    }
    var body: [UInt8] = [request[6], 0x03, UInt8(count * 2)]
    for value in values.compactMap({ $0 }) { body += [UInt8(value >> 8), UInt8(value & 0xFF)] }
    return Data([request[0], request[1], 0, 0, UInt8(body.count >> 8), UInt8(body.count & 0xFF)] + body)
}

final class FakeModbusTransport: ModbusTransport, @unchecked Sendable {
    let registers: [UInt16: UInt16]
    let unit: UInt8
    let reachable: Bool
    private let lock = NSLock()
    private(set) var requests: [[UInt8]] = []
    private(set) var closed = false

    init(registers: [UInt16: UInt16], unit: UInt8 = 1, reachable: Bool = true) {
        self.registers = registers
        self.unit = unit
        self.reachable = reachable
    }

    func open() async throws {
        if !reachable { throw SunWattError.inverterUnreachable }
    }

    func exchange(_ request: Data) async throws -> Data {
        let bytes = [UInt8](request)
        lock.withLock { requests.append(bytes) }
        return modbusResponse(to: bytes, registers: registers, unit: unit)
    }

    func close() { lock.withLock { closed = true } }
}

// MARK: Tests

@Suite struct HuaweiModbusTests {
    @Test func endpointParsing() {
        #expect(HuaweiLocalEndpoint(parsing: " 192.168.1.50 ") == HuaweiLocalEndpoint(host: "192.168.1.50"))
        #expect(HuaweiLocalEndpoint(parsing: "192.168.1.50:6607")?.port == 6607)
        #expect(HuaweiLocalEndpoint(parsing: "dongle.local")?.host == "dongle.local")
        #expect(HuaweiLocalEndpoint(parsing: "192.168.1.50", unitID: 0)?.unitID == 0)
        for bad in ["", "http://192.168.1.50", "192.168.1", "192.168.1.256", "192.168.1.50:0", "192.168.1.50:99999", "a b", "-x", "host/path"] {
            #expect(HuaweiLocalEndpoint(parsing: bad) == nil, "\(bad)")
        }
        #expect(HuaweiLocalEndpoint(parsing: "192.168.1.50", unitID: 300) == nil)
    }

    @Test func requestFrame() {
        let frame = ModbusFrame.readHoldingRegisters(transaction: 0x0102, unit: 1, address: 32080, count: 10)
        #expect([UInt8](frame) == [0x01, 0x02, 0, 0, 0, 6, 1, 0x03, 0x7D, 0x50, 0, 10])
    }

    @Test func responseFrames() throws {
        let request = [UInt8](ModbusFrame.readHoldingRegisters(transaction: 7, unit: 1, address: 32080, count: 2))
        let ok = modbusResponse(to: request, registers: [32080: 0xFFFF, 32081: 0xFFFB])
        let values = try ModbusFrame.registers(from: ok, transaction: 7, count: 2)
        #expect(values == [0xFFFF, 0xFFFB])
        #expect(ModbusFrame.int32(values, at: 0) == -5)
        #expect(throws: ModbusFrame.Failure.badFrame) { try ModbusFrame.registers(from: ok, transaction: 8, count: 2) }
        #expect(throws: ModbusFrame.Failure.badFrame) { try ModbusFrame.registers(from: ok, transaction: 7, count: 3) }
        #expect(throws: ModbusFrame.Failure.badFrame) { try ModbusFrame.registers(from: Data([0, 7, 0]), transaction: 7, count: 2) }
        let missing = modbusResponse(to: request, registers: [:])
        #expect(throws: ModbusFrame.Failure.exception(2)) { try ModbusFrame.registers(from: missing, transaction: 7, count: 2) }
    }

    @Test func readsInverterWithoutMeter() async throws {
        let transport = FakeModbusTransport(registers: fakeInverterRegisters())
        let client = HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in transport })
        let reading = try await client.read()
        #expect(reading.activePowerKW == 2.912)
        #expect(reading.dailyEnergyKWh == 5.12)
        #expect(abs(reading.totalEnergyKWh - 44150.55) < 1e-9)
        #expect(reading.deviceStatus == 0x0200)
        #expect(reading.meterActivePowerKW == nil)
        #expect(transport.requests.count == 3)
        #expect(transport.requests.allSatisfy { $0[7] == 0x03 })
        #expect(transport.closed)
    }

    @Test func readsTheMeterWhenThereIsOne() async throws {
        let transport = FakeModbusTransport(registers: fakeInverterRegisters(meterW: -500))
        let reading = try await HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in transport }).read()
        #expect(reading.meterActivePowerKW == -0.5)
    }

    @Test func errorsAreMapped() async {
        let down = FakeModbusTransport(registers: [:], reachable: false)
        await #expect(throws: SunWattError.inverterUnreachable) {
            try await HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in down }).read()
        }
        await #expect(throws: SunWattError.inverterUnreachable) {
            try await HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in nil }).read()
        }
        let wrongUnit = FakeModbusTransport(registers: fakeInverterRegisters(), unit: 0)
        await #expect(throws: ModbusFrame.Failure.exception(0x0B)) {
            try await HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in wrongUnit }).read()
        }
    }

    @Test func mergeIntoKioskSnapshot() {
        let now = madrid(2026, 6, 1, 12, 33)
        var base = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12, 0))
        base.curve = [
            CurvePoint(time: madrid(2026, 5, 31, 23, 55), kW: 0),
            CurvePoint(time: madrid(2026, 6, 1, 12, 0), kW: 2.4),
            CurvePoint(time: madrid(2026, 6, 1, 12, 32).addingTimeInterval(30), kW: 2.5),
            CurvePoint(time: madrid(2026, 6, 1, 23, 55), kW: nil),
        ]
        let reading = HuaweiInverterReading(activePowerKW: 2.9, dailyEnergyKWh: 5.1, totalEnergyKWh: 44150)
        let s = HuaweiLocalSolarClient.merge(reading, into: base, at: now)
        #expect(s.currentPowerKW == 2.9 && s.dailyEnergyKWh == 5.1 && s.cumulativeEnergyKWh == 44150)
        #expect(s.isLive == true && s.fetchedAt == now && s.dataAsOf == now)
        #expect(s.stationName == "Casa")
        // Yesterday, the last minute and the future go; the reading is the last point.
        #expect(s.curve.map(\.time) == [madrid(2026, 6, 1, 12, 0), now])
        #expect(s.home == nil)
        // Standby at night: slightly negative power reads as zero.
        #expect(HuaweiLocalSolarClient.merge(HuaweiInverterReading(activePowerKW: -0.005, dailyEnergyKWh: 0, totalEnergyKWh: 1), into: nil, at: now).currentPowerKW == 0)
    }

    @Test func mergeWithMeterGivesHome() {
        let reading = HuaweiInverterReading(activePowerKW: 3, dailyEnergyKWh: 5, totalEnergyKWh: 1, meterActivePowerKW: 2.2)
        let home = HuaweiLocalSolarClient.merge(reading, into: nil, at: madrid(2026, 6, 1, 12, 0)).home
        #expect(home?.gridKW == -2.2)
        #expect(abs((home?.consumptionKW ?? 0) - 0.8) < 1e-9)
    }

    @Test func localFirstThenKiosk() async throws {
        let now = madrid(2026, 6, 1, 12, 33)
        let kioskSnapshot = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12, 30))
        let up = HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in FakeModbusTransport(registers: fakeInverterRegisters()) })
        let down = HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "x"), makeTransport: { _ in FakeModbusTransport(registers: [:], reachable: false) })

        let atHome = try await HuaweiLocalSolarClient(local: up, kiosk: FakeSolar(result: .success(kioskSnapshot)), now: { now }).fetch()
        #expect(atHome.isLive == true && atHome.currentPowerKW == 2.912 && atHome.stationName == "Casa")

        let away = try await HuaweiLocalSolarClient(local: down, kiosk: FakeSolar(result: .success(kioskSnapshot)), now: { now }).fetch()
        #expect(away == kioskSnapshot)

        let inverterOnly = try await HuaweiLocalSolarClient(local: up, kiosk: nil, now: { now }).fetch()
        #expect(inverterOnly.curve.count == 1 && inverterOnly.isLive == true)

        await #expect(throws: SunWattError.inverterUnreachable) {
            try await HuaweiLocalSolarClient(local: down, kiosk: nil, now: { now }).fetch()
        }
        // At home with the kiosk failing: the inverter alone.
        let kioskDown = try await HuaweiLocalSolarClient(local: up, kiosk: FakeSolar(result: .failure(.unavailable)), now: { now }).fetch()
        #expect(kioskDown.isLive == true)
    }

    @Test func accountWithInverterAddress() {
        var settings = SunWattSettings(kioskURL: testKioskURL)
        settings.huaweiLocalHost = "192.168.1.50"
        let account = SolarAccount(settings: settings)
        guard case .huaweiLocal(let local, let kiosk)? = account else { Issue.record("expected huaweiLocal"); return }
        #expect(local.host == "192.168.1.50" && kiosk != nil)
        #expect(account?.provider == .fusionSolar)
        #expect(account?.cacheID == testKioskID)
        #expect(account?.kioskEndpoint != nil)
        #expect(SunWattLoader.defaultSolar(settings) is HuaweiLocalSolarClient)

        settings.kioskURL = ""
        #expect(SolarAccount(settings: settings)?.kioskEndpoint == nil)
        #expect(SolarAccount(settings: settings)?.cacheID.count == 64)

        settings.huaweiLocalHost = "not an address!"
        #expect(SolarAccount(settings: settings) == nil)
        #expect(settings.hasSolarInput)
        #expect(settings.invalidSolarInputError == .invalidInverterAddress)
    }

    @Test func liveFillsTheCurveFromTheCache() {
        var live = HuaweiLocalSolarClient.merge(HuaweiInverterReading(activePowerKW: 3, dailyEnergyKWh: 5, totalEnergyKWh: 1), into: nil, at: madrid(2026, 6, 1, 12, 30))
        live.monthEnergyKWh = nil
        var cached = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12, 0))
        cached.monthEnergyKWh = 300
        let filled = SunWattLoader.fillingIn(live, from: cached)
        #expect(filled.curve.count == 2)
        #expect(filled.curve.last?.kW == 3)
        #expect(filled.stationName == "Casa")
        #expect(filled.monthEnergyKWh == 300)
    }

    @Test func settingsWithoutInverterFieldsStillDecode() throws {
        let json = Data(#"{"kioskURL":"x"}"#.utf8)
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: json)
        #expect(settings.huaweiLocalHost.isEmpty && settings.huaweiLocalUnitID == 1)
    }

    @MainActor @Test func testInverterTriesUnitIDs() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, onboardingCompleted: true)
        let model = AppModel(launch: AppLaunchOptions(), store: store, loader: SunWattLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .success(storeSamplePrices()))))
        model.makeModbusTransport = { endpoint in FakeModbusTransport(registers: fakeInverterRegisters(), unit: 0) }
        guard case .success(let match) = await model.testInverter("192.168.1.50") else { Issue.record("expected success"); return }
        #expect(match.unitID == 0 && match.address == "192.168.1.50")
        #expect(match.reading.activePowerKW == 2.912)
        if case .failure(let error) = await model.testInverter("nope!") {
            #expect(error as? SunWattError == .invalidInverterAddress)
        } else { Issue.record("expected failure") }
        model.makeModbusTransport = { _ in FakeModbusTransport(registers: [:], reachable: false) }
        if case .failure(let error) = await model.testInverter("192.168.1.50") {
            #expect(error as? SunWattError == .inverterUnreachable)
        } else { Issue.record("expected failure") }
    }

    @MainActor @Test func liveTickUpdatesTheDashboard() async {
        let (store, _) = storeTempStore()
        var settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, onboardingCompleted: true)
        settings.huaweiLocalHost = "192.168.1.50"
        store.settings = settings
        let model = AppModel(launch: AppLaunchOptions(), store: store, loader: SunWattLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .success(storeSamplePrices()))))
        model.makeModbusTransport = { _ in FakeModbusTransport(registers: fakeInverterRegisters()) }
        await model.liveTick()
        #expect(model.data?.solar?.isLive == true)
        #expect(model.data?.solar?.currentPowerKW == 2.912)
        #expect(model.data?.solarStatus == .fresh)
    }
}

#if canImport(Network) && !os(watchOS)
/// Real TCP: NWModbusTransport against a tiny Modbus server on 127.0.0.1.
@Suite(.serialized) struct ModbusTCPTests {
    final class Server: @unchecked Sendable {
        let listener: NWListener
        let registers: [UInt16: UInt16]
        let queue = DispatchQueue(label: "test.modbus.server")

        init(registers: [UInt16: UInt16]) throws {
            self.registers = registers
            // Loopback only: no firewall prompt, no local network permission.
            let parameters = NWParameters.tcp
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            listener = try NWListener(using: parameters)
        }

        func start() async throws -> UInt16 {
            listener.newConnectionHandler = { [self] connection in
                connection.start(queue: queue)
                serve(connection)
            }
            return try await withCheckedThrowingContinuation { continuation in
                let once = Once()
                listener.stateUpdateHandler = { [self] state in
                    switch state {
                    case .ready:
                        if once.claim() { continuation.resume(returning: self.listener.port?.rawValue ?? 0) }
                    case .failed(let error):
                        if once.claim() { continuation.resume(throwing: error) }
                    default: break
                    }
                }
                listener.start(queue: queue)
            }
        }

        private func serve(_ connection: NWConnection) {
            connection.receive(minimumIncompleteLength: 12, maximumLength: 12) { [self] data, _, _, error in
                guard error == nil, let data, data.count == 12 else { connection.cancel(); return }
                connection.send(content: modbusResponse(to: [UInt8](data), registers: registers), completion: .contentProcessed { _ in
                    self.serve(connection)
                })
            }
        }

        func stop() { listener.cancel() }
    }

    final class Once: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        /// True the first time only.
        func claim() -> Bool { lock.withLock { defer { done = true }; return !done } }
    }

    @Test func readsOverRealTCP() async throws {
        let server = try Server(registers: fakeInverterRegisters(meterW: 1500))
        let port = try await server.start()
        defer { server.stop() }
        let client = HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "127.0.0.1", port: port)) { endpoint in
            NWModbusTransport(host: endpoint.host, port: endpoint.port, settleDelay: .milliseconds(20))
        }
        let reading = try await client.read()
        #expect(reading.activePowerKW == 2.912)
        #expect(reading.dailyEnergyKWh == 5.12)
        #expect(reading.meterActivePowerKW == 1.5)
    }

    @Test func closedPortIsUnreachable() async throws {
        // Grab a free port, then close it again.
        let server = try Server(registers: [:])
        let port = try await server.start()
        server.stop()
        try await Task.sleep(for: .milliseconds(100))
        let client = HuaweiModbusClient(endpoint: HuaweiLocalEndpoint(host: "127.0.0.1", port: port)) { endpoint in
            NWModbusTransport(host: endpoint.host, port: endpoint.port, settleDelay: .milliseconds(20))
        }
        let result = await SunWattLoader.race(deadline: 5) { try await client.read() }
        guard case .failure(let error) = result else { Issue.record("expected failure"); return }
        #expect(error as? SunWattError == .inverterUnreachable || (error as? SunWattError).map { if case .network = $0 { true } else { false } } == true)
    }
}
#endif
