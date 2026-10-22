package com.ideatic.sunwatt.wear.complication

import android.content.Context
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.wear.R
import com.ideatic.sunwatt.wear.WearApplication
import com.ideatic.sunwatt.ui.theme.SolarColors

/** The "Solar today" complication: today's kWh, with the power against today's peak as a ranged value. */
class SolarComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val entry = WearComplications.loadEntry(application as WearApplication)
        return build(this, request.complicationType, SolarComplicationModel(entry))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, SolarComplicationModel(SampleData.entry(SampleData.Scenario.DAY)))

    companion object {
        private val ramp = ColorRamp(intArrayOf(SolarColors.amber.toArgb(), SolarColors.orange.toArgb()), true)

        /** The data for [type]; null for a type the complication does not offer. */
        fun build(context: Context, type: ComplicationType, model: SolarComplicationModel): ComplicationData? {
            val tap = WearComplications.tapAction(context)
            val state = model.state
            val hasEnergy = state is WatchSolarState.Data || state == WatchSolarState.NoDataYet
            val iconRes = when {
                state == WatchSolarState.NotConfigured -> R.drawable.ic_settings
                state == WatchSolarState.Error -> R.drawable.ic_warning
                model.isNight -> R.drawable.ic_moon
                else -> R.drawable.ic_sun
            }
            val icon = MonochromaticImage.Builder(Icon.createWithResource(context, iconRes)).build()
            val message = context.getString(
                if (state == WatchSolarState.NotConfigured) R.string.widgets_solar_not_set_up else R.string.watch_solar_unavailable,
            )
            val energyLine = context.getString(R.string.widgets_kwh_today, model.energyText)
            val description = WearComplications.text(if (hasEnergy) energyLine else message)
            val gauge = model.gauge
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                    WearComplications.text(if (hasEnergy) model.energyText else "–"), description,
                )
                    .setTitle(if (hasEnergy) WearComplications.text(KWH) else null)
                    .setMonochromaticImage(icon).setTapAction(tap).build()
                ComplicationType.RANGED_VALUE ->
                    if (!hasEnergy || gauge == null) {
                        ShortTextComplicationData.Builder(WearComplications.text("–"), description)
                            .setMonochromaticImage(icon).setTapAction(tap).build()
                    } else {
                        RangedValueComplicationData.Builder(
                            (if (state == WatchSolarState.NoDataYet) 0.0 else gauge.value).toFloat(), 0f, gauge.upper.toFloat(), description,
                        )
                            .setText(WearComplications.text(model.energyText))
                            .setTitle(WearComplications.text(KWH))
                            .setMonochromaticImage(icon)
                            .setColorRamp(ramp)
                            .setTapAction(tap).build()
                    }
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                    WearComplications.text(if (hasEnergy) energyLine else message), description,
                )
                    .setTitle(if (state is WatchSolarState.Data) WearComplications.text(powerLine(model)) else null)
                    .setMonochromaticImage(icon).setTapAction(tap).build()
                else -> null
            }
        }

        /** "4,8 kW · 13:30". */
        private fun powerLine(model: SolarComplicationModel): String =
            listOfNotNull("${model.powerText} kW", model.timeText).joinToString(" · ")

        private const val KWH = "kWh"
    }
}
