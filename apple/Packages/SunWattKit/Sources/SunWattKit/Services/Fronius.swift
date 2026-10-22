import Foundation

// Fronius Solar API: the JSON interface Fronius inverters (Datamanager: Symo, Primo, Galvo, Eco; GEN24/Tauro) serve
// on the home network over plain HTTP, port 80, without login. Read-only by design (the API has no write calls).
// GEN24 inverters ship with it turned off ("Solar API" in the inverter's web page, under Communication).
//
// One request per reading: GET /solar_api/v1/GetPowerFlowRealtimeData.fcgi →
// {"Head": {"Status": {"Code": 0}}, "Body": {"Data": {"Site": {"P_PV", "P_Grid", "P_Load", "E_Day", "E_Total", …}}}}
// Units W and Wh. P_PV is null at night, P_Grid null without a Fronius meter, E_Day null on GEN24 (today then comes
// from the lifetime counter, like SunSpec: `LocalInverterSolarClient.dailyEnergy`). Signs: P_Grid > 0 buying from
// the grid, P_Load < 0 the home's consumption.

/// Reads a Fronius inverter through its Solar API. `endpoint.port` is the HTTP port (80); the unit ID is not used.
public struct FroniusSolarAPIClient: LocalInverterReader {
    public let endpoint: ModbusEndpoint
    let http: HTTPClient

    public init(endpoint: ModbusEndpoint, http: HTTPClient? = nil) {
        self.endpoint = endpoint
        self.http = http ?? URLSession.sunWatt
    }

    /// The Solar API's port when the address names none.
    public static let port: UInt16 = 80

    public func readLive() async throws -> LiveInverterReading {
        try await identifyLive().reading
    }

    /// The Solar API does not name the model; any answer it gives is a Fronius inverter, so the model reads "Fronius".
    public func identifyLive() async throws -> (model: String?, reading: LiveInverterReading) {
        #if os(watchOS)
        // Like the Modbus readers: the watch does not read the home network (its plist allows no plain HTTP).
        throw SunWattError.inverterUnreachable
        #else
        guard let url = URL(string: "http://\(endpoint.host):\(endpoint.port)/solar_api/v1/GetPowerFlowRealtimeData.fcgi") else {
            throw SunWattError.inverterUnreachable
        }
        let data: Data
        do {
            let (body, response) = try await HTTPFetch.perform(http, HTTPFetch.request(url, timeout: 5))
            try HTTPFetch.checkStatus(response)
            data = body
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // No connection, or something else answered on the port (router, printer: HTTP 404).
            throw SunWattError.inverterUnreachable
        }
        return ("Fronius", try Self.parse(data))
        #endif
    }

    /// Reading from a GetPowerFlowRealtimeData answer. Anything that is not a Solar API answer with status 0 reads as
    /// "no inverter there" (`inverterUnreachable`): another device on the port, or the inverter busy.
    static func parse(_ data: Data) throws -> LiveInverterReading {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let head = root["Head"] as? [String: Any], let status = head["Status"] as? [String: Any],
              number(status["Code"]) == 0,
              let body = root["Body"] as? [String: Any], let payload = body["Data"] as? [String: Any],
              let site = payload["Site"] as? [String: Any] else {
            throw SunWattError.inverterUnreachable
        }
        func kW(_ key: String) -> Double? { number(site[key]).map { $0 / 1000 } }
        func kWh(_ key: String) -> Double? { number(site[key]).map { max($0, 0) / 1000 } }
        // Positive P_Grid is bought; the reading's meter is positive when feeding the grid.
        let grid = kW("P_Grid")
        return LiveInverterReading(
            activePowerKW: kW("P_PV") ?? 0,
            dailyEnergyKWh: kWh("E_Day"),
            totalEnergyKWh: kWh("E_Total"),
            meterActivePowerKW: grid.map { -$0 },
            // P_Load is negative while the home uses power; with a battery it is the only right figure.
            homeConsumptionKW: grid == nil ? nil : kW("P_Load").map { max(-$0, 0) })
    }

    /// A finite JSON number; nil for null (NSNull), text or a missing key. No `is Bool` check: JSON 0 and 1 bridge to
    /// Bool, and status code 0 must read as 0.
    private static func number(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber else { return nil }
        let double = number.doubleValue
        return double.isFinite ? double : nil
    }
}
