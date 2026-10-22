package com.ideatic.sunwatt.shortcuts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.intents.CheapestHoursAnswer
import com.ideatic.sunwatt.core.intents.IntentDataSource
import com.ideatic.sunwatt.core.intents.PriceAnswer
import com.ideatic.sunwatt.core.intents.SolarAnswer
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.charts.PriceBarChart
import com.ideatic.sunwatt.ui.charts.PriceChartModel
import com.ideatic.sunwatt.ui.charts.PriceWindow
import com.ideatic.sunwatt.ui.charts.SolarChartModel
import com.ideatic.sunwatt.ui.charts.SolarCurveChart
import com.ideatic.sunwatt.ui.charts.rememberChartStyle
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.SunWattTheme
import com.ideatic.sunwatt.ui.theme.appPalette
import com.ideatic.sunwatt.widget.refreshIntent
import com.ideatic.sunwatt.widget.widgetLabel
import com.ideatic.sunwatt.ui.text.TextRes
import java.time.Instant

/** What an app shortcut asks for. The value is the `answer` extra of the shortcut's intent. */
enum class AnswerKind(val extra: String, val need: IntentDataSource.Need) {
    PRICE("price", IntentDataSource.Need.PRICE),
    SOLAR("solar", IntentDataSource.Need.SOLAR),
    CHEAPEST_HOURS("cheapestHours", IntentDataSource.Need.PRICE);

    companion object {
        const val EXTRA = "answer"

        fun fromExtra(value: String?): AnswerKind = entries.firstOrNull { it.extra == value } ?: PRICE
    }
}

/**
 * The answer of an app shortcut (Siri intents on Apple): a small translucent card over the home screen with the
 * spoken sentence, the figure and a mini chart, and a button that opens the app. Cache first, network only when the
 * cache cannot answer ([IntentDataSource]).
 */
class AnswerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = AnswerKind.fromExtra(intent.getStringExtra(AnswerKind.EXTRA))
        val graph = (application as SunWattApplication).graph
        setContent {
            SunWattTheme {
                var state by remember { mutableStateOf<Loaded?>(null) }
                LaunchedEffect(kind) {
                    val data = graph.intentData.data(kind.need)
                    state = Loaded(data, graph.loader.now())
                }
                AnswerScreen(kind, state, onClose = ::finish, onOpen = {
                    startActivity(refreshIntent(this).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                })
            }
        }
    }
}

private class Loaded(val data: SunWattData, val now: Instant)

@Composable
private fun AnswerScreen(kind: AnswerKind, loaded: Loaded?, onClose: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val texts = remember { AnswerTexts(ContextAnswerStrings(context)) }
    // A tap outside the card closes it.
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .safeDrawingPadding().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SunWattCard(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Text(stringResource(kind.titleRes()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                if (loaded == null) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    AnswerBody(kind, loaded, texts)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.app_done)) }
                Button(onClick = onOpen) { Text(stringResource(R.string.app_open_sunwatt)) }
            }
        }
    }
}

private fun AnswerKind.titleRes(): Int = when (this) {
    AnswerKind.PRICE -> R.string.intents_electricity_price_now
    AnswerKind.SOLAR -> R.string.intents_solar_production_now
    AnswerKind.CHEAPEST_HOURS -> R.string.intents_cheapest_hours
}

@Composable
private fun AnswerBody(kind: AnswerKind, loaded: Loaded, texts: AnswerTexts) {
    val data = loaded.data
    val now = loaded.now
    val palette = appPalette
    when (kind) {
        AnswerKind.PRICE -> {
            val answer = PriceAnswer.make(data, now)
            val series = data.prices
            val value = answer.eurPerKWh
            if (value != null && series != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.eurPerKWh(value), fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                        color = answer.level?.let { PriceColors.color(it) } ?: Color.Unspecified,
                    )
                    Text(" €/kWh", color = palette.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    Box(Modifier.weight(1f))
                    answer.level?.let { Text(texts.levelName(it), fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp)) }
                }
                val model = remember(series, now) { PriceChartModel.from(series, now, PriceWindow.Day, ChartVariant.WIDGET_MEDIUM) }
                PriceBarChart(model, rememberChartStyle(ChartVariant.WIDGET_MEDIUM, palette.isDark), null, {}, Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp))
            }
            Sentence(texts.price(answer))
        }
        AnswerKind.SOLAR -> {
            val answer = SolarAnswer.make(data, now)
            val snapshot = data.solar
            val power = answer.currentKW
            if (power != null && snapshot != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.kW(power), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text(" kW", color = palette.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    Box(Modifier.weight(1f))
                    answer.todayKWh?.let {
                        Text(
                            stringResource(R.string.intents_today_kwh, Format.kWh(it)), fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                val model = remember(snapshot, now) { SolarChartModel.from(snapshot, now, ChartVariant.WIDGET_MEDIUM) }
                SolarCurveChart(model, rememberChartStyle(ChartVariant.WIDGET_MEDIUM, palette.isDark), null, {}, Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp))
            }
            Sentence(texts.solar(answer))
        }
        AnswerKind.CHEAPEST_HOURS -> {
            val answer = CheapestHoursAnswer.make(data, now)
            val zone = data.prices?.region?.timeZone ?: Constants.madrid
            answer.hours.forEach { hour ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = Format.hour(hour.point.start, zone)
                    Text(
                        if (hour.isToday) label else stringResource(R.string.intents_tomorrow, label),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Box(Modifier.weight(1f))
                    Text("${Format.eurPerKWh(hour.point.eurPerKWh)} €/kWh", color = PriceColors.color(PriceLevel.LOW), fontWeight = FontWeight.Medium)
                }
            }
            Sentence(texts.cheapestHours(answer, zone))
        }
    }
}

@Composable
private fun Sentence(text: String) {
    Text(text, color = appPalette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
}
