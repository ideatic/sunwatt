import Foundation

/// A price source that can fetch today and, optionally, tomorrow in one call.
public protocol PriceRangeDataSource: PriceDataSource {
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries
}
