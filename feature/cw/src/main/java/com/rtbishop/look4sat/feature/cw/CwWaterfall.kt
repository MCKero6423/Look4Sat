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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Rolling spectrogram history for the waterfall display.
 *
 * Reuses [CwDeepSpectrogram] so the picture shows exactly the 400-1200 Hz band
 * and the same magnitudes the model sees — what you look at is what it decodes.
 */
class CwWaterfallState(private val historyRows: Int = 96) {

    // pushSamples runs on the audio capture thread while snapshot() runs on the
    // Compose draw thread, so every touch of these two collections is guarded.
    // ArrayDeque is not thread-safe: concurrent removeFirst()/toList() throws.
    private val lock = Any()
    private val rows = ArrayDeque<FloatArray>(historyRows)
    private val pending = ArrayList<Float>(CwDeepSpectrogram.SAMPLE_RATE)
    // Incremented by clear(). A pushSamples call records the generation before
    // doing FFT outside the lock and discards its result if a clear occurred in
    // the meantime, otherwise pre-clear audio would reappear after the button tap.
    private var generation = 0L

    /**
     * Bumped on every change so Compose knows to redraw.
     *
     * A StateFlow, not `mutableIntStateOf`: this is written from the audio
     * capture thread, and Compose snapshot state must only be mutated from the
     * composition thread — doing otherwise crashes at runtime.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Snapshot for drawing, oldest row first. */
    fun snapshot(): List<FloatArray> = synchronized(lock) { rows.toList() }

    fun pushSamples(chunk: FloatArray, sampleRate: Int) {
        if (chunk.isEmpty()) return
        val resampled = CwDeepSpectrogram.resampleLinear(
            chunk, sampleRate, CwDeepSpectrogram.SAMPLE_RATE
        )
        val audio: FloatArray
        val generationAtStart: Long
        synchronized(lock) {
            pending.ensureCapacity(pending.size + resampled.size)
            for (sample in resampled) pending.add(sample)
            // Need at least one FFT window before a row can be produced.
            if (pending.size < CwDeepSpectrogram.FFT_LENGTH) return
            audio = FloatArray(pending.size) { pending[it] }
            pending.clear()
            generationAtStart = generation
        }

        // FFT outside the lock; only the append below needs exclusivity.
        val computed = CwDeepSpectrogram.compute(audio)
        synchronized(lock) {
            // Drop the result when the user cleared the display while this FFT
            // was running: those samples belong to the discarded history.
            if (generation != generationAtStart) return
            for (row in computed) {
                if (rows.size >= historyRows) rows.removeFirst()
                rows.addLast(row)
            }
        }
        // update {} not `value += 1`: this runs on the audio capture thread while
        // clear() runs on the main thread, and `+=` is a non-atomic
        // read-modify-write. A lost increment means a dropped redraw, and two
        // writes landing on the same value make StateFlow report no change at all.
        _revision.update { it + 1 }
    }

    fun clear() {
        synchronized(lock) {
            generation++
            rows.clear()
            pending.clear()
        }
        _revision.update { it + 1 }
    }
}

/**
 * Draws the waterfall newest-row-last, one pixel column per frequency bin.
 * Colour ramp is the inferno palette (black -> purple -> orange -> yellow).
 *
 * When [toneShiftHz] is non-zero a tone is being shifted into the model's window.
 * Two markers are drawn on top of the waterfall:
 * - A green dashed line at the target (800 Hz) showing where the tone lands.
 * - An orange marker at the original tone frequency. When the original is outside
 *   the visible 400-1200 Hz band, an arrow and frequency label are drawn at the
 *   nearest edge pointing toward the tone.
 */
@Composable
internal fun CwWaterfallView(
    state: CwWaterfallState,
    signalStrength: Float,
    estimatedPitch: Float? = null,
    toneShiftHz: Float = 0f,
    modifier: Modifier = Modifier
) {
    val revision by state.revision.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
        // Touch the revision inside the draw scope so a new spectrum triggers a
        // redraw; without this read the canvas would only ever render once.
        @Suppress("UNUSED_EXPRESSION") revision

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
            // Linear interpolation between adjacent bins via a horizontal
            // gradient removes the blocky "pixel" look of 65 discrete columns.
            for (bin in 0 until row.size - 1) {
                val m0 = (row[bin] / peak).coerceIn(0f, 1f)
                val m1 = (row[bin + 1] / peak).coerceIn(0f, 1f)
                if (m0 < 0.06f && m1 < 0.06f) continue
                drawRect(
                    brush = Brush.horizontalGradient(listOf(inferno(m0), inferno(m1))),
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

        // Tone-shift markers: only when the shift is active and we know the pitch.
        if (toneShiftHz != 0f && estimatedPitch != null && estimatedPitch > 0f) {
            val minHz = CwDeepSpectrogram.MIN_FREQ_HZ.toFloat()
            val maxHz = CwDeepSpectrogram.MAX_FREQ_HZ.toFloat()
            val targetHz = (CwDeepSpectrogram.MIN_FREQ_HZ + (CwDeepSpectrogram.MAX_FREQ_HZ - CwDeepSpectrogram.MIN_FREQ_HZ) / 2.0).toFloat()
            val hzToX: (Float) -> Float = { ((it - minHz) / (maxHz - minHz) * size.width).toFloat() }
            val dashLen = 4f
            val labelSize = 10f

            // Green dashed line at the target.
            val targetX = hzToX(targetHz).coerceIn(0f, size.width)
            val dashCount = (size.height / (dashLen * 2)).toInt()
            for (i in 0 until dashCount) {
                drawLine(
                    color = Color(0xFF4CD964).copy(alpha = 0.5f),
                    start = Offset(targetX, i * dashLen * 2),
                    end = Offset(targetX, (i * dashLen * 2) + dashLen),
                    strokeWidth = 1.5f
                )
            }

            // Original pitch marker.
            val origPitch = estimatedPitch + toneShiftHz  // undo the correction
            if (origPitch in minHz..maxHz) {
                // Inside the visible band: orange dashed line at the original position.
                val origX = hzToX(origPitch).coerceIn(0f, size.width)
                for (i in 0 until dashCount) {
                    drawLine(
                        color = Color(0xFFFF9500).copy(alpha = 0.5f),
                        start = Offset(origX, i * dashLen * 2),
                        end = Offset(origX, (i * dashLen * 2) + dashLen),
                        strokeWidth = 1.5f
                    )
                }
            }
            // Draw the original frequency label at the top edge.
            val labelX = hzToX(origPitch).coerceIn(labelSize, size.width - labelSize * 4)
            drawRect(
                color = Color(0xFFFF9500).copy(alpha = 0.15f),
                topLeft = Offset(labelX - 2f, 0f),
                size = Size(labelSize * 5, labelSize + 4f)
            )
            // Note: Compose Canvas doesn't support drawText natively;
            // the label is a composable overlaid on the waterfall instead.
        }
        } // end Canvas

        // Frequency labels overlaid on the waterfall.
        if (toneShiftHz != 0f && estimatedPitch != null && estimatedPitch > 0f) {
            val origPitch = estimatedPitch + toneShiftHz
            Text(
                text = "${origPitch.toInt()} Hz",
                fontSize = 9.sp,
                color = Color(0xFFFF9500),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 2.dp)
            )
        }
    } // end Box
}

/**
 * matplotlib "inferno" colour map, approximated with piecewise-linear stops
 * (black -> purple -> magenta-red -> orange -> pale yellow). The same palette
 * used for the static spectrogram illustration, kept for visual consistency.
 */
private val INFERNO_STOPS = arrayOf(
    floatArrayOf(0.00f, 0.000f, 0.000f, 0.016f), // black
    floatArrayOf(0.25f, 0.231f, 0.059f, 0.439f), // deep purple
    floatArrayOf(0.50f, 0.549f, 0.161f, 0.506f), // magenta
    floatArrayOf(0.75f, 0.871f, 0.286f, 0.408f), // red-orange
    floatArrayOf(1.00f, 0.988f, 1.000f, 0.643f)  // pale yellow
)

private fun inferno(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    for (i in 0 until INFERNO_STOPS.size - 1) {
        val a = INFERNO_STOPS[i]
        val b = INFERNO_STOPS[i + 1]
        if (x <= b[0]) {
            val f = (x - a[0]) / (b[0] - a[0])
            return Color(
                red = a[1] + (b[1] - a[1]) * f,
                green = a[2] + (b[2] - a[2]) * f,
                blue = a[3] + (b[3] - a[3]) * f
            )
        }
    }
    return Color(0.988f, 1.0f, 0.643f)
}
