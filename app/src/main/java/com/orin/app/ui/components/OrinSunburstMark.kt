package com.orin.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Clean Terracotta Sunburst emblem for Orin (Claude-inspired editorial mark).
 */
@Composable
fun OrinSunburstMark(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.82f)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val outerR = minOf(cx, cy) * 0.92f
            val innerR = outerR * 0.28f
            val rays = 10
            val strokeW = (size.value * 0.06f).dp.toPx()

            for (i in 0 until rays) {
                val angle = (i * 2.0 * PI / rays) - (PI / 2.0)
                val len = if (i % 2 == 0) outerR else outerR * 0.74f
                drawLine(
                    color = color,
                    start = Offset(cx + (innerR * cos(angle)).toFloat(), cy + (innerR * sin(angle)).toFloat()),
                    end = Offset(cx + (len * cos(angle)).toFloat(), cy + (len * sin(angle)).toFloat()),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
