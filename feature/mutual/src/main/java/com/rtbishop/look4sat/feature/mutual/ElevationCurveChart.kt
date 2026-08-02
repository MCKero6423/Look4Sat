/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.mutual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Draggable dual-station elevation curve chart.
 */
@Composable
fun ElevationCurveChart(
    samples: List<Pair<Long, Pair<Double, Double>>>,
    startTime: Long,
    endTime: Long,
    maxElev: Double,
    modifier: Modifier = Modifier
) {
    if (samples.isEmpty()) return

    val colorA = MaterialTheme.colorScheme.primary
    val colorB = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val colorAArgb = colorA.toArgb()
    val colorBArgb = colorB.toArgb()
    val textColorArgb = textColor.toArgb()
    val onSurfaceArgb = onSurfaceColor.toArgb()
    val gridColorArgb = gridColor.toArgb()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var dragProgress by remember { mutableFloatStateOf(0.5f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        dragProgress = (dragProgress + dragAmount.x / size.width.toFloat()).coerceIn(0f, 1f)
                    }
                }
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val padding = 40f
            val plotLeft = padding
            val plotRight = chartWidth - 10f
            val plotTop = 10f
            val plotBottom = chartHeight - padding
            val plotWidth = plotRight - plotLeft
            val plotHeight = plotBottom - plotTop

            if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

            val minTime = startTime.toFloat()
            val maxTime = endTime.toFloat()
            val timeRange = maxTime - minTime
            if (timeRange <= 0f) return@Canvas

            // Grid lines
            val gridSteps = 4
            for (i in 0..gridSteps) {
                val y = plotBottom - (plotHeight * i / gridSteps)
                drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                val elev = (maxElev * i / gridSteps).roundToInt()
                drawContext.canvas.nativeCanvas.drawText(
                    "$elev°", 2f, y + 4f,
                    android.graphics.Paint().apply {
                        color = textColorArgb
                        textSize = 24f
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                )
            }

            // Time axis
            val timeSteps = 4
            for (i in 0..timeSteps) {
                val x = plotLeft + (plotWidth * i / timeSteps)
                val t = startTime + ((endTime - startTime) * i / timeSteps)
                drawContext.canvas.nativeCanvas.drawText(
                    timeFormat.format(Date(t)), x - 20f, chartHeight - 2f,
                    android.graphics.Paint().apply {
                        color = textColorArgb
                        textSize = 22f
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                )
            }

            // Elevation curves
            val pathA = Path()
            val pathB = Path()
            var first = true

            for ((time, elev) in samples) {
                val x = plotLeft + ((time.toFloat() - minTime) / timeRange) * plotWidth
                val yA = plotBottom - ((elev.first.toFloat() / maxElev.toFloat()) * plotHeight).coerceIn(0f, plotHeight)
                val yB = plotBottom - ((elev.second.toFloat() / maxElev.toFloat()) * plotHeight).coerceIn(0f, plotHeight)

                if (first) {
                    pathA.moveTo(x, yA)
                    pathB.moveTo(x, yB)
                    first = false
                } else {
                    pathA.lineTo(x, yA)
                    pathB.lineTo(x, yB)
                }
            }

            drawPath(pathA, colorA, style = Stroke(width = 2.5f))
            drawPath(pathB, colorB, style = Stroke(width = 2.5f))

            // Drag indicator
            val dragX = plotLeft + dragProgress * plotWidth
            val dragTime = startTime + (dragProgress * (endTime - startTime)).toLong()

            drawLine(
                onSurfaceColor,
                Offset(dragX, plotTop), Offset(dragX, plotBottom),
                strokeWidth = 2f
            )

            val dragElev = getElevationAtTime(samples, dragTime)
            val dragElevA = (dragElev?.first?.let { (it * 10).roundToInt() / 10.0 } ?: 0.0)
            val dragElevB = (dragElev?.second?.let { (it * 10).roundToInt() / 10.0 } ?: 0.0)

            drawContext.canvas.nativeCanvas.drawText(
                timeFormat.format(Date(dragTime)),
                dragX - 24f, plotBottom + 16f,
                android.graphics.Paint().apply {
                    color = onSurfaceArgb
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.LEFT
                }
            )
            drawContext.canvas.nativeCanvas.drawText(
                "A:${dragElevA}°",
                dragX + 6f, plotTop + 16f,
                android.graphics.Paint().apply {
                    color = colorAArgb
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.LEFT
                }
            )
            drawContext.canvas.nativeCanvas.drawText(
                "B:${dragElevB}°",
                dragX + 6f, plotTop + 42f,
                android.graphics.Paint().apply {
                    color = colorBArgb
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.LEFT
                }
            )
        }

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("● 站点A", color = colorA, fontSize = 12.sp)
            Text("● 站点B", color = colorB, fontSize = 12.sp)
        }
    }
}

private fun getElevationAtTime(
    samples: List<Pair<Long, Pair<Double, Double>>>,
    time: Long
): Pair<Double, Double>? {
    if (samples.isEmpty()) return null
    if (samples.size == 1) return samples[0].second
    if (time <= samples[0].first) return samples[0].second
    if (time >= samples.last().first) return samples.last().second

    var lo = 0
    var hi = samples.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (samples[mid].first <= time) lo = mid
        else hi = mid
    }

    val t0 = samples[lo].first
    val t1 = samples[hi].first
    val frac = if (t1 > t0) (time - t0).toFloat() / (t1 - t0).toFloat() else 0f

    val e0 = samples[lo].second
    val e1 = samples[hi].second
    return Pair(
        e0.first + (e1.first - e0.first) * frac,
        e0.second + (e1.second - e0.second) * frac
    )
}