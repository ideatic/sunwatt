import Foundation
@testable import HomeDashKit

/// Fixture-backed HTTP client. Routes by a substring of the request URL; records every request.
final class StubHTTPClient: HTTPClient, @unchecked Sendable {
    enum Reply {
        case ok(Data, status: Int = 200)
        case failure(Error)
    }

    private let lock = NSLock()
    private var routes: [(match: String, reply: Reply)] = []
    private var fallback: Reply
    private(set) var requests: [URLRequest] = []

    init(_ fallback: Reply = .failure(URLError(.notConnectedToInternet))) {
        self.fallback = fallback
    }

    /// Replies with `reply` for any URL whose absoluteString contains `match` (first match wins).
    func on(_ match: String, _ reply: Reply) {
        lock.withLock { routes.append((match, reply)) }
    }

    var requestedURLs: [URL] { lock.withLock { requests.compactMap(\.url) } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let reply: Reply = lock.withLock {
            requests.append(request)
            let url = request.url?.absoluteString ?? ""
            return routes.first { url.contains($0.match) }?.reply ?? fallback
        }
        switch reply {
        case .ok(let data, let status):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (data, response)
        case .failure(let error):
            throw error
        }
    }
}
