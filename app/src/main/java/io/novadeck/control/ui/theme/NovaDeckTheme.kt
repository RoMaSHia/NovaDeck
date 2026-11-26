package io.novadeck.control.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NovaDeckDarkScheme = darkColorScheme(
    primary = BrutalistColors.SignalAccent,
    onPrimary = BrutalistColors.ObsidianBg,
    secondary = BrutalistColors.TitaniumTrace,
    onSecondary = BrutalistColors.ObsidianBg,
    background = BrutalistColors.ObsidianBg,
    onBackground = BrutalistColors.BoneInk,
    surface = BrutalistColors.CarbonSurface,
    onSurface = BrutalistColors.BoneInk,
    surfaceVariant = BrutalistColors.SteelElevated,
    onSurfaceVariant = BrutalistColors.TungstenMute,
    outline = BrutalistColors.HairlineBorder,
    error = BrutalistColors.StatusFault,
    onError = BrutalistColors.BoneInk
)

private val BrutalistShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp)
)

object DeckType {
    val DisplayMono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.5).sp,
        color = BrutalistColors.BoneInk
    )
    val MetricMono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp,
        color = BrutalistColors.BoneInk
    )
    val DataMono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = BrutalistColors.BoneInk
    )
    val IndexTag = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
        color = BrutalistColors.TungstenMute
    )
    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = BrutalistColors.BoneInk
    )
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = BrutalistColors.TungstenMute
    )
    val Caption = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp,
        color = BrutalistColors.GraphiteFaint
    )
}

private val BrutalistTypography = Typography(
    titleLarge = DeckType.DisplayMono,
    titleMedium = DeckType.MetricMono,
    titleSmall = DeckType.IndexTag,
    bodyLarge = DeckType.Body,
    bodyMedium = DeckType.DataMono,
    labelMedium = DeckType.Label,
    labelSmall = DeckType.Caption
)

@Composable
fun NovaDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NovaDeckDarkScheme,
        shapes = BrutalistShapes,
        typography = BrutalistTypography,
        content = content
    )
}
