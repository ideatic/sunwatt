import SwiftUI

/// Snippet shown under the Siri / Shortcuts answer: the figure plus a small chart.
struct PriceSnippetView: View {
    let answer: PriceAnswer
    let series: PriceSeries?
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let price = answer.eurPerKWh {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(IntentStrings.current.eurPerKWh(price))
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(answer.level.map { Theme.Price.color(for: $0) } ?? Color.primary)
                    Text("€/kWh").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    if let level = answer.level {
                        Text(IntentStrings.current.levelName(level)).font(.subheadline.weight(.medium))
                    }
                }
                if let series {
                    PriceBarChart(series: series, points: series.hours(onDayOf: now), now: now, style: .mini)
                        .frame(height: 56)
                }
            } else {
                Text(answer.dialog).font(.subheadline)
            }
        }
        .padding()
    }
}

struct SolarSnippetView: View {
    let answer: SolarAnswer
    let snapshot: SolarSnapshot?
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let kW = answer.currentKW, let snapshot {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "sun.max.fill").foregroundStyle(Theme.Solar.amber)
                    Text(IntentStrings.current.number(kW))
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                    Text("kW").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                    if let kWh = answer.todayKWh {
                        Text(IntentStrings.current.text("Today %@ kWh", IntentStrings.current.energy(kWh)))
                            .font(.subheadline.weight(.medium))
                    }
                }
                SolarCurveChart(snapshot: snapshot, now: now, style: .mini)
                    .frame(height: 56)
            } else {
                Text(answer.dialog).font(.subheadline)
            }
        }
        .padding()
    }
}

struct CheapestHoursSnippetView: View {
    let answer: CheapestHoursAnswer
    let timeZone: TimeZone

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if answer.hours.isEmpty {
                Text(answer.dialog).font(.subheadline)
            } else {
                ForEach(answer.hours) { point in
                    HStack {
                        Text(Format.hour(point.start, timeZone: timeZone)).font(.headline).monospacedDigit()
                        Spacer(minLength: 0)
                        Text("\(IntentStrings.current.eurPerKWh(point.eurPerKWh)) €/kWh")
                            .font(.subheadline).monospacedDigit().foregroundStyle(Theme.Price.color(for: .low))
                    }
                }
            }
        }
        .padding()
    }
}
