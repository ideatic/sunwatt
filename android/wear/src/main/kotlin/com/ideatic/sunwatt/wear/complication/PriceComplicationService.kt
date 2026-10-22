package com.ideatic.sunwatt.wear.complication

import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.core.graphics.toColorInt
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.wear.R
import com.ideatic.sunwatt.wear.WearApplication
import com.ideatic.sunwatt.ui.theme.PriceColors
import androidx.compose.ui.graphics.toArgb

/** The price complication: the current price, with the day's range as a ranged value (Swift `SunWattWatchWidget`). */
class PriceComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val entry = WearComplications.loadEntry(application as WearApplication)
        return build(this, request.complicationType, PriceComplicationModel(entry))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, PriceComplicationModel(SampleData.entry(SampleData.Scenario.DAY)))

    companion object {
        private val ramp = ColorRamp(
            intArrayOf(PriceColors.green.toArgb(), PriceColors.yellow.toArgb(), PriceColors.red.toArgb()),
            true,
        )

        /** The data for [type]; null for a type the complication does not offer. */
        fun build(context: Context, type: ComplicationType, model: PriceComplicationModel): ComplicationData? {
            val tap = WearComplications.tapAction(context)
            val label = context.getString(R.string.widgetext_electricity_price)
            val value = model.valueText
            val gauge = model.gauge
            val icon = MonochromaticImage.Builder(
                Icon.createWithResource(context, if (value == null) R.drawable.ic_warning else R.drawable.ic_bolt),
            ).build()
            val unavailable = context.getString(R.string.widgets_prices_unavailable)
            val description = WearComplications.text(if (value == null) unavailable else "$label: $value ${PriceComplicationModel.UNIT}")
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(WearComplications.text(value ?: "–"), description)
                    .setTitle(value?.let { WearComplications.text(PriceComplicationModel.UNIT) })
                    .setMonochromaticImage(icon).setTapAction(tap).build()
                ComplicationType.RANGED_VALUE ->
                    if (value == null || gauge == null) {
                        ShortTextComplicationData.Builder(WearComplications.text("–"), description)
                            .setMonochromaticImage(icon).setTapAction(tap).build()
                    } else {
                        RangedValueComplicationData.Builder(gauge.value.toFloat(), gauge.lower.toFloat(), gauge.upper.toFloat(), description)
                            .setText(WearComplications.text(value))
                            .setMonochromaticImage(icon)
                            .setColorRamp(ramp)
                            .setTapAction(tap).build()
                    }
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                    WearComplications.text(if (value == null) unavailable else "$value ${PriceComplicationModel.UNIT}"), description,
                )
                    .setTitle(model.level?.let { WearComplications.text(context.getString(levelLabel(it))) })
                    .setMonochromaticImage(icon).setTapAction(tap).build()
                else -> null
            }
        }

        private fun levelLabel(level: PriceLevel): Int = when (level) {
            PriceLevel.LOW -> R.string.widgets_cheap
            PriceLevel.MEDIUM -> R.string.widgets_average
            PriceLevel.HIGH -> R.string.widgets_expensive
        }
    }
}
