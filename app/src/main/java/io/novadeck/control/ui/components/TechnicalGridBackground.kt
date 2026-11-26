package io.novadeck.control.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.novadeck.control.ui.theme.BrutalistColors

/**
 * Adaptive dark backdrop:
 * - DECK_FULL: static zero-animation precision dot-matrix & axis ticks (zero frame allocation)
 * - ULTRA_LITE: flat matte carbon surface with zero Canvas draw pass
 */
@Composable
fun TechnicalGridBackground(
    isUltraLite: Boolean,
    modifier: Modifier = Modifier
) {
    if (isUltraLite) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BrutalistColors.ObsidianBg)
        )
    } else {
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .background(BrutalistColors.ObsidianBg)
        ) {
            val pitchPx = 28.dp.toPx()
            val dotColor = BrutalistColors.GridLine
            val crossColor = BrutalistColors.HairlineBorder

            var xIndex = 0
            var x = pitchPx * 0.5f
            while (x < size.width) {
                var yIndex = 0
                var y = pitchPx * 0.5f
                while (y < size.height) {
                    if (xIndex % 4 == 0 && yIndex % 4 == 0) {
                        // Subtle hardware alignment crosshair every 4th grid intersection
                        drawLine(
                            color = crossColor,
                            start = Offset(x - 3f, y),
                            end = Offset(x + 3f, y),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = crossColor,
                            start = Offset(x, y - 3f),
                            end = Offset(x, y + 3f),
                            strokeWidth = 1f
                        )
                    } else {
                        drawCircle(
                            color = dotColor,
                            radius = 1.1f,
                            center = Offset(x, y)
                        )
                    }
                    y += pitchPx
                    yIndex++
                }
                x += pitchPx
                xIndex++
            }
        }
    }
}
