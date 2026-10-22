import Testing
import Foundation
import SwiftUI
import WidgetKit
@testable import SunWattKit

@Suite struct WatchSolarGaugeTests {
    @Test func powerIsPositionedWithinTodaysPeak() throws {
        let entry = SampleData.entry(.day)
        let gauge = try #require(WatchSolarGauge(entry: entry))
        #expect(gauge.upper >= 1)
        #expect(gauge.value >= 0 && gauge.value <= gauge.upper)
        #expect(gauge.fraction >= 0 && gauge.fraction <= 1)
        #expect(abs(gauge.fraction - gauge.value / gauge.upper) < 1e-9)
        let power = try #require(entry.data.solar?.currentPowerKW)
        #expect(abs(gauge.value - min(power, gauge.upper)) < 1e-9)
    }

    @Test func nightRingIsEmpty() throws {
        let gauge = try #require(WatchSolarGauge(entry: SampleData.entry(.night)))
        #expect(gauge.value == 0)
        #expect(gauge.fraction == 0)
    }

    @Test func peakFloorIsOneKilowatt() throws {
        var entry = SampleData.entry(.day)
        var solar = try #require(entry.data.solar)
        solar.curve = solar.curve.map { var p = $0; p.kW = p.kW.map { min($0, 0.2) }; return p }
        solar.currentPowerKW = 0.2
        entry.data.solar = solar
        let gauge = try #require(WatchSolarGauge(entry: entry))
        #expect(gauge.upper == 1)
        #expect(abs(gauge.fraction - 0.2) < 1e-9)
    }

    @Test func unavailableStatesHaveNoGauge() {
        #expect(WatchSolarGauge(entry: SampleData.entry(.error)) == nil)
        #expect(WatchSolarGauge(entry: SampleData.notConfiguredEntry()) == nil)
    }

    @Test func afterMidnightShowsNoDataYet() {
        var entry = SampleData.entry(.day)
        entry.date = entry.date.addingTimeInterval(11 * 3600)
        #expect(WatchSolarState(entry: entry) == .noDataYet)
        // Yesterday's power must not move the ring.
        #expect(WatchSolarGauge(entry: entry)?.value == 0)
    }

    @Test func statesMapFromScenarios() {
        #expect(WatchSolarState(entry: SampleData.entry(.day)) == .data(idle: false, stale: false))
        #expect(WatchSolarState(entry: SampleData.entry(.night)) == .data(idle: true, stale: false))
        #expect(WatchSolarState(entry: SampleData.entry(.stale)) == .data(idle: false, stale: true))
        #expect(WatchSolarState(entry: SampleData.entry(.error)) == .error)
        #expect(WatchSolarState(entry: SampleData.notConfiguredEntry()) == .notConfigured)
    }

    @Test func priceGaugeStaysInRangeAndMatchesFraction() throws {
        let gauge = try #require(WatchCornerGauge(entry: SampleData.entry(.day)))
        #expect(gauge.lower <= gauge.value && gauge.value <= gauge.upper)
        #expect(gauge.fraction >= 0 && gauge.fraction <= 1)
    }
}

@MainActor
@Suite struct WatchSolarRenderTests {
    private func entries() -> [(String, SunWattEntry)] {
        var midnight = SampleData.entry(.day)
        midnight.date = midnight.date.addingTimeInterval(11 * 3600)
        return SampleData.Scenario.allCases.map { ($0.rawValue, SampleData.entry($0)) }
            + [("unconfigured", SampleData.notConfiguredEntry()), ("midnight", midnight)]
    }

    @Test func everySolarFamilyRendersAtExpectedSize() throws {
        for (name, entry) in entries() {
            for family in WatchComplicationFamily.allCases {
                for mode in [WidgetRenderingMode.fullColor, .accented, .vibrant] {
                    let kind = WatchSnapshotView.Kind.solarComplication(family)
                    let renderer = ImageRenderer(content: WatchSnapshotView(entry: entry, kind: kind, renderingMode: mode))
                    let image = try #require(renderer.cgImage, "\(name) \(family) \(mode)")
                    let size = WatchSnapshotView.imageSize(for: kind)
                    #expect(image.width == Int(size.width) && image.height == Int(size.height), "\(name) \(family)")
                }
            }
        }
    }

    @Test func priceCircularGaugeRendersInEveryMode() throws {
        for (name, entry) in entries() {
            for mode in [WidgetRenderingMode.fullColor, .accented, .vibrant] {
                let renderer = ImageRenderer(content: WatchSnapshotView(entry: entry, kind: .complication(.circular), renderingMode: mode))
                #expect(renderer.cgImage != nil, "\(name) \(mode)")
            }
        }
    }
}
