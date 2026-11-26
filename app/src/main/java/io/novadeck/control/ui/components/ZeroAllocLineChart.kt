package io.novadeck.control.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.novadeck.control.core.FloatRingBuffer
import io.novadeck.control.ui.theme.BrutalistColors

/**
 * Zero-allocation oscilloscope chart reusing pre-allocated Compose Path instances
 * directly from fixed-size FloatRingBuffer(s).
 */
@Composable
fun ZeroAllocLineChart(
    primaryBuffer: FloatRingBuffer,
    tickVersion: Long,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    fixedMax: Float? = 100f,
    primaryColor: Color = BrutalistColors.SignalAccent,
    secondaryBuffer: FloatRingBuffer? = null,
    secondaryColor: Color = BrutalistColors.TitaniumTrace,
    isUltraLite: Boolean = false
) {
    val primaryPath = remember { Path() }
    val secondaryPath = remember { Path() }
    val fillPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(BrutalistColors.ObsidianBg)
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
    ) {
        // Bind Compose draw invalidation strictly to primitive tick counter
        @Suppress("UNUSED_VARIABLE")
        val rev = tickVersion

        val w = size.width
        val h = size.height
        val pad = 4.dp.toPx()
        val plotH = (h - pad * 2f).coerceAtLeast(1f)

        // Subtle technical baseline gridlines (skip vertical in Ultra-Lite)
        val gridRows = if (isUltraLite) 2 else 3
        for (r in 1..gridRows) {
            val gy = pad + plotH * (r.toFloat() / (gridRows + 1))
            drawLine(
                color = BrutalistColors.GridLine,
                start = Offset(0f, gy),
                end = Offset(w, gy),
                strokeWidth = 1f
            )
        }
        if (!isUltraLite) {
            for (c in 1..4) {
                val gx = w * (c / 5f)
                drawLine(
                    color = BrutalistColors.GridLine,
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1f
                )
            }
        }

        val maxVal = fixedMax ?: run {
            var m = primaryBuffer.max().coerceAtLeast(1f)
            if (secondaryBuffer != null) {
                m = m.coerceAtLeast(secondaryBuffer.max())
            }
            m * 1.15f
        }

        val strokeWidthPx = 1.6.dp.toPx()

        fun buildTrace(buf: FloatRingBuffer, path: Path, buildFill: Boolean) {
            path.reset()
            if (buildFill) fillPath.reset()
            val n = buf.size
            if (n == 0) return

            val cap = (buf.capacity - 1).coerceAtLeast(1)
            val startIdx = buf.capacity - n
            for (i in 0 until n) {
                val x = ((startIdx + i).toFloat() / cap.toFloat()) * w
                val norm = (buf.getOldestToNewest(i) / maxVal).coerceIn(0f, 1f)
                val y = pad + plotH * (1f - norm)
                if (i == 0) {
                    path.moveTo(x, y)
                    if (buildFill) {
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    }
                } else {
                    path.lineTo(x, y)
                    if (buildFill) fillPath.lineTo(x, y)
                }
            }
            if (buildFill && n > 1) {
                val lastX = ((startIdx + n - 1).toFloat() / cap.toFloat()) * w
                fillPath.lineTo(lastX, h)
                fillPath.close()
            }
        }

        if (secondaryBuffer != null && secondaryBuffer.size > 0) {
            buildTrace(secondaryBuffer, secondaryPath, false)
            drawPath(
                path = secondaryPath,
                color = secondaryColor,
                style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )
        }

        if (primaryBuffer.size > 0) {
            val doFill = !isUltraLite && primaryBuffer.size > 1
            buildTrace(primaryBuffer, primaryPath, doFill)
            if (doFill) {
                drawPath(
                    path = fillPath,
                    color = primaryColor.copy(alpha = 0.12f)
                )
            }
            drawPath(
                path = primaryPath,
                color = primaryColor,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )
        }
    }
}
