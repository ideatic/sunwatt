package com.ideatic.sunwatt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Rounded system font (the Apple app uses SF Rounded). Falls back to the default font where it does not exist. */
val RoundedFamily: FontFamily = FontFamily(Font(DeviceFontFamilyName("sans-serif-rounded")))

/** Port of `Theme.Font`: big figures are rounded, bold and use fixed-width digits so they do not jump. */
object FigureStyles {
    fun figure(sizeSp: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
        fontFamily = RoundedFamily,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        fontFeatureSettings = "tnum",
    )

    fun unit(sizeSp: Int) = TextStyle(fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold, fontSize = sizeSp.sp)

    fun figureSpan(sizeSp: Int, weight: FontWeight = FontWeight.Bold) =
        SpanStyle(fontFamily = RoundedFamily, fontWeight = weight, fontSize = sizeSp.sp, fontFeatureSettings = "tnum")

    fun unitSpan(sizeSp: Int) = SpanStyle(fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold, fontSize = sizeSp.sp)

    /** `Theme.Font.caption` (caption2, medium). */
    val caption = TextStyle(fontFamily = RoundedFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp)

    /** `Theme.Font.label` (footnote, semibold). */
    val label = TextStyle(fontFamily = RoundedFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

/** Material typography in the rounded family, sizes near the iOS text styles. */
val SunWattTypography: Typography = Typography().let { base ->
    fun TextStyle.rounded(weight: FontWeight? = null) = copy(fontFamily = RoundedFamily, fontWeight = weight ?: fontWeight)
    base.copy(
        displayLarge = base.displayLarge.rounded(),
        displayMedium = base.displayMedium.rounded(),
        displaySmall = base.displaySmall.rounded(),
        headlineLarge = base.headlineLarge.rounded(FontWeight.Bold),
        headlineMedium = base.headlineMedium.rounded(FontWeight.Bold),
        headlineSmall = base.headlineSmall.rounded(FontWeight.Bold),
        titleLarge = base.titleLarge.rounded(FontWeight.Bold),
        titleMedium = base.titleMedium.rounded(FontWeight.SemiBold),
        titleSmall = base.titleSmall.rounded(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.rounded(),
        bodyMedium = base.bodyMedium.rounded(),
        bodySmall = base.bodySmall.rounded(),
        labelLarge = base.labelLarge.rounded(FontWeight.SemiBold),
        labelMedium = base.labelMedium.rounded(FontWeight.SemiBold),
        labelSmall = base.labelSmall.rounded(FontWeight.Medium),
    )
}
