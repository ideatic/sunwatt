import Foundation
import Testing
@testable import SunWattKit

/// "Find inverter": subnet addresses, the probe-then-identify scan and the model name.
@Suite struct InverterFinderTests {
    @Test func neighboursOfAHomeSubnet() throws {
        let address = try #require(HuaweiInverterFinder.ipv4("192.168.1.23"))
        let hosts = HuaweiInverterFinder.neighbours(address: address, mask: 0xFFFF_FF00)
        #expect(hosts.count == 253)
        #expect(hosts.first == "192.168.1.1" && hosts.last == "192.168.1.254")
        #expect(!hosts.contains("192.168.1.23") && !hosts.contains("192.168.1.0") && !hosts.contains("192.168.1.255"))
    }

    @Test func largeSubnetsAreNarrowedAndTinyOnesSkipped() throws {
        let address = try #require(HuaweiInverterFinder.ipv4("10.0.5.9"))
        let hosts = HuaweiInverterFinder.neighbours(address: address, mask: 0xFFFF_0000)
        #expect(hosts.count == 253 && hosts.allSatisfy { $0.hasPrefix("10.0.5.") })
        #expect(HuaweiInverterFinder.neighbours(address: address, mask: 0xFFFF_FFFF).isEmpty)
        #expect(HuaweiInverterFinder.neighbours(address: address, mask: 0xFFFF_FFFE).isEmpty)
        // /30: the one other host.
        #expect(HuaweiInverterFinder.neighbours(address: try #require(HuaweiInverterFinder.ipv4("10.0.0.1")), mask: 0xFFFF_FFFC) == ["10.0.0.2"])
    }

    @Test func ipv4Parsing() {
        #expect(HuaweiInverterFinder.ipv4("192.168.1.50") == 0xC0A8_0132)
        #expect(HuaweiInverterFinder.ipv4("192.168.1") == nil)
        #expect(HuaweiInverterFinder.ipv4("192.168.1.300") == nil)
        #expect(HuaweiInverterFinder.dotted(0xC0A8_0132) == "192.168.1.50")
    }

    @Test func modelNameFromRegisters() {
        let text = Array("SUN2000-15KTL-M5".utf8)
        var registers = stride(from: 0, to: text.count, by: 2).map { UInt16(text[$0]) << 8 | UInt16(text[$0 + 1]) }
        registers += Array(repeating: 0, count: 15 - registers.count)
        #expect(HuaweiModbusClient.text(registers) == "SUN2000-15KTL-M5")
        #expect(HuaweiModbusClient.text(Array(repeating: 0, count: 15)) == nil)
        #expect(HuaweiModbusClient.text([0x01FF]) == nil)
    }

    @Test func findsOnlyHostsThatAnswerLikeAnInverter() async {
        var registers = fakeInverterRegisters()
        let model = Array("SUN2000-15KTL-M5".utf8) + [0, 0]
        for i in 0..<9 { registers[30000 + UInt16(i)] = UInt16(model[2 * i]) << 8 | UInt16(model[2 * i + 1]) }
        for i in 9..<15 { registers[30000 + UInt16(i)] = 0 }
        let inverterRegisters = registers
        let probed = Probed()
        let finder = HuaweiInverterFinder(
            candidates: { (1...254).map { "192.168.1.\($0)" } },
            probe: { host in
                probed.add(host)
                // .7: some other device with port 502 open; .50: the dongle (unit 0); .60: dongle on unit 1.
                return ["192.168.1.7", "192.168.1.50", "192.168.1.60"].contains(host)
            },
            makeTransport: { endpoint -> ModbusTransport? in
                switch endpoint.host {
                case "192.168.1.50": return FakeModbusTransport(registers: inverterRegisters, unit: 0)
                case "192.168.1.60": return FakeModbusTransport(registers: fakeInverterRegisters(powerW: 1000))
                default: return FakeModbusTransport(registers: [:], reachable: false)
                }
            })
        let matches = await finder.find()
        #expect(probed.count == 254)
        #expect(matches.map(\.host) == ["192.168.1.50", "192.168.1.60"])
        #expect(matches[0].unitID == 0 && matches[0].model == "SUN2000-15KTL-M5" && matches[0].reading.activePowerKW == 2.912)
        // No model registers: still an inverter, model unknown.
        #expect(matches[1].unitID == 1 && matches[1].model == nil && matches[1].reading.activePowerKW == 1)
    }

    @Test func nothingOpenFindsNothing() async {
        let finder = HuaweiInverterFinder(candidates: { ["192.168.1.2"] }, probe: { _ in false }, makeTransport: { _ in nil })
        #expect(await finder.find().isEmpty)
    }

    final class Probed: @unchecked Sendable {
        private let lock = NSLock()
        private var hosts = Set<String>()
        func add(_ host: String) { _ = lock.withLock { hosts.insert(host) } }
        var count: Int { lock.withLock { hosts.count } }
    }
}

@MainActor @Suite struct FindInverterModelTests {
    @Test func demoFindsTheSampleInverter() async {
        let model = AppModel(demo: .day, settings: SunWattSettings(kioskURL: testKioskURL))
        let matches = await model.findInverters()
        #expect(matches.map(\.host) == ["192.168.1.50"])
    }
}
