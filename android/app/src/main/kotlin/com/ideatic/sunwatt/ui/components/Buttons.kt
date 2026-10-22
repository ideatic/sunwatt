package com.ideatic.sunwatt.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import com.ideatic.sunwatt.ui.theme.RoundedFamily
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

private val PrimaryGradient = Brush.verticalGradient(listOf(Color(1.0f, 0.78f, 0.30f), Color(1.0f, 0.62f, 0.24f)))

/** Amber gradient button (`PrimaryButtonStyle`). [compact] is the small version next to other buttons; the full one fills the width. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val palette = appPalette
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    Row(
        modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .clip(shape)
            .background(PrimaryGradient)
            .border(BorderStroke(0.75.dp, Color(1f, 0.55f, 0.2f).copy(alpha = 0.6f)), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = if (compact) 36.dp else 50.dp)
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text, color = palette.onAccent, fontFamily = RoundedFamily, fontWeight = FontWeight.Bold,
            fontSize = if (compact) 15.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** Quiet button on an inset fill (`SoftButtonStyle`). */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val palette = appPalette
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    Row(
        modifier
            .clip(shape)
            .background(palette.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = if (compact) 36.dp else 46.dp)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = palette.accentText, fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 14.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
