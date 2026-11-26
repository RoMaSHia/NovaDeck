package io.novadeck.control.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlin.math.roundToInt

/**
 * Hardware LED-rack segmented meter with zero heap allocations.
 */
@Composable
fun SegmentedBarMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 20,
    height: Dp = 10.dp,
    activeColor: Color = BrutalistColors.TitaniumTrace,
    warnColor: Color = BrutalistColors.SignalAccent
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val gap = 2.dp.toPx()
        val totalGap = gap * (segments - 1)
        val segW = ((size.width - totalGap) / segments).coerceAtLeast(1f)
        val filled = (clamped * segments).roundToInt()

        for (i in 0 until segments) {
            val x = i * (segW + gap)
            val isLit = i < filled
            val col = when {
                !isLit -> BrutalistColors.SteelElevated
                i >= (segments * 0.8f) -> warnColor
                else -> activeColor
            }
            drawRect(
                color = col,
                topLeft = Offset(x, 0f),
                size = Size(segW, size.height)
            )
        }
    }
}

/**
 * Sharp industrial 240-degree dial gauge with hairline track & monospace readout.
 */
@Composable
fun BrutalistArcGauge(
    percent: Float,
    valueText: String,
    label: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    accentColor: Color = BrutalistColors.SignalAccent
) {
    val clamped = (percent / 100f).coerceIn(0f, 1f)
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val strokePx = 6.dp.toPx()
            val inset = strokePx * 0.6f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = BrutalistColors.HairlineBorder,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
            )

            drawArc(
                color = accentColor,
                startAngle = 150f,
                sweepAngle = 240f * clamped,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = valueText, style = DeckType.MetricMono)
            Text(text = label, style = DeckType.Caption)
        }
    }
}
