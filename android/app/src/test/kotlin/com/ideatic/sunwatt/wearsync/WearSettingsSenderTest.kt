package com.ideatic.sunwatt.wearsync

import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.models.SunWattSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearSettingsSenderTest {
    private val spain = SunWattSettings()
    private val germany = SunWattSettings(priceRegion = PriceRegion.GERMANY)

    @Test
    fun settingsRoundTripThroughTheBytes() {
        val settings = SunWattSettings(kioskURL = "https://example.invalid/kiosk", priceRegion = PriceRegion.FRANCE)
        val back = SunWattJson.decodeFromString(SunWattSettings.serializer(), WearSettingsSender.encode(settings).toString(Charsets.UTF_8))
        assertEquals(settings, back)
    }

    @Test
    fun sendsEveryChangeWhileAWatchIsPresent() = runBlocking {
        val sent = WearSettingsSender.settingsToSend(flowOf(spain, germany), flowOf(true)).toList()
        // The first settings can be conflated away by combine; the last ones always arrive.
        assertEquals(germany, sent.last())
    }

    @Test
    fun sendsNothingWithoutAWatch() = runBlocking {
        val sent = WearSettingsSender.settingsToSend(flowOf(spain, germany), flowOf(false)).toList()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sendsAgainWhenAWatchAppears() = runBlocking {
        val present = MutableSharedFlow<Boolean>(replay = 1)
        present.emit(false)
        val collected = mutableListOf<SunWattSettings>()
        val job = launch(Dispatchers.Unconfined) {
            WearSettingsSender.settingsToSend(flowOf(spain), present).collect { collected += it }
        }
        assertTrue(collected.isEmpty())
        present.emit(true)
        assertEquals(listOf(spain), collected)
        job.cancel()
    }
}
