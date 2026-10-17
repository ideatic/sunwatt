#if os(iOS) || os(macOS)
// App screens are iPhone/iPad/Mac only; the watch has its own UI (Views/Watch).
import SwiftUI

/// Scrollable dashboard of the app: solar, price, widget status and footer. Pure content (no scroll view,
/// no navigation), so snapshots can render it; `DashboardScreen` wraps it for the app.
struct DashboardContent: View {
    let model: AppModel
    /// Preselected chart positions and day, for snapshots.
    var solarSelection: Date?
    var priceSelection: Date?
    var startsOnTomorrow = false

    @Environment(\.appIdiom) private var idiom

    var body: some View {
        let isPlaceholder = model.entry == nil
        let entry = model.entry ?? SampleData.entry(.day, region: model.settings.priceRegion)

        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
            DashboardHeader(model: model)
            if let expiry = model.kioskExpiryNoticeDate {
                KioskExpiryNotice(model: model, expiry: expiry)
            }
            Group {
                if idiom != .phone {
                    // Two columns when there is room (Mac window, iPad); stacked in a narrow iPad split view.
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .top, spacing: Theme.Spacing.l) {
                            solarSection(entry).frame(maxHeight: .infinity, alignment: .top)
                            priceSection(entry).frame(maxHeight: .infinity, alignment: .top)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minWidth: 620)
                        VStack(alignment: .leading, spacing: Theme.Spacing.l) {
                            solarSection(entry)
                            priceSection(entry)
                        }
                    }
                } else {
                    solarSection(entry)
                    priceSection(entry)
                }
            }
            .redacted(reason: isPlaceholder ? .placeholder : [])
            .allowsHitTesting(!isPlaceholder)
            WidgetStatusCard(model: model)
            DashboardFooter(entry: entry, region: model.settings.priceRegion, solarProviders: model.settings.plants.map(\.provider), showsUpdated: !isPlaceholder)
        }
    }

    /// One plant: its card. Several: a card per plant, swiped sideways (the first plant first).
    @ViewBuilder
    private func solarSection(_ entry: SunWattEntry) -> some View {
        if entry.data.hasSeveralPlants {
            PlantPager(entry: entry) { page in plantSection(page) }
        } else {
            plantSection(entry)
        }
    }

    @ViewBuilder
    private func plantSection(_ entry: SunWattEntry) -> some View {
        if entry.data.solarLinkExpired {
            StateCard(
                symbol: "link",
                title: atext("Kiosk link expired"),
                message: Text(verbatim: SunWattError.kioskLinkRejected.localizedDescription),
                actions: [
                    .init(label: "Replace link", prominent: true) { model.present(.settings) },
                    .init(label: "How do I get it?") { model.present(.kioskHelp) },
                ])
        } else {
            solarStatusSection(entry)
        }
    }

    @ViewBuilder
    private func solarStatusSection(_ entry: SunWattEntry) -> some View {
        switch entry.data.solarStatus {
        case .notConfigured:
            SolarSetupCard(model: model)
        case .error(let message) where entry.data.solar == nil:
            let plant = model.settings.plant(id: entry.data.plantID) ?? model.settings.primaryPlant
            StateCard(
                symbol: "exclamationmark.triangle.fill",
                title: atext("Solar data unavailable"),
                // Several plants: which one.
                message: Text(verbatim: entry.data.plantName.map { "\($0): \(message)" } ?? message),
                actions: [
                    .init(label: "Try again", prominent: true) { Task { await model.refresh() } },
                    .init(label: plant.provider.profile.setup == .kioskLink ? "Check kiosk link" : "Settings") { model.present(.settings) },
                ])
        default:
            if let solar = entry.solarForDisplay {
                SolarCard(solar: solar, now: entry.date, isStale: entry.isSolarStale, plantName: entry.data.plantName,
                          initialSelection: solarSelection)
            } else {
                SolarSetupCard(model: model)
            }
        }
    }

    @ViewBuilder
    private func priceSection(_ entry: SunWattEntry) -> some View {
        if let series = entry.data.prices {
            PriceCard(series: series, now: entry.date, isStale: entry.isPriceStale,
                      startsOnTomorrow: startsOnTomorrow, initialSelection: priceSelection)
        } else {
            let message: String = {
                if case .error(let message) = entry.data.priceStatus { return message }
                return astring("Data not available yet.")
            }()
            StateCard(
                symbol: "exclamationmark.triangle.fill",
                title: atext("Prices unavailable"),
                message: Text(verbatim: message),
                actions: [.init(label: "Try again", prominent: true) { Task { await model.refresh() } }])
        }
    }
}

// MARK: Header and footer

private struct DashboardHeader: View {
    let model: AppModel
    @Environment(\.appIdiom) private var idiom
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        HStack(spacing: Theme.Spacing.m) {
            if idiom != .desktop {
                AppIconView(size: 40, style: .badge)
                    .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: dateText)
                    .font(.system(idiom != .phone ? .title : .title3, design: .rounded).weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if idiom != .desktop {
                    Text(verbatim: "SunWatt")
                        .font(.system(.footnote, design: .rounded).weight(.semibold))
                        .foregroundStyle(.secondary)
                } else if let name = model.data?.solar?.stationName {
                    Text(verbatim: name)
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: Theme.Spacing.s)
            if model.isLoading, model.hasLoaded {
                ProgressView().controlSize(.small)
            }
            if idiom != .desktop {
                Button { model.present(.settings) } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.accentText)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(palette.card))
                        .overlay(Circle().strokeBorder(palette.cardStroke, lineWidth: 0.75))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(atext("Settings"))
            }
        }
    }

    private var dateText: String {
        let style = Date.FormatStyle(locale: Format.locale, timeZone: Constants.madrid).weekday(.wide).day().month(.wide)
        let text = model.now.formatted(style)
        return text.prefix(1).uppercased() + text.dropFirst()
    }
}

private struct DashboardFooter: View {
    let entry: SunWattEntry
    let region: PriceRegion
    let solarProviders: [SolarProvider]
    let showsUpdated: Bool

    var body: some View {
        VStack(spacing: 3) {
            if showsUpdated, let updated = entry.data.lastUpdated {
                atext("Updated \(Format.time(updated))")
                    .font(.system(.caption, design: .rounded).weight(.semibold))
            }
            sourceAttribution(region)
            atext("Solar: \(providerNames)")
        }
        .font(.system(.caption2, design: .rounded))
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.top, Theme.Spacing.xs)
    }

    /// "Huawei FusionSolar", or each provider once with several plants ("Huawei FusionSolar, SolarEdge").
    private var providerNames: String {
        var seen = Set<SolarProvider>()
        return solarProviders.filter { seen.insert($0).inserted }.map(\.displayName)
            .formatted(.list(type: .and, width: .narrow).locale(Format.locale))
    }
}

// MARK: Solar

/// Several plants: one page per plant, swiped sideways, with page dots below. The scale and layout of each card is the
/// single-plant one.
private struct PlantPager<Page: View>: View {
    let entry: SunWattEntry
    @ViewBuilder let page: (SunWattEntry) -> Page
    @State private var shown: String?

    var body: some View {
        let plants = entry.data.allPlants
        let current = shown ?? plants.first?.id
        VStack(spacing: Theme.Spacing.s) {
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 0) {
                    ForEach(plants, id: \.id) { plant in
                        page(SunWattEntry(date: entry.date, data: entry.data.showingPlant(id: plant.id)))
                            // Room for the card shadow inside the clipped scroll view.
                            .padding(.vertical, 8)
                            .containerRelativeFrame(.horizontal)
                            .id(plant.id)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollIndicators(.hidden)
            .scrollPosition(id: $shown)
            .padding(.vertical, -8)
            HStack(spacing: 6) {
                ForEach(plants, id: \.id) { plant in
                    Circle()
                        .fill(plant.id == current ? Color.primary.opacity(0.75) : Color.primary.opacity(0.2))
                        .frame(width: 6, height: 6)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: plants.first { $0.id == current }?.name ?? ""))
        }
    }
}

/// "Your kiosk link may stop working around …": shown from 30 days before the link's first anniversary in SunWatt
/// (kiosk links last one year), until the user replaces the link or closes the notice.
private struct KioskExpiryNotice: View {
    let model: AppModel
    let expiry: Date
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let date = expiry.formatted(Date.FormatStyle(locale: Format.locale).day().month(.wide).year())
        HStack(alignment: .top, spacing: Theme.Spacing.m) {
            Image(systemName: "link.badge.plus")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(AppPalette(scheme).warning)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                atext("Your kiosk link may stop working around \(date). Create a new one in FusionSolar and paste it here.")
                    .font(.system(.subheadline, design: .rounded))
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: Theme.Spacing.s) {
                    SettingsOpener(model: model) { atext("Renew link") }
                        .buttonStyle(PrimaryButtonStyle(compact: true))
                    Button { model.present(.kioskHelp) } label: { atext("How do I get it?") }
                        .buttonStyle(SoftButtonStyle(compact: true))
                }
            }
            Spacer(minLength: 0)
            Button { model.dismissKioskExpiryNotice() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.secondary)
                    .padding(6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(atext("Done"))
        }
        .appCard()
    }
}

/// What the house uses now, the grid exchange and today's share from the panels. Shown only with a meter at home.
private struct HomeEnergyRow: View {
    let home: HomeEnergy
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.s) {
            Rectangle().fill(AppPalette(scheme).hairline).frame(height: 0.5)
            HStack(alignment: .top, spacing: Theme.Spacing.l) {
                if let use = home.consumptionKW {
                    MiniStat(value: Format.kW(use), unit: "kW", caption: "Home now")
                }
                if let grid = home.gridKW {
                    MiniStat(
                        value: Format.kW(abs(grid)), unit: "kW",
                        caption: grid > Self.idleGrid ? "Buying from the grid" : grid < -Self.idleGrid ? "Selling to the grid" : "Grid")
                }
                Spacer(minLength: 0)
            }
            if let used = home.dailyConsumptionKWh {
                summary(used: used)
                    .font(.system(.footnote, design: .rounded).weight(.medium))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Below this (kW) the grid exchange counts as none.
    private static let idleGrid = 0.05

    private func summary(used: Double) -> Text {
        let kWh = Format.kWh(used)
        guard let share = home.selfSufficiency else { return atext("Used \(kWh) kWh today") }
        let percent = share.formatted(.percent.precision(.fractionLength(0)).locale(Format.locale))
        return atext("Used \(kWh) kWh today · \(percent) from your panels")
    }
}

private struct SolarCard: View {
    let solar: SolarSnapshot
    let now: Date
    let isStale: Bool
    /// Several plants: the plant's name, shown instead of the station name.
    var plantName: String?
    var initialSelection: Date?
    @Environment(\.redactionReasons) private var redaction
    @Environment(\.appIdiom) private var idiom

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: solar.isIdle ? Theme.Solar.nightSymbol : Theme.Solar.symbol,
                tint: solar.isIdle ? AnyShapeStyle(HierarchicalShapeStyle.secondary) : AnyShapeStyle(Theme.Solar.gradient),
                title: atext("Solar production"),
                subtitle: (plantName ?? solar.stationName).map { Text(verbatim: $0) }
            ) {
                timeMarker
            }
            HStack(alignment: .lastTextBaseline, spacing: Theme.Spacing.m) {
                VStack(alignment: .leading, spacing: 2) {
                    if solar.isIdle {
                        atext("No production")
                            .font(.system(size: 30, weight: .bold, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    } else {
                        FigureText(value: Format.kW(solar.currentPowerKW), unit: "kW", size: 50)
                    }
                    atext("\(Format.kWh(solar.dailyEnergyKWh)) kWh today")
                        .font(.system(.subheadline, design: .rounded).weight(.medium))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: Theme.Spacing.s)
                VStack(alignment: .trailing, spacing: Theme.Spacing.s) {
                    if let month = solar.monthEnergyKWh {
                        let monthEnergy = Format.energy(month)
                        MiniStat(value: monthEnergy.value, unit: monthEnergy.unit, caption: "This month", alignment: .trailing)
                    }
                    if let year = solar.yearEnergyKWh {
                        let yearEnergy = Format.energy(year)
                        MiniStat(value: yearEnergy.value, unit: yearEnergy.unit, caption: "This year", alignment: .trailing)
                    }
                }
                .multilineTextAlignment(.trailing)
                .fixedSize()
            }
            // Only plants with a meter at home report consumption; export-only plants show nothing here.
            if let home = solar.home {
                HomeEnergyRow(home: home)
            }
            if !solar.curve.isEmpty {
                Group {
                    if redaction.contains(.placeholder) {
                        RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(0.07))
                    } else {
                        InteractiveSolarChart(snapshot: solar, now: now, initialSelection: initialSelection)
                    }
                }
                .frame(height: idiom == .pad ? 250 : 190)
            }
        }
        .appCard()
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var timeMarker: some View {
        if isStale {
            StaleBadge(since: solar.dataAsOf ?? solar.fetchedAt)
        } else if solar.isLive == true {
            // Read from the inverter on the home network a moment ago.
            HStack(spacing: 5) {
                Circle().fill(Theme.Price.green).frame(width: 7, height: 7)
                atext("Live")
            }
            .font(.system(.caption, design: .rounded).weight(.semibold))
            .foregroundStyle(.secondary)
        } else if let time = solar.dataAsOf {
            atext("As of \(Format.time(time))")
                .font(.system(.caption, design: .rounded).weight(.medium))
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

private struct SolarSetupCard: View {
    let model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: Theme.Solar.symbol, tint: AnyShapeStyle(Theme.Solar.gradient),
                title: atext("Solar production"))
            VStack(alignment: .leading, spacing: 4) {
                atext("Add your solar plant").font(.system(.title3, design: .rounded).weight(.bold))
                atext("Connect Huawei FusionSolar, SolarEdge or PVOutput to see today's production here and in the widget.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: Theme.Spacing.s) {
                SettingsOpener(model: model) { atext("Set up solar") }
                    .buttonStyle(PrimaryButtonStyle(compact: true))
                if model.settings.solarProvider.profile.setup == .kioskLink {
                    Button { model.present(.kioskHelp) } label: { atext("How do I get it?") }
                        .buttonStyle(SoftButtonStyle(compact: true))
                }
            }
        }
        .appCard()
    }
}

/// Opens the app settings: the sheet on iPhone, the `Settings` window on the Mac.
struct SettingsOpener<Label: View>: View {
    let model: AppModel
    @ViewBuilder var label: Label

    var body: some View {
        #if os(macOS)
        SettingsLink { label }
        #else
        Button { model.present(.settings) } label: { label }
        #endif
    }
}

// MARK: Price

private enum DayChoice: Hashable { case today, tomorrow }

private struct PriceCard: View {
    let series: PriceSeries
    let now: Date
    let isStale: Bool
    var startsOnTomorrow = false
    var initialSelection: Date?

    @State private var day: DayChoice
    @Environment(\.redactionReasons) private var redaction
    @Environment(\.appIdiom) private var idiom

    init(series: PriceSeries, now: Date, isStale: Bool, startsOnTomorrow: Bool, initialSelection: Date?) {
        self.series = series
        self.now = now
        self.isStale = isStale
        self.startsOnTomorrow = startsOnTomorrow
        self.initialSelection = initialSelection
        _day = State(initialValue: startsOnTomorrow ? .tomorrow : .today)
    }

    private var region: PriceRegion { series.region }
    private var tomorrowStart: Date? {
        region.calendar.date(byAdding: .day, value: 1, to: region.calendar.startOfDay(for: now))
    }
    private var tomorrowHours: [PricePoint] { tomorrowStart.map { series.hours(onDayOf: $0) } ?? [] }
    private var hasTomorrow: Bool { !tomorrowHours.isEmpty }
    private var shownDay: DayChoice { hasTomorrow ? day : .today }
    private var reference: Date { shownDay == .today ? now : (tomorrowStart ?? now) }
    private var hours: [PricePoint] { series.hours(onDayOf: reference) }

    var body: some View {
        let context = series.dayContext(for: reference)
        let current = shownDay == .today ? series.currentHour(at: now) : nil
        let fraction = current.flatMap { context?.fraction(of: $0) }
        let level = current.flatMap { hour in context.map { $0.level(of: hour) } }

        VStack(alignment: .leading, spacing: Theme.Spacing.m) {
            CardHeader(
                symbol: Theme.Price.symbol,
                tint: AnyShapeStyle(fraction.map { Theme.Price.color(fraction: $0) } ?? Theme.Price.green),
                title: tariffName(region),
                subtitle: Text("\(region.flag) \(regionName(region))")
            ) {
                // Level of the current hour in the top right corner, like the data time of the solar card.
                HStack(spacing: Theme.Spacing.s) {
                    if isStale { StaleBadge(since: series.fetchedAt, timeZone: region.timeZone) }
                    if let level { LevelPill(level: level) }
                }
            }

            if hasTomorrow {
                AppSegmented(
                    options: [(DayChoice.today, "Today"), (DayChoice.tomorrow, "Tomorrow")],
                    selection: $day)
            }

            headline(context: context, current: current)

            if !hours.isEmpty {
                Group {
                    if redaction.contains(.placeholder) {
                        RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(0.07))
                    } else {
                        InteractivePriceChart(
                            series: series, points: hours, now: now,
                            initialSelection: shownDay == .today || startsOnTomorrow ? initialSelection : nil)
                            .id(shownDay)
                    }
                }
                .frame(height: idiom == .pad ? 230 : 170)
            }

            if let stats = context?.stats { statsRow(stats) }
            cheapest
        }
        .appCard()
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func headline(context: PriceDayContext?, current: PricePoint?) -> some View {
        if let current, context != nil {
            HStack(alignment: .lastTextBaseline, spacing: Theme.Spacing.m) {
                FigureText(value: Format.eurPerKWh(current.eurPerKWh), unit: "€/kWh", size: 50)
                Spacer(minLength: 0)
            }
        } else if let context {
            VStack(alignment: .leading, spacing: 2) {
                FigureText(value: Format.eurPerKWh(context.stats.average), unit: "€/kWh", size: 50)
                atext(shownDay == .tomorrow ? "Average tomorrow" : "Average today")
                    .font(.system(.subheadline, design: .rounded).weight(.medium))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func statsRow(_ stats: PriceStats) -> some View {
        HStack(alignment: .top, spacing: Theme.Spacing.l) {
            statItem("Min", stats.min, Theme.Price.green)
            statItem("Average", stats.average, Theme.Price.yellow)
            statItem("Max", stats.max, Theme.Price.red)
            Spacer(minLength: 0)
        }
    }

    private func statItem(_ title: LocalizedStringKey, _ value: Double, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            atext(title)
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            HStack(spacing: 5) {
                Circle().fill(color).frame(width: 8, height: 8)
                Text(verbatim: Format.eurPerKWh(value))
                    .font(Theme.Font.figure(17, weight: .bold))
            }
        }
    }

    @ViewBuilder
    private var cheapest: some View {
        let from = shownDay == .today ? now : (tomorrowStart ?? now)
        let dayEnd = region.calendar.dateInterval(of: .day, for: reference)?.end
        let upcoming = series.cheapestUpcoming(from: from, count: 3, until: dayEnd)
            .filter { region.calendar.isDate($0.start, inSameDayAs: reference) }
        if !upcoming.isEmpty {
            let contexts = PriceDayContexts(series: series, points: upcoming)
            VStack(alignment: .leading, spacing: Theme.Spacing.s) {
                atext(shownDay == .today ? "Cheapest upcoming hours" : "Cheapest hours tomorrow")
                    .font(.system(.caption, design: .rounded).weight(.semibold))
                    .foregroundStyle(.secondary)
                HStack(spacing: Theme.Spacing.s) {
                    ForEach(upcoming) { point in
                        HourChip(point: point, fraction: contexts.fraction(of: point), timeZone: region.timeZone)
                    }
                }
            }
        }
    }
}

private struct HourChip: View {
    let point: PricePoint
    let fraction: Double
    let timeZone: TimeZone
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "\(Format.hour(point.start, timeZone: timeZone))–\(Format.hour(point.end, timeZone: timeZone))")
                .font(.system(.footnote, design: .rounded).weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(verbatim: Format.eurPerKWh(point.eurPerKWh))
                .font(Theme.Font.figure(13, weight: .semibold))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.Price.color(fraction: fraction).opacity(0.18)))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Widget status

struct WidgetStatusCard: View {
    let model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = AppPalette(scheme)
        if let count = model.widgetCount, count > 0 {
            HStack(spacing: Theme.Spacing.m) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(palette.success)
                VStack(alignment: .leading, spacing: 2) {
                    atext("Widget added").font(.system(.headline, design: .rounded))
                    atext("Active widgets: \(count)")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: Theme.Spacing.s)
                Button { model.present(.widgetGuide) } label: { atext("Add another") }
                    .buttonStyle(SoftButtonStyle(compact: true))
            }
            .appCard()
        } else {
            VStack(alignment: .leading, spacing: Theme.Spacing.m) {
                HStack(alignment: .center, spacing: Theme.Spacing.m) {
                    MiniWidgetPreview(family: .small, width: 96)
                    VStack(alignment: .leading, spacing: 4) {
                        atext("Add the widget").font(.system(.title3, design: .rounded).weight(.bold))
                        atext("Prices and solar production on your screen, one glance away.")
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Button { model.present(.widgetGuide) } label: {
                    Label { atext("Show me how") } icon: { Image(systemName: "plus.square.on.square") }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .appCard()
        }
    }
}

// MARK: Screen

/// Dashboard in a scroll view with pull to refresh (iOS) and a width limit (Mac, iPad).
struct DashboardScreen: View {
    let model: AppModel
    @Environment(\.appIdiom) private var idiom

    var body: some View {
        ScrollView {
            DashboardContent(model: model)
                .padding(.horizontal, idiom != .phone ? Theme.Spacing.xl : Theme.Spacing.l)
                .padding(.vertical, idiom == .desktop ? Theme.Spacing.xl : Theme.Spacing.m)
                .frame(maxWidth: idiom == .desktop ? 1000 : (idiom == .pad ? 1100 : 720))
                .frame(maxWidth: .infinity)
        }
        .refreshable { await model.refresh() }
        .scrollContentBackground(.hidden)
        .background(AppBackground())
    }
}
#endif
