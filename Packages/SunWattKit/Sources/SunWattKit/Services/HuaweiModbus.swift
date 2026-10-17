import Foundation
#if canImport(Network) && !os(watchOS)
import Network
#endif

// Huawei SUN2000 inverters read directly on the home network, through the Smart Dongle (SDongleA-05 and similar),
// with Modbus TCP. The dongle must have Modbus TCP set to "Enable (unrestricted)" (an installer setting).
// SunWatt only reads holding registers (function 0x03); it never writes.
// Register map: Huawei "SUN2000 MODBUS Interface Definitions".

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
        /// The device answers Modbus but is not the expected kind (e.g. no SunSpec marker, no inverter model).
        case unexpectedDevice
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

    /// Two ASCII characters per register, padded with zeros. Nil when empty or not printable.
    static func text(_ registers: [UInt16]) -> String? {
        let bytes = registers.flatMap { [UInt8($0 >> 8), UInt8($0 & 0xFF)] }.prefix { $0 != 0 }
        guard bytes.allSatisfy({ (0x20...0x7E).contains($0) }) else { return nil }
        let value = String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
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
        try await ModbusSession.run(endpoint, makeTransport: makeTransport) { try await Self.reading($0) }
    }

    /// The model name (registers 30000...30014, ASCII, e.g. "SUN2000-15KTL-M5"; nil if the dongle does not give it)
    /// and a reading, on one connection. Used by "Find inverter".
    public func identify() async throws -> (model: String?, reading: HuaweiInverterReading) {
        try await ModbusSession.run(endpoint, makeTransport: makeTransport) { registers in
            let model = (try? await registers(30000, 15)).flatMap(ModbusFrame.text)
            return (model, try await Self.reading(registers))
        }
    }

    private static func reading(_ registers: ModbusSession.ReadRegisters) async throws -> HuaweiInverterReading {
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
