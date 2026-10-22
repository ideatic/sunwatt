package com.ideatic.sunwatt.debug

import com.ideatic.sunwatt.core.data.PlantSolarData
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.primaryPlant
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.ui.Screen
import com.ideatic.sunwatt.ui.model.DashboardPreselection
import com.ideatic.sunwatt.ui.model.DemoSetup
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

/**
 * Fixed app states for demos, screenshots and emulator checks (port of the Swift `AppModel(demo:)` and the snapshot
 * scenarios). Debug builds only. The in-memory state uses the sample day of [SampleData] (2026-10-17, 13:40);
 * [seeded] is the same content moved to today and "now", for the store.
 */
object DemoScenarios {
    val scenarios = listOf(
        "day", "night", "stale", "error", "loading", "noSolar", "home", "plants", "kioskExpiring", "kioskExpired",
        "tomorrow", "selection", "widgetAdded",
    )

    const val DEMO_KIOSK_URL = "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=DEMO0000"

    /** Two further plants (demo names, a SolarEdge site and a FusionSolar API account). */
    val demoPlants = listOf(
        SolarPlant(id = "demo-barn", name = "Barn", provider = SolarProvider.SOLAR_EDGE, siteID = "1234567", apiKey = "DEMO0KEY0DEMO0KEY0DEMO0KEY000000"),
        SolarPlant(id = "demo-beach", name = "Beach house", provider = SolarProvider.FUSION_SOLAR_API, siteID = "demo_user", apiKey = "DEMO-system-code"),
    )

    val demoHome = HomeEnergy(consumptionKW = 0.9, gridKW = -3.9, dailyConsumptionKWh = 6.4, dailyImportKWh = 2.1, dailyExportKWh = 9.8)

    fun baseSettings(region: PriceRegion = PriceRegion.SPAIN) =
        SunWattSettings(kioskURL = DEMO_KIOSK_URL, priceRegion = region, onboardingCompleted = true)

    /** The in-memory state of [scenario]; an unknown name reads as "day". [screen] is dashboard, settings, onboarding, widgetGuide or kioskHelp. */
    fun setup(scenario: String, screen: String? = null, widgets: Int? = null, region: PriceRegion = PriceRegion.SPAIN): DemoSetup {
        var settings = baseSettings(region)
        val kind = when (scenario) {
            "night" -> SampleData.Scenario.NIGHT
            "stale" -> SampleData.Scenario.STALE
            "error" -> SampleData.Scenario.ERROR
            else -> SampleData.Scenario.DAY
        }
        val entry = SampleData.entry(kind, region)
        var data = entry.data
        var now = entry.date
        var widgetCount: Int? = widgets ?: if (scenario == "widgetAdded") 2 else 0
        var preselection = DashboardPreselection()
        var isLoading = false
        when (scenario) {
            "error" -> {
                widgetCount = widgets
                val message = SunWattError.Unavailable.message.orEmpty()
                data = data.copy(solarStatus = SourceStatus.Error(message, SunWattError.Unavailable.code), priceStatus = SourceStatus.Error(message, SunWattError.Unavailable.code))
            }
            "loading" -> isLoading = true
            "noSolar" -> {
                settings = settings.copy(kioskURL = "")
                data = data.copy(solar = null, solarStatus = SourceStatus.NotConfigured)
            }
            "home" -> data = data.copy(solar = data.solar?.copy(home = demoHome))
            "plants" -> {
                settings = settings.copy(primaryPlantName = "Home", additionalPlants = demoPlants)
                data = withDemoPlants(data, settings)
            }
            "kioskExpiring" -> {
                // Saved 350 days before the sample time: the notice shows (15 days left).
                settings = settings.copy(kioskLinkSavedAt = SampleData.now.minusSeconds(350 * 24 * 3600L))
            }
            "kioskExpired" -> data = data.copy(
                solar = null, solarLinkExpired = true,
                solarStatus = SourceStatus.Error(SunWattError.KioskLinkRejected.message.orEmpty(), SunWattError.KioskLinkRejected.code),
            )
            "tomorrow" -> {
                data = data.copy(prices = data.prices?.let { withTomorrow(it) })
                preselection = DashboardPreselection(startsOnTomorrow = true)
            }
            "selection" -> preselection = DashboardPreselection(
                solar = SampleData.now.minusSeconds(3600), price = SampleData.now.plusSeconds(6 * 3600),
            )
        }
        val ui = when (screen) {
            "settings" -> Screen.Settings
            "widgetGuide" -> Screen.WidgetGuide
            "kioskHelp" -> Screen.KioskHelp
            else -> null
        }
        return DemoSetup(
            settings = settings, data = data, now = now, widgetCount = widgetCount, isLoading = isLoading,
            showsOnboarding = screen == "onboarding", screen = ui, preselection = preselection,
        )
    }

    /** The data of each further plant: the same day scaled by 0.62, 0.35, ... so the pages differ. */
    fun withDemoPlants(base: SunWattData, settings: SunWattSettings): SunWattData {
        val solar = base.solar ?: return base
        val others = settings.additionalPlants.mapIndexed { index, plant ->
            val factor = maxOf(0.2, 0.62 - 0.27 * index)
            PlantSolarData(plant.id, plant.displayName(null), scaled(solar, factor), SourceStatus.Fresh)
        }
        return base.copy(plantName = settings.primaryPlant.displayName(null), otherPlants = others)
    }

    fun scaled(solar: SolarSnapshot, factor: Double): SolarSnapshot = solar.copy(
        currentPowerKW = solar.currentPowerKW?.times(factor),
        dailyEnergyKWh = solar.dailyEnergyKWh?.times(factor),
        monthEnergyKWh = solar.monthEnergyKWh?.times(factor),
        yearEnergyKWh = solar.yearEnergyKWh?.times(factor),
        cumulativeEnergyKWh = solar.cumulativeEnergyKWh?.times(factor),
        curve = solar.curve.map { CurvePoint(it.time, it.kW?.times(factor)) },
        home = null,
    )

    /** [series] plus a second day (the same prices moved by three hours). */
    fun withTomorrow(series: PriceSeries): PriceSeries {
        val tomorrow = series.points.mapIndexed { i, point ->
            PricePoint(point.start.plusSeconds(24 * 3600L), point.durationSeconds, series.points[(i + 21) % series.points.size].eurPerMWh)
        }
        return series.copy(points = series.points + tomorrow, granularity = PriceGranularity.HOURLY)
    }

    /** What `seed` writes to the store: the scenario moved to the real today and [now]. */
    class Seeded(val settings: SunWattSettings, val solar: Map<String, SolarSnapshot>, val prices: PriceSeries?)

    fun seeded(scenario: String, now: Instant, region: PriceRegion = PriceRegion.SPAIN): Seeded {
        var settings = baseSettings(region)
        val madrid = ZoneId.of("Europe/Madrid")
        val localNow = now.atZone(madrid)
        // Same time of day on the sample day, to cut the sample curve there; then everything moves to today.
        val sampleAtNow = SampleData.date(localNow.hour, localNow.minute)
        val dayShift = java.time.temporal.ChronoUnit.DAYS.between(SampleData.date(0, 0).atZone(madrid).toLocalDate(), localNow.toLocalDate())
        fun shift(time: Instant) = time.atZone(madrid).plusDays(dayShift).toInstant()
        fun move(solar: SolarSnapshot, fetchedAt: Instant) = solar.copy(
            curve = solar.curve.map { CurvePoint(shift(it.time), it.kW) }, fetchedAt = fetchedAt, dataAsOf = solar.dataAsOf?.let(::shift),
        )
        val night = scenario == "night"
        val stale = scenario == "stale"
        val until = if (night) SampleData.date(22, 5) else if (stale) sampleAtNow.minusSeconds(2 * 3600) else sampleAtNow
        var solar: SolarSnapshot? = move(SampleData.snapshot(until, until), if (stale) now.minusSeconds(2 * 3600) else now)
        var prices: PriceSeries? = SampleData.prices(region, now).let { p ->
            withTomorrow(p).let { two -> two.copy(points = two.points.map { PricePoint(shift(it.start), it.durationSeconds, it.eurPerMWh) }) }
        }
        when (scenario) {
            "noSolar" -> { settings = settings.copy(kioskURL = ""); solar = null }
            "home" -> solar = solar?.copy(home = demoHome)
            "plants" -> settings = settings.copy(primaryPlantName = "Home", additionalPlants = demoPlants)
            "kioskExpiring" -> settings = settings.copy(kioskLinkSavedAt = now.minus(Duration.ofDays(350)))
            "error" -> { solar = null; prices = null }
        }
        val solarByPlant = mutableMapOf<String, SolarSnapshot>()
        if (solar != null) {
            solarByPlant[SolarPlant.PRIMARY_ID] = solar
            if (scenario == "plants") settings.additionalPlants.forEachIndexed { i, plant ->
                solarByPlant[plant.id] = scaled(solar, maxOf(0.2, 0.62 - 0.27 * i))
            }
        }
        return Seeded(settings, solarByPlant, prices)
    }
}
