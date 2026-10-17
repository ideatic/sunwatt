import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// Which inverter protocol the selected provider reads on the home network.
public enum LocalInverterKind: Sendable {
    /// FusionSolar: a Huawei inverter through its dongle (Huawei register map, port 502, unit 1 or 0).
    case huawei
    /// SolarEdge and PVOutput: a SunSpec inverter (SolarEdge, SMA, Fronius, …; port 502 or 1502).
    case sunSpec

    public init(provider: SolarProvider) {
        self = provider.profile.localInverter
    }

    /// Ports tried when the address names none.
    public var ports: [UInt16] { self == .huawei ? [502] : SunSpecClient.usualPorts }
    /// Unit IDs tried, in order.
    public var unitIDs: [Int] { self == .huawei ? [1, 0] : SunSpecClient.usualUnitIDs }
    /// How long one read may take. Huawei dongles pause 1 s after connecting; a SunSpec inverter asked with the wrong
    /// unit ID may not answer at all, so that wait is kept short.
    var readDeadline: TimeInterval { self == .huawei ? 6 : 4 }

    /// `makeTransport` nil: the reader's own (Huawei waits longer after connecting).
    public func reader(_ endpoint: ModbusEndpoint, makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)?) -> any LocalInverterReader {
        switch self {
        case .huawei: HuaweiModbusClient(endpoint: endpoint, makeTransport: makeTransport)
        case .sunSpec: SunSpecClient(endpoint: endpoint, makeTransport: makeTransport)
        }
    }
}

/// "Find inverter": looks for inverters on the home network. Neither Huawei dongles nor SunSpec inverters announce
/// themselves (no Bonjour), so every address of the device's Wi-Fi/Ethernet subnet (at most the 254 around it) is tried
/// on the kind's ports with a quick TCP connect; the few that accept are read like an inverter (each unit ID in turn).
/// Read-only, like the live data. Takes a few seconds; the first run makes iOS ask for Local Network access.
public struct InverterFinder: Sendable {
    public struct Match: Hashable, Sendable {
        /// "host", or "host:port" when the port is not 502: the value to save.
        public let address: String
        public let unitID: Int
        /// e.g. "SUN2000-15KTL-M5" or "SolarEdge SE5000H"; nil when the inverter does not report it.
        public let model: String?
        public let reading: LiveInverterReading

        public init(address: String, unitID: Int, model: String?, reading: LiveInverterReading) {
            self.address = address
            self.unitID = unitID
            self.model = model
            self.reading = reading
        }
    }

    public let kind: LocalInverterKind
    /// Addresses to try.
    var candidates: @Sendable () -> [String]
    /// True when `host` accepts a TCP connection on `port` (within `probeTimeout`).
    var probe: @Sendable (String, UInt16) async -> Bool
    /// Transport for the full read of a host that accepted; nil: the reader's own.
    var makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)?
    /// Connections tried at the same time.
    var concurrency = 64

    public init(kind: LocalInverterKind) {
        self.kind = kind
        candidates = { Self.localCandidates() }
        probe = { host, port in await Self.defaultProbe(host, port: port) }
        makeTransport = nil
    }

    init(kind: LocalInverterKind, candidates: @escaping @Sendable () -> [String], probe: @escaping @Sendable (String, UInt16) async -> Bool,
         makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)?) {
        self.kind = kind
        self.candidates = candidates
        self.probe = probe
        self.makeTransport = makeTransport
    }

    /// Inverters that answered, in address order. Empty when none did (or no local network).
    public func find() async -> [Match] {
        let open = await openPorts(candidates().flatMap { host in kind.ports.map { (host, $0) } })
        let kind = kind, makeTransport = makeTransport
        let matches = await withTaskGroup(of: Match?.self) { group in
            for (host, port) in open {
                group.addTask {
                    try? await Self.identify(kind: kind, host: host, ports: [port], unitIDs: kind.unitIDs, makeTransport: makeTransport).get()
                }
            }
            var found: [Match] = []
            for await match in group { if let match { found.append(match) } }
            return found
        }
        // One entry per address (an inverter answering on both ports appears once).
        var seen = Set<String>()
        return matches
            .sorted { (Self.ipv4(Self.host(of: $0.address)) ?? 0, $0.address) < (Self.ipv4(Self.host(of: $1.address)) ?? 0, $1.address) }
            .filter { seen.insert(Self.host(of: $0.address)).inserted }
    }

    private func openPorts(_ targets: [(String, UInt16)]) async -> [(String, UInt16)] {
        let probe = probe
        return await withTaskGroup(of: (String, UInt16)?.self) { group in
            var pending = targets.makeIterator()
            for _ in 0..<concurrency {
                guard let target = pending.next() else { break }
                group.addTask { await probe(target.0, target.1) ? target : nil }
            }
            var open: [(String, UInt16)] = []
            for await target in group {
                if let target { open.append(target) }
                if Task.isCancelled { group.cancelAll(); break }
                if let next = pending.next() { group.addTask { await probe(next.0, next.1) ? next : nil } }
            }
            return open
        }
    }

    /// Reads `host` on each port and unit ID in turn and returns the first that answers like an inverter. Used by the
    /// scan and by "Test inverter". A Modbus answer that is not an inverter (wrong unit ID, "gateway target failed")
    /// or, for SunSpec, no answer in time tries the next unit ID; a refused connection the next port.
    public static func identify(kind: LocalInverterKind, host: String, ports: [UInt16], unitIDs: [Int],
                                makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)?) async -> Result<Match, Error> {
        var lastError: Error = SunWattError.inverterUnreachable
        for port in ports {
            units: for unit in unitIDs {
                guard (0...247).contains(unit) else { continue }
                let endpoint = ModbusEndpoint(host: host, port: port, unitID: UInt8(unit))
                let reader = kind.reader(endpoint, makeTransport: makeTransport)
                switch await SunWattLoader.race(deadline: kind.readDeadline, { try await reader.identifyLive() }) {
                case .success(let (model, reading)):
                    return .success(Match(address: endpoint.text, unitID: unit, model: model, reading: reading))
                case .failure(let error):
                    if error is CancellationError { return .failure(error) }
                    lastError = error
                    let timedOut: Bool = if case .network? = error as? SunWattError { true } else { false }
                    if error is ModbusFrame.Failure || (kind == .sunSpec && timedOut) { continue units }
                    break units
                }
            }
        }
        // An answer that never was an inverter reads as "no inverter there".
        return .failure(lastError is SunWattError ? lastError : SunWattError.inverterUnreachable)
    }

    static func host(of address: String) -> String {
        ModbusEndpoint(parsing: address)?.host ?? address
    }

    // MARK: Addresses

    /// The other addresses of `address`'s subnet. Subnets larger than /24 are narrowed to the /24 around the
    /// device (home routers almost always use /24; a /16 would be 65,000 addresses). Nothing for /31 and /32.
    static func neighbours(address: UInt32, mask: UInt32) -> [String] {
        let mask = mask < 0xFFFF_FF00 ? 0xFFFF_FF00 : mask
        let network = address & mask
        let broadcast = network | ~mask
        guard broadcast > network + 1 else { return [] }
        return (network + 1..<broadcast).filter { $0 != address }.map(Self.dotted)
    }

    static func dotted(_ value: UInt32) -> String {
        "\(value >> 24).\(value >> 16 & 0xFF).\(value >> 8 & 0xFF).\(value & 0xFF)"
    }

    static func ipv4(_ text: String) -> UInt32? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false).compactMap { UInt32($0) }
        guard parts.count == 4, parts.allSatisfy({ $0 < 256 }) else { return nil }
        return parts.reduce(0) { $0 << 8 | $1 }
    }

    /// Neighbours on the Wi-Fi/Ethernet interfaces ("en…"); not cellular, VPN or loopback.
    static func localCandidates() -> [String] {
        var seen = Set<String>()
        return localIPv4Networks().flatMap { neighbours(address: $0.address, mask: $0.mask) }.filter { seen.insert($0).inserted }
    }

    static func localIPv4Networks() -> [(address: UInt32, mask: UInt32)] {
        #if canImport(Darwin)
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var networks: [(address: UInt32, mask: UInt32)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, String(cString: entry.ifa_name).hasPrefix("en"),
                  let address = entry.ifa_addr, address.pointee.sa_family == sa_family_t(AF_INET),
                  let netmask = entry.ifa_netmask else { continue }
            func value(_ pointer: UnsafeMutablePointer<sockaddr>) -> UInt32 {
                pointer.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            }
            networks.append((value(address), value(netmask)))
        }
        return networks
        #else
        return []
        #endif
    }

    // MARK: Probe

    static let probeTimeout: TimeInterval = 1.2

    /// TCP connect without the dongle pause; closed at once.
    static func defaultProbe(_ host: String, port: UInt16) async -> Bool {
        #if canImport(Network) && !os(watchOS)
        let transport = NWModbusTransport(host: host, port: port, settleDelay: .zero)
        defer { transport.close() }
        if case .success = await SunWattLoader.race(deadline: probeTimeout, { try await transport.open() }) { return true }
        return false
        #else
        return false
        #endif
    }
}
