package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

/** Widest column of content: a phone fills the width, a tablet gets a centred column (the iPad sheet is 640 wide). */
val ContentMaxWidth = 640.dp

/** A page with a top bar (back arrow and [title]) on the app background; the content scrolls and keeps clear of the keyboard. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = appPalette
    AppBackground(modifier) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.app_back), tint = palette.textPrimary)
                }
                RoundedText(title, 20, Modifier.padding(start = Spacing.s), weight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.hairline))
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(Spacing.l + 2.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.l),
                ) { content() }
            }
        }
    }
}

/** A screen drawn above another one that stays composed under it (its typed text and scroll position survive). Takes every touch. */
@Composable
fun ScreenOverlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) { content() }
}
