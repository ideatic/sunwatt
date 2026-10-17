import Foundation

// SunSpec over Modbus TCP: the common register map of SolarEdge, SMA, Fronius, Kostal, ABB/FIMER and
// many others (SunSpec Alliance "Information Model Specification"). Read-only, like the Huawei client.
//
// Layout: the marker "SunS" (0x5375 0x6E53) at a base address (40000, else 0 or 50000), then a chain of models, each
// [ID, length, length registers of data], ending with ID 0xFFFF. SunWatt reads model 1 (common: manufacturer and
// model name) and the first inverter model: 101/102/103 (integers with scale factors) or 111/112/113 (floats).
// SunSpec has no "energy today" register, only a lifetime counter: today's energy comes from the cloud figure plus
// what the counter grew since (`LocalInverterSolarClient.dailyEnergy`).
//
// Usual settings: SolarEdge port 1502, unit 1 (turned on in SetApp or by the installer: "Modbus TCP"); SMA port 502,
// unit 126; Fronius port 502, unit 1 ("Modbus TCP" with "SunSpec Model Type: float" or "int+SF").

/// Reads a SunSpec inverter. Three to six requests per reading (marker, model headers, common and inverter blocks).
public struct SunSpecClient: LocalInverterReader {
    public let endpoint: ModbusEndpoint
    let makeTransport: @Sendable (ModbusEndpoint) -> ModbusTransport?

    public init(endpoint: ModbusEndpoint, makeTransport: (@Sendable (ModbusEndpoint) -> ModbusTransport?)? = nil) {
        self.endpoint = endpoint
        self.makeTransport = makeTransport ?? SunSpecClient.defaultTransport
    }

    /// Network.framework TCP with a short pause after connecting (some gateways drop a request sent at once);
    /// nil on watchOS, where apps cannot open such connections.
    public static let defaultTransport: @Sendable (ModbusEndpoint) -> ModbusTransport? = { endpoint in
        #if canImport(Network) && !os(watchOS)
        NWModbusTransport(host: endpoint.host, port: endpoint.port, settleDelay: .milliseconds(200))
        #else
        nil
        #endif
    }

    /// Ports to try when the user typed none: SolarEdge's 1502 and the standard 502.
    public static let usualPorts: [UInt16] = [502, 1502]
    /// Unit IDs to try when the saved one does not answer: most inverters 1, SMA 126, some gateways 2.
    public static let usualUnitIDs: [Int] = [1, 126, 2]

    public func readLive() async throws -> LiveInverterReading {
        try await identifyLive().reading
    }

    public func identifyLive() async throws -> (model: String?, reading: LiveInverterReading) {
        try await ModbusSession.run(endpoint, makeTransport: makeTransport) { registers in
            try await Self.read(registers)
        }
    }

    static let marker: [UInt16] = [0x5375, 0x6E53]
    static let integerInverterModels: Set<UInt16> = [101, 102, 103]
    static let floatInverterModels: Set<UInt16> = [111, 112, 113]

    static func read(_ registers: ModbusSession.ReadRegisters) async throws -> (model: String?, reading: LiveInverterReading) {
        var start: UInt16?
        for base: UInt16 in [40000, 0, 50000] {
            do {
                if try await registers(base, 2) == marker { start = base; break }
            } catch is ModbusFrame.Failure {
                continue
            }
        }
        guard let start else { throw ModbusFrame.Failure.unexpectedDevice }
        var address = start + 2
        var model: String?
        // A real chain has a handful of models; the limit stops a device that never ends it.
        for _ in 0..<40 {
            let header = try await registers(address, 2)
            let id = header[0], length = header[1]
            guard id != 0xFFFF, length > 0, Int(address) + 2 + Int(length) <= Int(UInt16.max) else { break }
            if id == 1, length >= 32 {
                model = commonModelName(try await registers(address + 2, 32))
            } else if integerInverterModels.contains(id) || floatInverterModels.contains(id) {
                let data = try await registers(address + 2, min(length, 50))
                guard let reading = inverterReading(model: id, data: data) else { throw ModbusFrame.Failure.unexpectedDevice }
                return (model, reading)
            }
            address += 2 + length
        }
        throw ModbusFrame.Failure.unexpectedDevice
    }

    /// Manufacturer (registers 0-15) and model (16-31) of the common model, e.g. "SolarEdge SE5000H" or "Fronius
    /// Symo 8.2-3-M". The model alone when it already starts with the manufacturer.
    static func commonModelName(_ data: [UInt16]) -> String? {
        guard data.count >= 32 else { return nil }
        let manufacturer = ModbusFrame.text(Array(data[0..<16]))
        let model = ModbusFrame.text(Array(data[16..<32]))
        switch (manufacturer, model) {
        case let (maker?, model?):
            return model.lowercased().hasPrefix(maker.lowercased()) ? model : "\(maker) \(model)"
        case let (maker, model):
            return model ?? maker
        }
    }

    /// The data of inverter model 101-103 or 111-113 (without the ID and length registers). Nil when the power is
    /// not implemented or the block is too short.
    static func inverterReading(model id: UInt16, data: [UInt16]) -> LiveInverterReading? {
        if integerInverterModels.contains(id) {
            // W 12, W_SF 13, WH 22-23 (acc32, 0 = not implemented), WH_SF 24.
            guard data.count >= 25, data[12] != 0x8000 else { return nil }
            let watts = scaled(Double(Int16(bitPattern: data[12])), data[13])
            let wattHours = ModbusFrame.uint32(data, at: 22)
            let lifetime = wattHours == 0 ? nil : scaled(Double(wattHours), data[24]).map { $0 / 1000 }
            guard let watts else { return nil }
            return LiveInverterReading(activePowerKW: watts / 1000, totalEnergyKWh: lifetime)
        }
        // Floats: W 20-21, WH 30-31; NaN = not implemented.
        guard data.count >= 32 else { return nil }
        let watts = Double(Float(bitPattern: ModbusFrame.uint32(data, at: 20)))
        let wattHours = Double(Float(bitPattern: ModbusFrame.uint32(data, at: 30)))
        guard watts.isFinite else { return nil }
        return LiveInverterReading(activePowerKW: watts / 1000, totalEnergyKWh: wattHours.isFinite && wattHours > 0 ? wattHours / 1000 : nil)
    }

    /// `value` × 10^`factor` (a signed scale factor register); nil when the factor is not implemented (0x8000) or
    /// out of range.
    static func scaled(_ value: Double, _ factor: UInt16) -> Double? {
        let exponent = Int16(bitPattern: factor)
        guard factor != 0x8000, (-10...10).contains(exponent) else { return nil }
        return value * pow(10, Double(exponent))
    }
}
