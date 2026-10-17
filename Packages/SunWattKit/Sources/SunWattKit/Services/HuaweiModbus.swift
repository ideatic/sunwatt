import Foundation
#if canImport(Network) && !os(watchOS)
import Network
#endif

// Huawei SUN2000 inverters read directly on the home network, through the Smart Dongle (SDongleA-05 and similar),
// with Modbus TCP. The dongle must have Modbus TCP set to "Enable (unrestricted)" (an installer setting).
// SunWatt only reads holding registers (function 0x03); it never writes.
// Register map: Huawei "SUN2000 MODBUS Interface Definitions".

/// Address of the dongle on the home network: an IPv4 address or a host name, optionally ":port" (default 502),
/// and the Modbus unit ID of the inverter behind it (usually 1).
public struct HuaweiLocalEndpoint: Hashable, Sendable {
    public var host: String
    public var port: UInt16
    public var unitID: UInt8

    public init(host: String, port: UInt16 = 502, unitID: UInt8 = 1) {
        self.host = host
        self.port = port
        self.unitID = unitID
    }

    /// Nil for anything that is not a plain host: no scheme, no path, no spaces.
    public init?(parsing text: String, unitID: Int = 1) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, (0...247).contains(unitID) else { return nil }
        var host = trimmed
        var port: UInt16 = 502
        if let colon = trimmed.lastIndex(of: ":") {
            guard let value = UInt16(trimmed[trimmed.index(after: colon)...]), value > 0 else { return nil }
            port = value
            host = String(trimmed[..<colon])
        }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-")
        guard (1...253).contains(host.count), host.unicodeScalars.allSatisfy(allowed.contains),
              !host.hasPrefix("."), !host.hasPrefix("-"), !host.hasSuffix(".") else { return nil }
        // Digits and dots only: must be a full IPv4 address.
        if host.allSatisfy({ $0.isNumber || $0 == "." }) {
            let parts = host.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count == 4, parts.allSatisfy({ !$0.isEmpty && $0.count <= 3 && (Int($0) ?? 256) <= 255 }) else { return nil }
        }
        self.init(host: host, port: port, unitID: UInt8(unitID))
    }
}

/// One reading of the inverter (and of the Huawei power meter, when there is one).
public struct HuaweiInverterReading: Hashable, Sendable {
    /// Inverter active power now (kW). Slightly negative at night (standby consumption).
    public var activePowerKW: Double
    /// Energy produced today (kWh).
    public var dailyEnergyKWh: Double
    /// Energy produced since installation (kWh).
    public var totalEnergyKWh: Double
    /// Huawei device status code (e.g. 0x0200 on-grid, 0xA000 standby: no irradiation).
    public var deviceStatus: UInt16?
    /// Power at the grid connection from a Huawei meter (kW): positive when feeding the grid, negative when buying.
    /// Nil without a meter (export-only plants).
    public var meterActivePowerKW: Double?

    public init(activePowerKW: Double, dailyEnergyKWh: Double, totalEnergyKWh: Double, deviceStatus: UInt16? = nil, meterActivePowerKW: Double? = nil) {
        self.activePowerKW = activePowerKW
        self.dailyEnergyKWh = dailyEnergyKWh
        self.totalEnergyKWh = totalEnergyKWh
        self.deviceStatus = deviceStatus
        self.meterActivePowerKW = meterActivePowerKW
    }
}

/// Modbus TCP framing for "read holding registers".
enum ModbusFrame {
    enum Failure: Error, Equatable {
        /// Malformed or unexpected frame.
        case badFrame
        /// The device answered with a Modbus exception (e.g. 2: illegal address, 11: gateway target failed).
        case exception(UInt8)
    }

    /// MBAP header (transaction, protocol 0, length 6, unit) + function 0x03, start address, register count.
    static func readHoldingRegisters(transaction: UInt16, unit: UInt8, address: UInt16, count: UInt16) -> Data {
        Data([
            UInt8(transaction >> 8), UInt8(transaction & 0xFF), 0, 0, 0, 6, unit,
            0x03, UInt8(address >> 8), UInt8(address & 0xFF), UInt8(count >> 8), UInt8(count & 0xFF),
        ])
    }

    /// The register values of a response to `readHoldingRegisters(transaction:...count:)`.
    static func registers(from frame: Data, transaction: UInt16, count: UInt16) throws -> [UInt16] {
        let b = [UInt8](frame)
        guard b.count >= 9, UInt16(b[0]) << 8 | UInt16(b[1]) == transaction, b[2] == 0, b[3] == 0,
              Int(b[4]) << 8 | Int(b[5]) == b.count - 6 else { throw Failure.badFrame }
        if b[7] == 0x83 { throw Failure.exception(b[8]) }
        let byteCount = Int(b[8])
        guard b[7] == 0x03, byteCount == Int(count) * 2, b.count == 9 + byteCount else { throw Failure.badFrame }
        return stride(from: 9, to: 9 + byteCount, by: 2).map { UInt16(b[$0]) << 8 | UInt16(b[$0 + 1]) }
    }

    static func uint32(_ registers: [UInt16], at index: Int) -> UInt32 {
        UInt32(registers[index]) << 16 | UInt32(registers[index + 1])
    }

    static func int32(_ registers: [UInt16], at index: Int) -> Int32 {
        Int32(bitPattern: uint32(registers, at: index))
    }
}

/// A Modbus TCP connection: open, exchange whole frames, close.
public protocol ModbusTransport: Sendable {
    func open() async throws
    /// Sends a request frame and returns the complete response frame.
    func exchange(_ request: Data) async throws -> Data
    func close()
}

/// Reads a Huawei inverter through its dongle. Three requests per reading: inverter power and status
/// (32080...32089), energy (32106...32115) and the meter (37100...37114, skipped without a meter).
public struct HuaweiModbusClient: Sendable {
    public let endpoint: HuaweiLocalEndpoint
    let makeTransport: @Sendable (HuaweiLocalEndpoint) -> ModbusTransport?

    public init(endpoint: HuaweiLocalEndpoint, makeTransport: (@Sendable (HuaweiLocalEndpoint) -> ModbusTransport?)? = nil) {
        self.endpoint = endpoint
        self.makeTransport = makeTransport ?? HuaweiModbusClient.defaultTransport
    }

    /// Network.framework TCP; nil on watchOS, where apps cannot open such connections.
    public static let defaultTransport: @Sendable (HuaweiLocalEndpoint) -> ModbusTransport? = { endpoint in
        #if canImport(Network) && !os(watchOS)
        NWModbusTransport(host: endpoint.host, port: endpoint.port)
        #else
        nil
        #endif
    }

    public func read() async throws -> HuaweiInverterReading {
        guard let transport = makeTransport(endpoint) else { throw SunWattError.inverterUnreachable }
        defer { transport.close() }
        do {
            try await transport.open()
            let unit = endpoint.unitID
            var transaction: UInt16 = 0
            func registers(_ address: UInt16, _ count: UInt16) async throws -> [UInt16] {
                transaction &+= 1
                let request = ModbusFrame.readHoldingRegisters(transaction: transaction, unit: unit, address: address, count: count)
                return try ModbusFrame.registers(from: try await transport.exchange(request), transaction: transaction, count: count)
            }
            let inverter = try await registers(32080, 10)
            let energy = try await registers(32106, 10)
            // Meter: status 37100 (1 = normal), active power 37113 (I32, W, > 0 feeding the grid).
            var meterKW: Double?
            if let meter = try? await registers(37100, 15), meter[0] == 1 {
                meterKW = Double(ModbusFrame.int32(meter, at: 13)) / 1000
            }
            return HuaweiInverterReading(
                activePowerKW: Double(ModbusFrame.int32(inverter, at: 0)) / 1000,
                dailyEnergyKWh: Double(ModbusFrame.uint32(energy, at: 8)) / 100,
                totalEnergyKWh: Double(ModbusFrame.uint32(energy, at: 0)) / 100,
                deviceStatus: inverter[9],
                meterActivePowerKW: meterKW)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as SunWattError {
            throw error
        } catch let error as ModbusFrame.Failure {
            // The dongle answered, but not as expected (e.g. wrong unit ID): kept apart from "no connection".
            throw error
        } catch {
            throw SunWattError.inverterUnreachable
        }
    }
}

/// FusionSolar with the inverter on the home network: both are read together. When the inverter answers (at home),
/// its live figures replace the kiosk's and its reading is added to the kiosk curve; otherwise (away, or the dongle
/// is busy) the kiosk snapshot is used as it is. Without a kiosk link, only the inverter.
public struct HuaweiLocalSolarClient: SolarDataSource {
    public let local: HuaweiModbusClient
    public let kiosk: SolarDataSource?
    /// Away from home the connection can hang until this deadline; keep it short.
    public var localDeadline: TimeInterval = 5
    public let now: @Sendable () -> Date

    public init(local: HuaweiModbusClient, kiosk: SolarDataSource?, now: @escaping @Sendable () -> Date = { Date() }) {
        self.local = local
        self.kiosk = kiosk
        self.now = now
    }

    public func fetch() async throws -> SolarSnapshot {
        let local = local
        async let fromKiosk = Self.fetch(kiosk)
        let fromInverter = await SunWattLoader.race(deadline: localDeadline) { try await local.read() }
        let kioskResult = await fromKiosk
        switch fromInverter {
        case .success(let reading):
            return Self.merge(reading, into: try? kioskResult?.get(), at: now())
        case .failure(let error):
            if error is CancellationError { throw CancellationError() }
            // Without a kiosk link every failure (timeout, refused, odd answer) reads as "the inverter did not answer".
            guard let kioskResult else { throw SunWattError.inverterUnreachable }
            return try kioskResult.get()
        }
    }

    private static func fetch(_ source: SolarDataSource?) async -> Result<SolarSnapshot, Error>? {
        guard let source else { return nil }
        do { return .success(try await source.fetch()) } catch { return .failure(error) }
    }

    /// `base` (the kiosk snapshot, or the last one shown) with the live reading: power, today's and total energy,
    /// a curve point at `date`, and home consumption when a Huawei meter is present.
    public static func merge(_ reading: HuaweiInverterReading, into base: SolarSnapshot?, at date: Date) -> SolarSnapshot {
        var snapshot = base ?? SolarSnapshot(currentPowerKW: nil, dailyEnergyKWh: nil, curve: [], fetchedAt: date, dataAsOf: nil)
        let power = max(reading.activePowerKW, 0)
        snapshot.currentPowerKW = power
        snapshot.dailyEnergyKWh = reading.dailyEnergyKWh
        snapshot.cumulativeEnergyKWh = reading.totalEnergyKWh
        snapshot.fetchedAt = date
        snapshot.dataAsOf = date
        snapshot.isLive = true
        // Today's curve only (a base from yesterday contributes nothing), plus this reading. Points of the last minute
        // are replaced, so live reads every few seconds add at most one point a minute.
        snapshot.curve = snapshot.curve.filter {
            Constants.madridCalendar.isDate($0.time, inSameDayAs: date) && $0.time < date.addingTimeInterval(-55)
        } + [CurvePoint(time: date, kW: power)]
        if let meter = reading.meterActivePowerKW {
            // Meter: > 0 feeding the grid. HomeEnergy.gridKW: > 0 buying.
            let grid = -meter
            snapshot.home = HomeEnergy(consumptionKW: max(power + grid, 0), gridKW: grid, dailyConsumptionKWh: nil)
        } else {
            snapshot.home = nil
        }
        return snapshot
    }
}

#if canImport(Network) && !os(watchOS)
/// Modbus TCP over Network.framework. Huawei dongles drop requests sent right after connecting, so `open` waits
/// `settleDelay` once the connection is ready.
final class NWModbusTransport: ModbusTransport, @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.ideatic.sunwatt.modbus")
    private let settleDelay: Duration

    init(host: String, port: UInt16, settleDelay: Duration = .seconds(1)) {
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 502, using: .tcp)
        self.settleDelay = settleDelay
    }

    func open() async throws {
        let connection = connection
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let once = OnceContinuation(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: once.resume(.success(()))
                    // .waiting: no route or connection refused (not at home, or Modbus TCP off).
                    case .waiting, .failed: once.resume(.failure(SunWattError.inverterUnreachable))
                    case .cancelled: once.resume(.failure(CancellationError()))
                    default: break
                    }
                }
                connection.start(queue: queue)
            }
        } onCancel: { [self] in
            close()
        }
        try await Task.sleep(for: settleDelay)
    }

    func exchange(_ request: Data) async throws -> Data {
        let connection = connection
        return try await withTaskCancellationHandler {
            try await Self.send(request, on: connection)
            let header = [UInt8](try await Self.receive(7, on: connection))
            let length = Int(header[4]) << 8 | Int(header[5])
            guard (2...254).contains(length) else { throw SunWattError.inverterUnreachable }
            let body = try await Self.receive(length - 1, on: connection)
            return Data(header) + body
        } onCancel: { [self] in
            close()
        }
    }

    func close() {
        connection.cancel()
    }

    private static func send(_ data: Data, on connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if error != nil { continuation.resume(throwing: SunWattError.inverterUnreachable) } else { continuation.resume() }
            })
        }
    }

    private static func receive(_ count: Int, on connection: NWConnection) async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if error == nil, let data, data.count == count {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: SunWattError.inverterUnreachable)
                }
            }
        }
    }
}

/// Resumes a continuation once; later results are ignored (connection state handlers fire several times).
private final class OnceContinuation<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func resume(_ result: Result<T, Error>) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(with: result)
    }
}
#endif
