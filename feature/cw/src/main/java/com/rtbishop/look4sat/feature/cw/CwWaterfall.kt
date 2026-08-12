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
package com.rtbishop.look4sat.feature.cw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram

/**
 * Rolling spectrogram history for the waterfall display.
 *
 * Reuses [CwDeepSpectrogram] so the picture shows exactly the 400-1200 Hz band
 * and the same magnitudes the model sees — what you look at is what it decodes.
 */
class CwWaterfallState(private val historyRows: Int = 96) {

    private val rows = ArrayDeque<FloatArray>(historyRows)
    private val pending = ArrayList<Float>(CwDeepSpectrogram.SAMPLE_RATE)

    /** Bumped on every change so Compose knows to redraw. */
    val revision = mutableIntStateOf(0)

    /** Snapshot for drawing, oldest row first. */
    fun snapshot(): List<FloatArray> = rows.toList()

    fun pushSamples(chunk: FloatArray, sampleRate: Int) {
        if (chunk.isEmpty()) return
        val resampled = CwDeepSpectrogram.resampleLinear(
            chunk, sampleRate, CwDeepSpectrogram.SAMPLE_RATE
        )
        pending.ensureCapacity(pending.size + resampled.size)
        for (sample in resampled) pending.add(sample)

        // Need at least one FFT window before a row can be produced.
        if (pending.size < CwDeepSpectrogram.FFT_LENGTH) return

        val audio = FloatArray(pending.size) { pending[it] }
        pending.clear()

        for (row in CwDeepSpectrogram.compute(audio)) {
            if (rows.size >= historyRows) rows.removeFirst()
            rows.addLast(row)
        }
        revision.intValue++
    }

    fun clear() {
        rows.clear()
        pending.clear()
        revision.intValue++
    }
}

/**
 * Draws the waterfall newest-row-last, one pixel column per frequency bin.
 * Colour ramp goes dark blue -> cyan -> yellow with magnitude.
 */
@Composable
internal fun CwWaterfallView(
    state: CwWaterfallState,
    signalStrength: Float,
    modifier: Modifier = Modifier
) {
    // Reading the revision inside the draw scope is what triggers redraws as
    // new spectra arrive; without it the canvas would render only once.
    val revision = state.revision.intValue

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = Color(0xFF00060F), size = size)

        val rows = state.snapshot()
        if (rows.isEmpty()) return@Canvas

        // Scale to the loudest value on screen so quiet signals stay visible.
        var peak = 0f
        for (row in rows) for (v in row) if (v > peak) peak = v
        if (peak <= 0f) return@Canvas

        val rowHeight = size.height / rows.size
        val binWidth = size.width / CwDeepSpectrogram.FREQUENCY_BINS

        for ((index, row) in rows.withIndex()) {
            val y = index * rowHeight
            for (bin in row.indices) {
                val magnitude = (row[bin] / peak).coerceIn(0f, 1f)
                if (magnitude < 0.06f) continue
                drawRect(
                    color = rampColor(magnitude),
                    topLeft = Offset(bin * binWidth, y),
                    size = Size(binWidth + 1f, rowHeight + 1f)
                )
            }
        }

        if (signalStrength > 0f) {
            drawRect(
                color = Color(0xFF4CD964).copy(alpha = 0.8f),
                topLeft = Offset(0f, size.height - 3f),
                size = Size(size.width * signalStrength.coerceIn(0f, 1f), 3f)
            )
        }
    }
}

private fun rampColor(magnitude: Float): Color = when {
    magnitude < 0.5f -> {
        val t = magnitude / 0.5f
        Color(red = 0f, green = 0.35f * t, blue = 0.35f + 0.55f * t)
    }
    else -> {
        val t = (magnitude - 0.5f) / 0.5f
        Color(red = t, green = 0.35f + 0.6f * t, blue = 0.9f - 0.8f * t)
    }
}
