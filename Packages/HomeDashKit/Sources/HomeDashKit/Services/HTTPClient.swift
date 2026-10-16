import Foundation

/// Minimal HTTP abstraction so clients can be tested with fixtures.
public protocol HTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPClient {}

public protocol SolarDataSource: Sendable {
    func fetch() async throws -> SolarSnapshot
}

public protocol PriceDataSource: Sendable {
    /// Fetches prices for the Europe/Madrid day that contains `day`.
    func fetch(day: Date) async throws -> PriceSeries
}

/// Errors shown to the user. `errorDescription` is in Spanish (UI language).
public enum HomeDashError: Error, Equatable, Sendable, LocalizedError {
    case invalidKioskURL
    case notConfigured
    case http(status: Int)
    case badResponse(String)
    case unavailable
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidKioskURL: "La URL del kiosco no es válida. Debe contener el parámetro kk."
        case .notConfigured: "Configura la URL del kiosco en Ajustes."
        case .http(let status): "El servidor respondió con el código \(status)."
        case .badResponse(let detail): "Respuesta no válida: \(detail)"
        case .unavailable: "Datos no disponibles todavía."
        case .network(let detail): "Error de red: \(detail)"
        }
    }
}
