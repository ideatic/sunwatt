import Foundation
#if canImport(Darwin)
import Darwin
#endif

/// "Find inverter": looks for Huawei dongles on the home network. Huawei dongles do not announce themselves (no
/// Bonjour), so every address of the device's Wi-Fi/Ethernet subnet (at most the 254 around it) is tried on port 502
/// with a quick TCP connect; the few that accept are read like an inverter (unit ID 1, then 0). Read-only, like the
/// live data. Takes a few seconds; the first run makes iOS ask for Local Network access.
public struct HuaweiInverterFinder: Sendable {
    public struct Match: Hashable, Sendable {
        public let host: String
        public let unitID: Int
        /// e.g. "SUN2000-15KTL-M5"; nil when the dongle does not report it.
        public let model: String?
        public let reading: HuaweiInverterReading

        public init(host: String, unitID: Int, model: String?, reading: HuaweiInverterReading) {
            self.host = host
            self.unitID = unitID
            self.model = model
            self.reading = reading
        }
    }

    /// Addresses to try.
    var candidates: @Sendable () -> [String]
    /// True when `host` accepts a TCP connection on port 502 (within `probeTimeout`).
    var probe: @Sendable (String) async -> Bool
    /// Transport for the full read of a host that accepted.
    var makeTransport: @Sendable (HuaweiLocalEndpoint) -> ModbusTransport?
    /// Connections tried at the same time.
    var concurrency = 48
    var identifyDeadline: TimeInterval = 6

    public init() {
        candidates = { Self.localCandidates() }
        probe = { host in await Self.defaultProbe(host) }
        makeTransport = HuaweiModbusClient.defaultTransport
    }

    init(candidates: @escaping @Sendable () -> [String], probe: @escaping @Sendable (String) async -> Bool,
         makeTransport: @escaping @Sendable (HuaweiLocalEndpoint) -> ModbusTransport?) {
        self.candidates = candidates
        self.probe = probe
        self.makeTransport = makeTransport
    }

    /// Inverters that answered, in address order. Empty when none did (or no local network).
    public func find() async -> [Match] {
        let open = await openHosts(candidates())
        let matches = await withTaskGroup(of: Match?.self) { group in
            for host in open { group.addTask { await identify(host) } }
            var found: [Match] = []
            for await match in group { if let match { found.append(match) } }
            return found
        }
        return matches.sorted { Self.ipv4($0.host) ?? 0 < Self.ipv4($1.host) ?? 0 }
    }

    private func openHosts(_ hosts: [String]) async -> [String] {
        await withTaskGroup(of: String?.self) { group in
            var pending = hosts.makeIterator()
            for _ in 0..<concurrency {
                guard let host = pending.next() else { break }
                group.addTask { await probe(host) ? host : nil }
            }
            var open: [String] = []
            for await host in group {
                if let host { open.append(host) }
                if Task.isCancelled { group.cancelAll(); break }
                if let next = pending.next() { group.addTask { await probe(next) ? next : nil } }
            }
            return open
        }
    }

    /// A Modbus answer with an exception (e.g. "gateway target failed") means another unit ID may work.
    private func identify(_ host: String) async -> Match? {
        for unit in [1, 0] {
            guard let endpoint = HuaweiLocalEndpoint(parsing: host, unitID: unit) else { return nil }
            let client = HuaweiModbusClient(endpoint: endpoint, makeTransport: makeTransport)
            let result = await SunWattLoader.race(deadline: identifyDeadline) {
                let (model, reading) = try await client.identify()
                return Match(host: host, unitID: unit, model: model, reading: reading)
            }
            switch result {
            case .success(let match): return match
            case .failure(let error): if !(error is ModbusFrame.Failure) { return nil }
            }
        }
        return nil
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

    /// TCP connect to port 502 without the dongle pause; closed at once.
    static func defaultProbe(_ host: String) async -> Bool {
        #if canImport(Network) && !os(watchOS)
        let transport = NWModbusTransport(host: host, port: 502, settleDelay: .zero)
        defer { transport.close() }
        if case .success = await SunWattLoader.race(deadline: probeTimeout, { try await transport.open() }) { return true }
        return false
        #else
        return false
        #endif
    }
}
