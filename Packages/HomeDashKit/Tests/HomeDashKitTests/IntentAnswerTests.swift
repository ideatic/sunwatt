import Foundation
import Testing
@testable import HomeDashKit

private func strings(_ code: String) throws -> IntentStrings {
    try #require(IntentStrings.language(code), "\(code).lproj")
}

private let now = SampleData.now
private let dayData = SampleData.entry(.day).data

@Suite struct PriceAnswerTests {
    @Test func spanishAndEnglishDialogs() throws {
        let es = PriceAnswer.make(from: dayData, now: now, strings: try strings("es"))
        #expect(es.dialog == "El precio de la electricidad ahora es 0,018 €/kWh. Es barato.")
        #expect(es.level == .low)
        #expect(abs((es.eurPerKWh ?? 0) - 0.01781) < 1e-9)
        let en = PriceAnswer.make(from: dayData, now: now, strings: try strings("en"))
        #expect(en.dialog == "The electricity price now is 0.018 €/kWh. It is cheap.")
    }

    @Test func expensiveHour() throws {
        let evening = SampleData.nightNow.addingTimeInterval(-2 * 3600) // 20:10, 198.48 €/MWh
        let answer = PriceAnswer.make(from: dayData, now: evening, strings: try strings("en"))
        #expect(answer.level == .high)
        #expect(answer.dialog.hasSuffix("It is expensive."))
    }

    @Test func noPricesGivesFriendlyDialog() throws {
        let data = SampleData.entry(.error).data
        let answer = PriceAnswer.make(from: data, now: now, strings: try strings("es"))
        #expect(answer.eurPerKWh == nil)
        #expect(answer.dialog.hasPrefix("Ahora mismo no puedo obtener el precio"))
    }

    @Test func seriesWithoutCurrentHourIsUnavailable() throws {
        let tomorrow = now.addingTimeInterval(3 * 86_400)
        let answer = PriceAnswer.make(from: dayData, now: tomorrow, strings: try strings("en"))
        #expect(answer.eurPerKWh == nil)
        #expect(answer.dialog.hasPrefix("I cannot get the electricity price"))
    }

    @Test func staleCachedPriceStillAnswers() throws {
        var data = dayData
        data.priceStatus = .stale(since: now.addingTimeInterval(-3600))
        #expect(PriceAnswer.make(from: data, now: now, strings: try strings("en")).eurPerKWh != nil)
    }
}

@Suite struct SolarAnswerTests {
    @Test func producing() throws {
        let es = SolarAnswer.make(from: dayData, now: now, strings: try strings("es"))
        #expect(es.outcome == .producing)
        #expect(es.dialog.hasPrefix("La producción solar ahora es de "))
        #expect(es.dialog.contains(" kW. Hoy: "))
        let en = SolarAnswer.make(from: dayData, now: now, strings: try strings("en"))
        #expect(en.dialog.hasPrefix("Solar production now is "))
        #expect(en.currentKW == dayData.solar?.currentPowerKW)
    }

    @Test func idleAtNight() throws {
        let data = SampleData.entry(.night).data
        let answer = SolarAnswer.make(from: data, now: SampleData.nightNow, strings: try strings("en"))
        #expect(answer.outcome == .idle)
        #expect(answer.dialog.hasPrefix("The plant is not producing now. Today: "))
    }

    @Test func notConfigured() throws {
        let data = SampleData.notConfiguredEntry().data
        let answer = SolarAnswer.make(from: data, now: now, strings: try strings("es"))
        #expect(answer.outcome == .notConfigured)
        #expect(answer.currentKW == nil)
        #expect(answer.dialog.contains("no está configurada"))
    }

    @Test func errorWithoutData() throws {
        let answer = SolarAnswer.make(from: SampleData.entry(.error).data, now: now, strings: try strings("en"))
        #expect(answer.outcome == .unavailable)
        #expect(answer.dialog.hasPrefix("I cannot get the solar production"))
    }

    @Test func oldReadingMentionsItsTime() throws {
        let data = SampleData.entry(.stale).data
        let answer = SolarAnswer.make(from: data, now: now, strings: try strings("en"))
        #expect(answer.dialog.contains("The last reading is from"))
    }

    @Test func freshReadingHasNoStaleNote() throws {
        let answer = SolarAnswer.make(from: dayData, now: now, strings: try strings("en"))
        #expect(!answer.dialog.contains("last reading"))
    }
}

@Suite struct CheapestHoursAnswerTests {
    @Test func listsNextThreeHours() throws {
        let es = CheapestHoursAnswer.make(from: dayData, now: now, strings: try strings("es"))
        #expect(es.dialog == "Las horas más baratas próximas son 13 h, 14 h y 15 h, desde 0,018 €/kWh.")
        let en = CheapestHoursAnswer.make(from: dayData, now: now, strings: try strings("en"))
        #expect(en.dialog == "The cheapest hours coming up are 13 h, 14 h, and 15 h, from 0.018 €/kWh.")
        #expect(en.hours.count == 3)
    }

    @Test func lastHourOfDayUsesSingularForm() throws {
        let late = SampleData.date(23, 30)
        let answer = CheapestHoursAnswer.make(from: dayData, now: late, strings: try strings("en"))
        #expect(answer.hours.count == 1)
        #expect(answer.dialog.hasPrefix("The cheapest hour coming up is 23 h"))
    }

    @Test func noPricesGivesFriendlyDialog() throws {
        let answer = CheapestHoursAnswer.make(from: SampleData.entry(.error).data, now: now, strings: try strings("es"))
        #expect(answer.hours.isEmpty)
        #expect(answer.dialog.hasPrefix("Ahora mismo no encuentro"))
    }
}

@Suite struct ControlContentTests {
    @Test func priceControl() throws {
        let es = ControlContent.price(from: dayData, now: now, strings: try strings("es"))
        #expect(es.title == "0,018 €/kWh")
        #expect(es.subtitle == "Barata")
        let none = ControlContent.price(from: SampleData.entry(.error).data, now: now, strings: try strings("en"))
        #expect(none.title == "No data")
        #expect(none.subtitle == "Tap to refresh")
    }

    @Test func solarControl() throws {
        let en = ControlContent.solar(from: dayData, now: now, strings: try strings("en"))
        #expect(en.title.hasSuffix(" kW"))
        #expect(en.subtitle.hasPrefix("Today "))
        let off = ControlContent.solar(from: SampleData.notConfiguredEntry().data, now: now, strings: try strings("en"))
        #expect(off.title == "Not set up")
        let night = ControlContent.solar(from: SampleData.entry(.night).data, now: SampleData.nightNow, strings: try strings("en"))
        #expect(night.title == "No production")
    }
}

@Suite struct IntentDataSourceTests {
    @Test func priceCacheNeedsCurrentHour() {
        #expect(IntentDataSource.cacheAnswers(dayData, need: .price, now: now))
        #expect(!IntentDataSource.cacheAnswers(dayData, need: .price, now: now.addingTimeInterval(3 * 86_400)))
        #expect(!IntentDataSource.cacheAnswers(SampleData.entry(.error).data, need: .price, now: now))
    }

    @Test func solarCacheNeedsRecentReadingOrNoSetup() {
        #expect(IntentDataSource.cacheAnswers(dayData, need: .solar, now: now))
        #expect(!IntentDataSource.cacheAnswers(SampleData.entry(.stale).data, need: .solar, now: now))
        #expect(IntentDataSource.cacheAnswers(SampleData.notConfiguredEntry().data, need: .solar, now: now))
        #expect(!IntentDataSource.cacheAnswers(SampleData.entry(.error).data, need: .solar, now: now))
    }

    @Test func emptyCacheLoadsFromTheNetwork() async {
        let (store, _) = storeTempStore()
        configured(store)
        let prices = storeSamplePrices()
        let loader = makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(prices)))
        let data = await IntentDataSource(loader: loader, deadline: 2).data(for: .price)
        #expect(data.priceStatus == .fresh)
        #expect(store.loadPrices() == prices)
    }
}

@Suite struct RefreshIntentTests {
    final class Order: @unchecked Sendable {
        private let lock = NSLock()
        private var events: [String] = []
        func add(_ e: String) { lock.lock(); events.append(e); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return events }
    }

    @Test func isConstructibleAndHidden() throws {
        _ = RefreshWidgetIntent()
        #expect(RefreshWidgetIntent.isDiscoverable == false)
        #expect(RefreshWidgetIntent.openAppWhenRun == false)
    }

    @Test func titleIsLocalizedInEverySupportedLanguage() throws {
        for code in L10n.languages {
            let s = try strings(code)
            let title = s.text("Refresh")
            #expect(!title.isEmpty)
            if code != "en" { #expect(title != "Refresh", "\(code)") }
        }
        #expect(try strings("es").text("Refresh") == "Actualizar")
    }

    @Test func loadsBeforeReloading() async {
        let order = Order()
        await RefreshWidgetIntent.refresh(load: { order.add("load") }, reload: { order.add("reload") })
        #expect(order.all == ["load", "reload"])
    }
}
