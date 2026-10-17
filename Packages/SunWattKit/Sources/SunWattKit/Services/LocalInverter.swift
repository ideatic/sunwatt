import Foundation

// Inverters read directly on the home network with Modbus TCP: Huawei (through its dongle, `HuaweiModbusClient`) and
// SunSpec inverters (SolarEdge, SMA, Fronius, …, `SunSpecClient`). Read-only (function 0x03). While the inverter
// answers (at home) its live figures are shown; elsewhere the cloud source of the provider.

/// Address of an inverter (or its dongle) on the home network: an IPv4 address or a host name, optionally ":port"
/// (default 502), and the Modbus unit ID (usually 1).
public struct ModbusEndpoint: Hashable, Sendable {
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

    /// True when `text` names a port (`host:port`).
    public static func hasPort(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).contains(":")
    }

    /// How the address is saved: "host", or "host:port" when the port is not 502.
    public var text: String { port == 502 ? host : "\(host):\(port)" }
}

/// The Huawei name, from before SunSpec support.
public typealias HuaweiLocalEndpoint = ModbusEndpoint

/// One live reading, whatever the inverter brand.
public struct LiveInverterReading: Hashable, Sendable {
    /// AC power now (kW). Slightly negative at night on some inverters (standby consumption).
    public var activePowerKW: Double
    /// Energy produced today (kWh). Nil when the inverter does not count it (SunSpec: only a lifetime counter).
    public var dailyEnergyKWh: Double?
    /// Energy produced since installation (kWh).
    public var totalEnergyKWh: Double?
    /// Power at the grid connection from a meter the inverter reads (kW): positive when feeding the grid.
    public var meterActivePowerKW: Double?

    public init(activePowerKW: Double, dailyEnergyKWh: Double? = nil, totalEnergyKWh: Double? = nil, meterActivePowerKW: Double? = nil) {
        self.activePowerKW = activePowerKW
        self.dailyEnergyKWh = dailyEnergyKWh
        self.totalEnergyKWh = totalEnergyKWh
        self.meterActivePowerKW = meterActivePowerKW
    }
}

/// An inverter that can be read on the home network (Modbus TCP, read-only). Contract, checked for every reader by
/// `ProviderContractTests`:
///
/// - **Reading.** `activePowerKW` is finite (may be slightly negative at night; callers clamp it). Energy figures are
///   nil when the inverter does not count them, never negative. `identifyLive` returns the same reading as `readLive`
///   plus the model name when the inverter reports one.
/// - **Errors.** `CancellationError`; `SunWattError.inverterUnreachable` when there is no connection (away from home,
///   port closed, Modbus TCP off); `ModbusFrame.Failure` when a device answered but not as this kind of inverter
///   (wrong unit ID, another device, Modbus exception): callers try the next unit ID only then.
/// - **Budget.** One connection per call, closed afterwards; callers put a deadline on it (5 s for live reads) and
///   the reader stops when cancelled. Never writes a register.
public protocol LocalInverterReader: Sendable {
    func readLive() async throws -> LiveInverterReading
    /// The model name (when the inverter gives it) and a reading, on one connection. Used by "Find inverter".
    func identifyLive() async throws -> (model: String?, reading: LiveInverterReading)
}

extension HuaweiInverterReading {
    public var live: LiveInverterReading {
        LiveInverterReading(activePowerKW: activePowerKW, dailyEnergyKWh: dailyEnergyKWh, totalEnergyKWh: totalEnergyKWh, meterActivePowerKW: meterActivePowerKW)
    }
}

extension HuaweiModbusClient: LocalInverterReader {
    public func readLive() async throws -> LiveInverterReading { try await read().live }
    public func identifyLive() async throws -> (model: String?, reading: LiveInverterReading) {
        let (model, reading) = try await identify()
        return (model, reading.live)
    }
}

/// One Modbus TCP connection used for several "read holding registers" requests.
enum ModbusSession {
    typealias ReadRegisters = (UInt16, UInt16) async throws -> [UInt16]

    /// Opens the connection, runs `body` with a register reader and closes it. Errors: `CancellationError`,
    /// `SunWattError` as thrown, `ModbusFrame.Failure` when the device answered unexpectedly (e.g. wrong unit ID),
    /// anything else `SunWattError.inverterUnreachable`.
    static func run<T>(_ endpoint: ModbusEndpoint, makeTransport: @Sendable (ModbusEndpoint) -> ModbusTransport?,
                       _ body: (ReadRegisters) async throws -> T) async throws -> T {
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
            return try await body(registers)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as SunWattError {
            throw error
        } catch let error as ModbusFrame.Failure {
            // The device answered, but not as expected (e.g. wrong unit ID): kept apart from "no connection".
            throw error
        } catch {
            throw SunWattError.inverterUnreachable
        }
    }
}

/// The cloud source of the provider (kiosk link, SolarEdge, PVOutput) and the inverter on the home network, read
/// together. When the inverter answers (at home), its live figures replace the cloud's and its reading is added to the
/// cloud curve; otherwise (away, or the inverter is busy) the cloud snapshot is used as it is. Without a cloud source
/// (`cloud` nil), only the inverter.
public struct LocalInverterSolarClient: SolarDataSource {
    public let local: any LocalInverterReader
    public let cloud: SolarDataSource?
    /// Keep the cloud's home consumption when the inverter reads no meter (SolarEdge's comes from its own meter).
    /// Huawei: false, without a Huawei meter the plant has no consumption figures.
    public var keepsCloudHome: Bool
    /// Away from home the connection can hang until this deadline; keep it short.
    public var localDeadline: TimeInterval = 5
    public let now: @Sendable () -> Date

    public init(local: any LocalInverterReader, cloud: SolarDataSource?, keepsCloudHome: Bool = false, now: @escaping @Sendable () -> Date = { Date() }) {
        self.local = local
        self.cloud = cloud
        self.keepsCloudHome = keepsCloudHome
        self.now = now
    }

    /// FusionSolar with the Huawei inverter, plus the kiosk link when there is one.
    public init(local: HuaweiModbusClient, kiosk: SolarDataSource?, now: @escaping @Sendable () -> Date = { Date() }) {
        self.init(local: local, cloud: kiosk, keepsCloudHome: false, now: now)
    }

    public func fetch() async throws -> SolarSnapshot {
        let local = local
        async let fromCloud = Self.fetch(cloud)
        let fromInverter = await SunWattLoader.race(deadline: localDeadline) { try await local.readLive() }
        let cloudResult = await fromCloud
        switch fromInverter {
        case .success(let reading):
            return Self.merge(reading, into: try? cloudResult?.get(), at: now(), keepsBaseHome: keepsCloudHome)
        case .failure(let error):
            if error is CancellationError { throw CancellationError() }
            // Without a cloud source every failure (timeout, refused, odd answer) reads as "the inverter did not answer".
            guard let cloudResult else { throw SunWattError.inverterUnreachable }
            return try cloudResult.get()
        }
    }

    private static func fetch(_ source: SolarDataSource?) async -> Result<SolarSnapshot, Error>? {
        guard let source else { return nil }
        do { return .success(try await source.fetch()) } catch { return .failure(error) }
    }

    /// `base` (the cloud snapshot, or the last one shown) with the live reading: power, today's and total energy,
    /// a curve point at `date`, and home consumption when the inverter reads a meter.
    public static func merge(_ reading: LiveInverterReading, into base: SolarSnapshot?, at date: Date, keepsBaseHome: Bool = false) -> SolarSnapshot {
        var snapshot = base ?? SolarSnapshot(currentPowerKW: nil, dailyEnergyKWh: nil, curve: [], fetchedAt: date, dataAsOf: nil)
        let power = max(reading.activePowerKW, 0)
        snapshot.currentPowerKW = power
        snapshot.dailyEnergyKWh = reading.dailyEnergyKWh ?? dailyEnergy(lifetimeKWh: reading.totalEnergyKWh, at: date, previous: base)
        snapshot.cumulativeEnergyKWh = reading.totalEnergyKWh ?? snapshot.cumulativeEnergyKWh
        if let base {
            snapshot.cloudFetchedAt = base.isLive == true ? base.cloudFetchedAt : (base.cloudFetchedAt ?? base.fetchedAt)
        }
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
        } else if !keepsBaseHome {
            snapshot.home = nil
        }
        return snapshot
    }

    public static func merge(_ reading: HuaweiInverterReading, into base: SolarSnapshot?, at date: Date) -> SolarSnapshot {
        merge(reading.live, into: base, at: date)
    }

    /// Today's energy for an inverter that only has a lifetime counter (SunSpec): `previous` (a live snapshot of the
    /// same Madrid day) plus what the counter grew since. When `previous` is not live (its lifetime may come from the
    /// cloud and not match the inverter's), its own figure. Nil without a snapshot of the same day.
    static func dailyEnergy(lifetimeKWh: Double?, at date: Date, previous: SolarSnapshot?) -> Double? {
        guard let previous, Constants.madridCalendar.isDate(previous.fetchedAt, inSameDayAs: date),
              let daily = previous.dailyEnergyKWh else { return nil }
        // A counter that went back (replaced inverter) or jumped (another inverter) adds nothing.
        if previous.isLive == true, let lifetimeKWh, let before = previous.cumulativeEnergyKWh,
           lifetimeKWh >= before, lifetimeKWh - before < 500 {
            return daily + (lifetimeKWh - before)
        }
        return daily
    }
}

/// The FusionSolar name, from before SunSpec support.
public typealias HuaweiLocalSolarClient = LocalInverterSolarClient
