import Foundation

/// Localized text for Siri, Shortcuts and controls. Strings live in `Intents.xcstrings` (and `Widgets.xcstrings` for
/// level names). The bundle and locale are injectable, so tests can render any language without Siri.
public struct IntentStrings: Sendable {
    public let bundle: Bundle
    public let locale: Locale

    public init(bundle: Bundle, locale: Locale) {
        self.bundle = bundle
        self.locale = locale
    }

    /// The language of the process and the user's number format.
    public static var current: IntentStrings { IntentStrings(bundle: L10n.bundle, locale: Format.locale) }

    /// Strings in one of `L10n.languages`. Nil when the language bundle is missing.
    public static func language(_ code: String) -> IntentStrings? {
        // English is the source language: the keys are the strings, so an empty bundle returns them unchanged.
        if code == "en" { return Bundle(path: NSTemporaryDirectory()).map { IntentStrings(bundle: $0, locale: Locale(identifier: "en_US")) } }
        guard let path = L10n.bundle.path(forResource: code, ofType: "lproj"), let bundle = Bundle(path: path) else { return nil }
        return IntentStrings(bundle: bundle, locale: Locale(identifier: code))
    }

    /// Looks `key` up in `table` and fills the `%@` placeholders with `arguments`.
    public func text(_ key: String, table: String = "Intents", _ arguments: String...) -> String {
        let template = bundle.localizedString(forKey: key, value: key, table: table)
        guard !arguments.isEmpty else { return template }
        return String(format: template, locale: locale, arguments: arguments)
    }

    func levelName(_ level: PriceLevel) -> String {
        switch level {
        case .low: text("Cheap", table: "Widgets")
        case .medium: text("Average", table: "Widgets")
        case .high: text("Expensive", table: "Widgets")
        }
    }

    func eurPerKWh(_ value: Double) -> String {
        value.formatted(.number.locale(locale).precision(.fractionLength(3)))
    }

    func number(_ value: Double) -> String {
        let digits = abs(value) < 10 ? 1 : 0
        return value.formatted(.number.locale(locale).precision(.fractionLength(digits)))
    }

    func energy(_ value: Double) -> String {
        let digits = abs(value) < 1000 ? 1 : 0
        return value.formatted(.number.locale(locale).precision(.fractionLength(digits)))
    }

    func time(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, timeZone: Constants.madrid))
    }

    func list(_ items: [String]) -> String {
        items.formatted(.list(type: .and, width: .standard).locale(locale))
    }
}

/// "What is the electricity price now?"
public struct PriceAnswer: Sendable, Equatable {
    /// Current price in €/kWh; nil when there is none.
    public var eurPerKWh: Double?
    public var level: PriceLevel?
    public var dialog: String

    public static func make(from data: SunWattData, now: Date, strings: IntentStrings = .current) -> PriceAnswer {
        guard let series = data.prices, let point = series.currentHour(at: now) else {
            return PriceAnswer(eurPerKWh: nil, level: nil, dialog: strings.text(
                "I cannot get the electricity price right now. Check your connection and try again."))
        }
        let level = series.level(of: point)
        let price = strings.eurPerKWh(point.eurPerKWh)
        let dialog = switch level {
        case .low: strings.text("The electricity price now is %@ €/kWh. It is cheap.", price)
        case .medium: strings.text("The electricity price now is %@ €/kWh. It is an average price.", price)
        case .high: strings.text("The electricity price now is %@ €/kWh. It is expensive.", price)
        }
        return PriceAnswer(eurPerKWh: point.eurPerKWh, level: level, dialog: dialog)
    }
}

/// "How much solar am I producing?"
public struct SolarAnswer: Sendable, Equatable {
    public enum Outcome: Sendable, Equatable {
        case producing
        case idle
        case notConfigured
        case unavailable
    }

    public var outcome: Outcome
    public var currentKW: Double?
    public var todayKWh: Double?
    public var dialog: String

    public static func make(from data: SunWattData, now: Date, strings: IntentStrings = .current) -> SolarAnswer {
        if data.hasSeveralPlants { return several(from: data, now: now, strings: strings) }
        if case .notConfigured = data.solarStatus {
            return SolarAnswer(outcome: .notConfigured, currentKW: nil, todayKWh: nil, dialog: strings.text(
                "Solar production is not set up. Connect your solar system in the SunWatt settings."))
        }
        if data.solarLinkExpired {
            return SolarAnswer(outcome: .unavailable, currentKW: nil, todayKWh: nil, dialog: strings.text(
                "Your FusionSolar kiosk link has expired. Create a new one and paste it in the SunWatt settings."))
        }
        // A snapshot from yesterday has no power or daily energy for today (see `SunWattEntry.solarForDisplay`).
        guard let solar = SunWattEntry(date: now, data: data).solarForDisplay, let power = solar.currentPowerKW else {
            return SolarAnswer(outcome: .unavailable, currentKW: nil, todayKWh: nil, dialog: strings.text(
                "I cannot get the solar production right now. Check your connection and try again."))
        }
        var dialog: String
        let outcome: Outcome = solar.isIdle ? .idle : .producing
        if solar.isIdle {
            dialog = solar.dailyEnergyKWh.map { strings.text("The plant is not producing now. Today: %@ kWh.", strings.energy($0)) }
                ?? strings.text("The plant is not producing now.")
        } else {
            dialog = solar.dailyEnergyKWh.map {
                strings.text("Solar production now is %@ kW. Today: %@ kWh.", strings.number(power), strings.energy($0))
            } ?? strings.text("Solar production now is %@ kW.", strings.number(power))
        }
        let readingTime = solar.dataAsOf ?? solar.fetchedAt
        if now.timeIntervalSince(solar.fetchedAt) > Constants.staleAfter {
            dialog += " " + strings.text("The last reading is from %@.", strings.time(readingTime))
        }
        return SolarAnswer(outcome: outcome, currentKW: power, todayKWh: solar.dailyEnergyKWh, dialog: dialog)
    }

    /// Several plants: the total, then each plant ("… 5,2 kW in total. Today: 18,4 kWh. By plant: Home 3,1 kW,
    /// Roof 2,1 kW.").
    static func several(from data: SunWattData, now: Date, strings: IntentStrings) -> SolarAnswer {
        let entry = SunWattEntry(date: now, data: data)
        let plants = entry.plantsForDisplay
        if plants.allSatisfy({ $0.status == .notConfigured }) {
            return SolarAnswer(outcome: .notConfigured, currentKW: nil, todayKWh: nil, dialog: strings.text(
                "Solar production is not set up. Connect your solar system in the SunWatt settings."))
        }
        guard let total = entry.combinedSolarForDisplay, let power = total.currentPowerKW else {
            return SolarAnswer(outcome: .unavailable, currentKW: nil, todayKWh: nil, dialog: strings.text(
                "I cannot get the solar production right now. Check your connection and try again."))
        }
        let outcome: Outcome = total.isIdle ? .idle : .producing
        var dialog: String
        if total.isIdle {
            dialog = total.dailyEnergyKWh.map { strings.text("Your plants are not producing now. Today: %@ kWh.", strings.energy($0)) }
                ?? strings.text("Your plants are not producing now.")
        } else {
            dialog = total.dailyEnergyKWh.map {
                strings.text("Your plants are producing %@ kW in total. Today: %@ kWh.", strings.number(power), strings.energy($0))
            } ?? strings.text("Your plants are producing %@ kW in total.", strings.number(power))
        }
        let items = plants.map { plant -> String in
            guard let solar = plant.solar, let kW = solar.currentPowerKW else { return strings.text("%@: no data", plant.name) }
            return solar.isIdle ? strings.text("%@: not producing", plant.name) : strings.text("%@: %@ kW", plant.name, strings.number(kW))
        }
        dialog += " " + strings.text("By plant: %@.", strings.list(items))
        if now.timeIntervalSince(total.fetchedAt) > Constants.staleAfter {
            dialog += " " + strings.text("The last reading is from %@.", strings.time(total.dataAsOf ?? total.fetchedAt))
        }
        return SolarAnswer(outcome: outcome, currentKW: power, todayKWh: total.dailyEnergyKWh, dialog: dialog)
    }
}

/// "When is electricity cheapest?"
public struct CheapestHoursAnswer: Sendable, Equatable {
    /// Starts of the cheapest upcoming hours, in time order.
    public var hours: [PricePoint]
    public var dialog: String

    public static func make(from data: SunWattData, now: Date, count: Int = 3, strings: IntentStrings = .current) -> CheapestHoursAnswer {
        guard let series = data.prices else { return unavailable(strings) }
        let cheapest = series.cheapestUpcoming(from: now, count: count)
        guard let lowest = cheapest.map(\.eurPerKWh).min() else { return unavailable(strings) }
        // The ranking spans today and tomorrow: hours of tomorrow say so ("3 h tomorrow").
        let calendar = series.region.calendar
        let labels = cheapest.map { point in
            let hour = Format.hour(point.start, timeZone: series.region.timeZone)
            return calendar.isDate(point.start, inSameDayAs: now) ? hour : strings.text("%@ tomorrow", hour)
        }
        let price = strings.eurPerKWh(lowest)
        let dialog = cheapest.count == 1
            ? strings.text("The cheapest hour coming up is %@, at %@ €/kWh.", labels[0], price)
            : strings.text("The cheapest hours coming up are %@, from %@ €/kWh.", strings.list(labels), price)
        return CheapestHoursAnswer(hours: cheapest, dialog: dialog)
    }

    private static func unavailable(_ strings: IntentStrings) -> CheapestHoursAnswer {
        CheapestHoursAnswer(hours: [], dialog: strings.text(
            "I cannot find upcoming electricity prices right now. Check your connection and try again."))
    }
}

/// Text for the two controls (value title + subtitle).
public struct ControlContent: Sendable, Equatable {
    public var title: String
    public var subtitle: String

    public static func price(from data: SunWattData, now: Date, strings: IntentStrings = .current) -> ControlContent {
        guard let series = data.prices, let point = series.currentHour(at: now) else {
            return ControlContent(title: strings.text("No data"), subtitle: strings.text("Tap to refresh"))
        }
        return ControlContent(
            title: "\(strings.eurPerKWh(point.eurPerKWh)) €/kWh",
            subtitle: strings.levelName(series.level(of: point)))
    }

    public static func solar(from data: SunWattData, now: Date, strings: IntentStrings = .current) -> ControlContent {
        if case .notConfigured = data.solarStatus {
            return ControlContent(title: strings.text("Not set up"), subtitle: "SunWatt")
        }
        // A snapshot from yesterday has no power or daily energy for today (see `SunWattEntry.solarForDisplay`).
        // Several plants: their total.
        guard let solar = SunWattEntry(date: now, data: data).combinedSolarForDisplay, let power = solar.currentPowerKW else {
            return ControlContent(title: strings.text("No data"), subtitle: strings.text("Tap to refresh"))
        }
        let today = solar.dailyEnergyKWh.map { strings.text("Today %@ kWh", strings.energy($0)) }
        if solar.isIdle {
            return ControlContent(title: strings.text("No production"), subtitle: today ?? "")
        }
        return ControlContent(title: "\(strings.number(power)) kW", subtitle: today ?? "")
    }
}
