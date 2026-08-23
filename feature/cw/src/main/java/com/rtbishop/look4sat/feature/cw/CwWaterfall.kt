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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram
import com.rtbishop.look4sat.core.domain.cw.CwToneShifter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil
import kotlin.math.roundToInt

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
        // The whole band, not just the model's window: a tone outside the window leaves no
        // usable trace inside it, so the narrow view showed the operator nothing at all.
        val computed = CwDeepSpectrogram.compute(
            audio,
            CwDeepSpectrogram.DISPLAY_MIN_FREQ_HZ,
            CwDeepSpectrogram.DISPLAY_MAX_FREQ_HZ
        )
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
 * Spans the whole audio band, not just the model's window, so a tone the decoder cannot
 * read is still in the picture — inside the window such a tone leaves no usable trace at
 * all, and the operator could not even tell a signal was present. The window itself is
 * framed and the rest dimmed, so it stays clear which part is being decoded.
 *
 * When [toneShiftHz] is non-zero a tone is being moved into that window: green marks
 * where it is being delivered, orange marks [detectedToneHz] where the tone really is.
 */
@Composable
internal fun CwWaterfallView(
    state: CwWaterfallState,
    signalStrength: Float,
    detectedToneHz: Float? = null,
    toneShiftHz: Float = 0f,
    modifier: Modifier = Modifier
) {
    val revision by state.revision.collectAsState()

    // A Canvas announces nothing, so the whole spectrum was silent to a screen reader.
    // The tone and whether the decoder can reach it are the facts the picture conveys,
    // so they are what the description says.
    val hz = detectedToneHz?.roundToInt()
    val toneDesc = when {
        hz == null || hz <= 0 -> stringResource(R.string.cw_waterfall_idle)
        toneShiftHz != 0f -> stringResource(R.string.cw_waterfall_shifted, hz)
        CwToneShifter.isInsideWindow(hz.toFloat()) ->
            stringResource(R.string.cw_waterfall_inside, hz)
        else -> stringResource(R.string.cw_waterfall_outside, hz)
    }
    val description = stringResource(R.string.cw_waterfall_desc, toneDesc)

    Box(modifier = modifier.fillMaxSize().semantics { contentDescription = description }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Touch the revision inside the draw scope so a new spectrum triggers a
            // redraw; without this read the canvas would only ever render once.
            @Suppress("UNUSED_EXPRESSION") revision

            drawRect(color = Color(0xFF00060F), size = size)

            val rows = state.snapshot()
            var peak = 0f
            // Scale to the loudest value on screen so quiet signals stay visible.
            for (row in rows) for (v in row) if (v > peak) peak = v

            if (peak > 0f) {
                val rowHeight = size.height / rows.size
                // From the row itself, not the model's bin count: the display spans the
                // whole band and so carries more bins than the model reads.
                val binWidth = size.width / rows.first().size
                for ((index, row) in rows.withIndex()) {
                    val y = index * rowHeight
                    // Linear interpolation between adjacent bins via a horizontal
                    // gradient removes the blocky "pixel" look of discrete columns.
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
            }

            // After the spectrum so it cannot bury them, and outside the `peak > 0`
            // branch above because a shift stays applied through key-up gaps: the
            // markers must hold still through them, not blink out whenever the
            // picture goes momentarily quiet.
            drawDecoderWindow()
            drawToneShiftMarkers(detectedToneHz, toneShiftHz)

            if (signalStrength > 0f) {
                drawRect(
                    color = Color(0xFF4CD964).copy(alpha = 0.8f),
                    topLeft = Offset(0f, size.height - 3f),
                    size = Size(size.width * signalStrength.coerceIn(0f, 1f), 3f)
                )
            }
        }

        // The tone's own frequency, as text: Canvas has no drawText, so this rides on top
        // of it, on the side the tone lies beyond so it reads with the edge marker.
        // Suppressed for a non-positive pitch, where the readout is an artefact of the
        // loudest bin drifting below the shift and printing it would just show nonsense —
        // the marker itself still shows the low edge.
        if (toneShiftHz != 0f && detectedToneHz != null && detectedToneHz > 0f) {
            // Halfway is the tipping point, so the text sits nearer the marker it belongs
            // to wherever that is — including a pitch on the upper edge, whose line is
            // drawn hard against the right of the picture.
            val onHighSide = detectedToneHz > CwDeepSpectrogram.DISPLAY_MAX_FREQ_HZ / 2
            Text(
                text = "${detectedToneHz.roundToInt()} Hz",
                fontSize = 9.sp,
                color = TONE_ORIGIN_COLOUR,
                modifier = Modifier
                    .align(if (onHighSide) Alignment.TopEnd else Alignment.TopStart)
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Where the shifter actually puts the tone.
 *
 * Read from the shifter rather than recomputed as the window midpoint: the two agree
 * today only by coincidence, and retuning either one would leave this line marking a
 * frequency nothing is being delivered to — with no test or compiler error to say so.
 */
private val TONE_SHIFT_TARGET_HZ = CwToneShifter.TARGET_HZ.toFloat()

private val TONE_TARGET_COLOUR = Color(0xFF4CD964)

/**
 * Marker colour for the tone's own frequency.
 *
 * Cyan, not the orange it used to be: the inferno ramp runs black through purple and
 * orange to pale yellow, so an orange marker sitting on the very trace it points at was
 * the same hue as that trace and could not be told apart from it. Cyan appears nowhere in
 * the ramp.
 */
private val TONE_ORIGIN_COLOUR = Color(0xFF00E5FF)

/**
 * Shades the part of the band the model does not read, and marks the tone within it.
 *
 * The picture spans the whole band while the decoder reads only a window of it, so without
 * this the operator cannot tell which half of what they are looking at is being decoded.
 */
private fun DrawScope.drawDecoderWindow() {
    val loX = hzToX(CwDeepSpectrogram.MIN_FREQ_HZ.toFloat()) * size.width
    val hiX = hzToX(CwDeepSpectrogram.MAX_FREQ_HZ.toFloat()) * size.width
    // Lift the readable band rather than darken the rest. The background is already almost
    // black, so a dim wash over it moves only a couple of levels and reads as nothing; a
    // faint lift inside is visible against it while leaving the trace itself untouched.
    drawRect(
        color = Color(0xFF7FA8D8).copy(alpha = 0.16f),
        topLeft = Offset(loX, 0f),
        size = Size(hiX - loX, size.height)
    )
    val edge = Color(0xFF8FA6C4).copy(alpha = 0.8f)
    drawRect(color = edge, topLeft = Offset(loX, 0f), size = Size(1.5f, size.height))
    drawRect(color = edge, topLeft = Offset(hiX - 1.5f, 0f), size = Size(1.5f, size.height))
}

/**
 * Marks where the shifter is delivering the tone, and where the tone really is.
 *
 * Draws nothing when no shift is applied: the tone is then inside the window, plainly
 * visible in the spectrum on its own, and a marker would only add clutter.
 */
private fun DrawScope.drawToneShiftMarkers(detectedToneHz: Float?, toneShiftHz: Float) {
    if (toneShiftHz == 0f) return

    // The target line is drawn on the strength of the shift alone. A shift being applied
    // is the fact worth showing, and it must not depend on the tone readout, which can be
    // absent for a weak or slow fist even while a shift stays latched from an earlier scan.
    markerBracket(hzToX(TONE_SHIFT_TARGET_HZ), TONE_TARGET_COLOUR)

    // No usable tone estimate: the target line alone, rather than a guessed position.
    if (detectedToneHz == null || detectedToneHz.isNaN() || detectedToneHz <= 0f) return

    // The tone is genuinely in the picture now, so mark it where it is.
    markerBracket(hzToX(detectedToneHz), TONE_ORIGIN_COLOUR)
}

/** Height of the strip along the top reserved for frequency markers. */
private const val MARKER_GUTTER_PX = 7f

/**
 * A marker pip in the gutter above the spectrum, at [fraction] across.
 *
 * Kept out of the spectrum rather than drawn across it. A line laid over a CW trace cannot
 * be told apart from the keying gaps in that trace, and the marker that matters most sits
 * exactly on the tone it points at - so it was invisible in the one place it was needed.
 * A pip in its own strip is clear of the signal and still reads against the axis.
 */
private fun DrawScope.markerBracket(fraction: Float, colour: Color) {
    val x = (fraction * size.width).coerceIn(1f, size.width - 3f)
    drawRect(
        color = colour,
        topLeft = Offset(x - 1f, 0f),
        size = Size(3f, MARKER_GUTTER_PX)
    )
    // A short stub reaching into the spectrum, so the pip reads as pointing at a
    // frequency rather than floating above one, without masking the trace below.
    drawRect(
        color = colour.copy(alpha = 0.55f),
        topLeft = Offset(x, MARKER_GUTTER_PX),
        size = Size(1f, MARKER_GUTTER_PX * 0.7f)
    )
}

/** Fraction across the display for [hz], 0..1 spanning the visible band. */
private fun hzToX(hz: Float): Float {
    val lo = CwDeepSpectrogram.DISPLAY_MIN_FREQ_HZ.toFloat()
    val hi = CwDeepSpectrogram.DISPLAY_MAX_FREQ_HZ.toFloat()
    return (hz - lo) / (hi - lo)
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
