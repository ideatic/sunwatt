package com.ideatic.sunwatt.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.text.textRes
import com.ideatic.sunwatt.ui.text.message
import com.ideatic.sunwatt.ui.theme.RoundedFamily
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

/** Result of "Test connection" (kiosk, API key and API account editors). */
sealed interface SolarTestState {
    data object Idle : SolarTestState
    data object Testing : SolarTestState
    data class Success(val snapshot: SolarSnapshot) : SolarTestState
    data class Failure(val message: String) : SolarTestState
}

/** Maps a connection test result to its state. The message is the localized text of the [SunWattError]; nothing else is shown. */
fun Result<SolarSnapshot>.toTestState(context: Context): SolarTestState = fold(
    onSuccess = { SolarTestState.Success(it) },
    onFailure = { SolarTestState.Failure(it.userMessage(context)) },
)

/** Only a [SunWattError] has a text that is safe to show (no secrets); anything else reads as "data not available". */
fun Throwable.userMessage(context: Context): String = (this as? SunWattError ?: SunWattError.Unavailable).message(context)

/** Opens [url] in the browser. Never put a secret in [url]. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing to do.
    }
}

/** Runs [commit] when the screen stops or the composable leaves, so typed values are never lost. */
@Composable
fun CommitOnLeave(commit: () -> Unit) {
    val latest by rememberUpdatedState(commit)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            latest()
        }
    }
}

/** Field on an inset fill (`AppTextField`). [isSecret] hides the text (API key, system code). Commits on Done and on focus loss. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    prompt: String,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onCommit: () -> Unit = {},
) {
    val palette = appPalette
    val focus = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val style = TextStyle(
        fontFamily = if (monospace) FontFamily.Monospace else RoundedFamily,
        fontWeight = FontWeight.Medium, fontSize = 15.sp, color = palette.textPrimary,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (hadFocus && !it.isFocused) onCommit()
                hadFocus = it.isFocused
            },
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(palette.accentText),
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            onCommit()
            focus.clearFocus()
        }),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(shape).background(palette.fill).border(BorderStroke(0.5.dp, palette.hairline), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    androidx.compose.material3.Text(prompt, style = style.copy(color = palette.textSecondary.copy(alpha = 0.55f)))
                }
                inner()
            }
        },
    )
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    RoundedText(text, 13, modifier.padding(horizontal = Spacing.xs), weight = FontWeight.SemiBold)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 0.5.dp).size(width = 0.dp, height = 0.5.dp).background(appPalette.hairline))
}

/** Row under a field: icon and one line of text in the same colour. */
@Composable
fun ValidationRow(tone: Tone, text: String, icon: @Composable () -> Unit) {
    val color = tone.color()
    Row(Modifier.padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        RoundedText(text, 13, weight = FontWeight.Medium, color = color)
    }
}

enum class Tone { NEUTRAL, SUCCESS, WARNING }

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> appPalette.textSecondary
    Tone.SUCCESS -> appPalette.success
    Tone.WARNING -> appPalette.warning
}

@Composable
fun SuccessIcon() = Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = appPalette.success)

@Composable
fun WarningIcon() = Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = appPalette.warning)

/** Quiet button with an optional leading symbol (`SoftButtonStyle`); dimmed and inert when not [enabled]. */
@Composable
fun SoftIconButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
) {
    val palette = appPalette
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    val color = if (tint == Color.Unspecified) palette.accentText else tint
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(palette.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = if (compact) 36.dp else 46.dp)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        androidx.compose.material3.Text(
            text, color = color, fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 14.sp else 16.sp, maxLines = 2,
        )
    }
}

/** Progress marker and text, e.g. "Connecting…". */
@Composable
fun BusyRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = appPalette.accentText)
        RoundedText(text, 13, color = appPalette.textSecondary)
    }
}

/** A tinted box for a result: green for success, amber for a failure. */
@Composable
fun ResultBox(tone: Tone, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(tone.color().copy(alpha = 0.12f)).padding(Spacing.m)) { content() }
}

/** Result of "Test connection", shared by the kiosk, API key and API account editors. */
@Composable
fun SolarTestResult(state: SolarTestState) {
    val palette = appPalette
    when (state) {
        SolarTestState.Idle -> Unit
        SolarTestState.Testing -> BusyRow(stringResource(R.string.app_connecting))
        is SolarTestState.Success -> ResultBox(Tone.SUCCESS) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SuccessIcon()
                    RoundedText(stringResource(R.string.app_connected), 15, weight = FontWeight.Bold)
                }
                val solar = state.snapshot
                solar.stationName?.let { ResultLine(stringResource(R.string.app_plant), it) }
                ResultLine(stringResource(R.string.app_power_now), "${Format.kW(solar.currentPowerKW)} kW")
                ResultLine(stringResource(R.string.app_energy_today), "${Format.kWh(solar.dailyEnergyKWh)} kWh")
                ResultLine(stringResource(R.string.app_data_as_of), solar.dataAsOf?.let { Format.time(it) } ?: "–")
            }
        }
        is SolarTestState.Failure -> FailureBox(state.message)
    }
}

@Composable
fun FailureBox(message: String) {
    ResultBox(Tone.WARNING) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            WarningIcon()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RoundedText(stringResource(R.string.app_connection_failed), 15, weight = FontWeight.Bold)
                RoundedText(message, 13, color = appPalette.textSecondary)
            }
        }
    }
}

@Composable
private fun ResultLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        RoundedText(title, 13, Modifier.weight(1f), color = appPalette.textSecondary)
        RoundedText(value, 13, weight = FontWeight.SemiBold, maxLines = 1, tabularDigits = true)
    }
}

/** The `TextRes` of an error as text, in a composable. */
@Composable
fun SunWattError.text(): String = textRes().text()

/** Card-like icon tile: a rounded square on the inset fill with an icon or drawing inside. */
@Composable
fun SymbolTile(size: Dp, modifier: Modifier = Modifier, tint: Brush? = null, content: @Composable () -> Unit) {
    val palette = appPalette
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(tint ?: SolidColor(palette.fill)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The amber number disc of a step. */
@Composable
fun StepNumber(number: Int, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(SolarColors.gradient),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            number.toString(), color = appPalette.onAccent, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

/** Click area without a ripple, for rows that show their own selected state. */
@Composable
fun Modifier.plainClickable(role: Role, onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = role, onClick = onClick)

/** A rounded square outline with a plus inside (Apple `plus.square.on.square`): the "add a widget" symbol. */
@Composable
fun PlusSquareSymbol(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.09f
        drawRoundRect(
            color, androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            androidx.compose.ui.geometry.CornerRadius(this.size.minDimension * 0.22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        val c = this.size.minDimension / 2
        val arm = this.size.minDimension * 0.22f
        drawLine(color, androidx.compose.ui.geometry.Offset(c - arm, c), androidx.compose.ui.geometry.Offset(c + arm, c), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(c, c - arm), androidx.compose.ui.geometry.Offset(c, c + arm), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** One line of rounded text that shrinks (down to 8 sp) until it fits the width instead of cutting off (`minimumScaleFactor`). */
@Composable
fun ShrinkText(text: String, sizeSp: Float, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Medium, color: Color = Color.Unspecified) {
    var size by remember(text, sizeSp) { mutableStateOf(sizeSp) }
    androidx.compose.material3.Text(
        text, modifier, color = color, fontFamily = RoundedFamily, fontWeight = weight, fontSize = size.sp, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && size > 8f) size -= 0.5f },
    )
}
