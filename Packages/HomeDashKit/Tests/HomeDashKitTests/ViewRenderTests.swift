import Testing
import SwiftUI
import WidgetKit
@testable import HomeDashKit

@MainActor
private func renderedSize(_ view: some View, scale: CGFloat = 1) -> CGSize? {
    let renderer = ImageRenderer(content: view)
    renderer.scale = scale
    guard let image = renderer.cgImage else { return nil }
    return CGSize(width: image.width, height: image.height)
}

@MainActor
@Test(arguments: HomeDashFamily.allCases, SampleData.Scenario.allCases)
func everyFamilyAndStateRendersAtExpectedSize(family: HomeDashFamily, scenario: SampleData.Scenario) {
    Format.locale = Locale(identifier: "es_ES")
    let view = WidgetSnapshotView(entry: SampleData.entry(scenario), family: family, locale: Locale(identifier: "es_ES"))
    let size = renderedSize(view)
    #expect(size == WidgetSnapshotView.imageSize(for: family))
}

@MainActor
@Test(arguments: WidgetContent.allCases, PriceWindow.allCases)
func contentAndWindowVariantsRender(content: WidgetContent, window: PriceWindow) {
    for family in HomeDashFamily.allCases {
        let view = WidgetSnapshotView(
            entry: SampleData.entry(.day), family: family, content: content, window: window, scheme: .dark)
        #expect(renderedSize(view) == WidgetSnapshotView.imageSize(for: family))
    }
}

@MainActor
@Test func notConfiguredAndForeignRegionRender() {
    let notConfigured = WidgetSnapshotView(entry: SampleData.notConfiguredEntry(), family: .medium)
    #expect(renderedSize(notConfigured) == WidgetSnapshotView.imageSize(for: .medium))
    let france = WidgetSnapshotView(entry: SampleData.entry(.day, region: .france), family: .large, locale: Locale(identifier: "fr_FR"))
    #expect(renderedSize(france) == WidgetSnapshotView.imageSize(for: .large))
}

@MainActor
@Test func renderingModesRender() {
    for mode in [WidgetRenderingMode.accented, .vibrant] {
        let view = WidgetSnapshotView(entry: SampleData.entry(.day), family: .small, scheme: .dark, renderingMode: mode)
        #expect(renderedSize(view) == WidgetSnapshotView.imageSize(for: .small))
    }
}

@MainActor
@Test func chartComponentsRenderAtGivenSize() {
    let entry = SampleData.entry(.day)
    let solar = entry.data.solar!
    let prices = entry.data.prices!
    let curve = SolarCurveChart(snapshot: solar, now: entry.date, style: .full).frame(width: 300, height: 100)
    #expect(renderedSize(curve) == CGSize(width: 300, height: 100))
    let bars = PriceBarChart(series: prices, points: prices.hours(onDayOf: entry.date), now: entry.date, style: .mini)
        .frame(width: 140, height: 60)
    #expect(renderedSize(bars) == CGSize(width: 140, height: 60))
    let figure = FigureText(value: "4,8", unit: "kW", size: 34)
    #expect(renderedSize(figure) != nil)
    #expect(renderedSize(StaleBadge(since: entry.date)) != nil)
    #expect(renderedSize(SourceStateView(kind: .notConfigured).frame(width: 100, height: 100)) == CGSize(width: 100, height: 100))
    #expect(renderedSize(PricePill(eurPerKWh: 0.147, fraction: 0.4)) != nil)
}
