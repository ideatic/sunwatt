package com.ideatic.sunwatt.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Rounded card on the page background (`appCard`): white or near-black fill, hairline stroke, soft warm shadow. */
@Composable
fun SunWattCard(
    modifier: Modifier = Modifier,
    padding: Dp = Spacing.l + 2.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = appPalette
    val shape = RoundedCornerShape(Radius.card + 2.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = palette.cardShadow, spotColor = palette.cardShadow),
        shape = shape,
        color = palette.card,
        border = BorderStroke(0.75.dp, palette.cardStroke),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A big figure with a small unit next to it, e.g. "5,3 kW". [value] is already formatted (user locale, `Format`). */
@Composable
fun BigFigure(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    figureSizeSp: Int = 44,
    unitSizeSp: Int = 18,
    color: Color = Color.Unspecified,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(FigureStyles.figureSpan(figureSizeSp)) { append(value) }
            append(' ')
            withStyle(FigureStyles.unitSpan(unitSizeSp)) { append(unit) }
        },
        color = color,
        modifier = modifier,
    )
}
