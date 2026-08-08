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
package com.rtbishop.look4sat.core.domain.cw

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * CW decoder ported from fldigi (w1hkj/fldigi) cw.cxx — full receive
 * chain: NCO down-conversion, FFT lowpass filter, decimation, AGC,
 * hysteresis keying detection, element timing state machine, adaptive
 * speed tracking, SOM codebook matching and morse table lookup.
 *
 * Drop-in replacement for the reverse-engineered Morse Expert decoder:
 * same processBuffer(FloatArray) entry point, same StateFlow outputs.
 */
class CwFldigiDecoder(
    val sampleRate: Int = CwFldigiConstants.CW_SAMPLERATE,
    private val frequency: Double = 600.0,
    private val useSom: Boolean = true,
    private val initialWpm: Int = CwFldigiConstants.DEFAULT_SPEED
) {
    private companion object {
        const val CW_DOT = '.'
        const val CW_DASH = '-'
        const val CW_RESET = 0
        const val CW_KEYDOWN = 1
        const val CW_KEYUP = 2
        const val CW_QUERY = 3
        const val CW_SUCCESS = 0
        const val CW_ERROR = -1
    }

    private enum class CwRxState { IDLE, IN_TONE, AFTER_TONE }

    // Outputs (API-compatible with the old CwDecoder)
    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength

    private val _estimatedPitch = MutableStateFlow<Float?>(frequency.toFloat())
    val estimatedPitch: StateFlow<Float?> = _estimatedPitch

    private val _estimatedSpeed = MutableStateFlow<Float?>(null)
    val estimatedSpeed: StateFlow<Float?> = _estimatedSpeed

    // --- fldigi cw state ---
    private var phaseacc = 0.0
    private var FFTphase = 0.0
    private var FFTvalue = 0.0
    private var smpl_ctr = 0

    private var agc_peak = 1.0
    private var noise_floor = 1.0
    private var sig_avg = 0.0
    private var metric = 0.0
    private var siglevel = 0.0

    private var upper_threshold = CwFldigiConstants.CW_UPPER_THRESHOLD
    private var lower_threshold = CwFldigiConstants.CW_LOWER_THRESHOLD

    private val cw_FFT_filter: CwFftFilt
    private val bitfilter: CwMovAvg
    private val trackingfilter: CwMovAvg

    private var cw_receive_state = CwRxState.IDLE
    private var old_cw_receive_state = CwRxState.IDLE

    private var cw_rr_start_timestamp = 0
    private var cw_rr_end_timestamp = 0
    private var rx_rep_buf = StringBuilder()
    private var cw_ptr = 0
    private val cw_buffer = FloatArray(CwFldigiConstants.MAX_MORSE_ELEMENTS + 1)

    private var cw_speed = initialWpm
    private var cw_send_speed = initialWpm
    private var cw_receive_speed = initialWpm
    private var cw_bandwidth = CwFldigiConstants.DEFAULT_BANDWIDTH
    private var cwTrack = true
    private var two_dots = 0L
    private var last_element = 0
    private var space_sent = true
    private var cw_noise_spike_threshold = 0L
    private var cw_receive_dot_length = 0L
    private var cw_receive_dash_length = 0L

    private var synchscope = 50

    init {
        val bw = cw_bandwidth.toDouble() / sampleRate
        cw_FFT_filter = CwFftFilt(bw, CwFldigiConstants.CW_FFT_SIZE)
        val bfv = (symbollen() / (2 * CwFldigiConstants.DEC_RATIO)).coerceAtLeast(1)
        bitfilter = CwMovAvg(bfv)
        trackingfilter = CwMovAvg(CwFldigiConstants.TRACKING_FILTER_SIZE)

        two_dots = (2 * CwFldigiConstants.KWPM / cw_speed).toLong()
        cw_noise_spike_threshold = two_dots / 4
        cw_receive_dot_length = (CwFldigiConstants.KWPM / cw_receive_speed).toLong()
        cw_receive_dash_length = 3 * cw_receive_dot_length
    }

    private fun symbollen(): Int =
        (sampleRate * 1.2 / cw_speed).roundToInt()

    private fun usecDiff(earlier: Int, later: Int): Int =
        if (earlier >= later) 0 else later - earlier

    /** Main entry: feed PCM samples. */
    fun processBuffer(buffer: FloatArray) {
        for (sample in buffer) {
            rxSample(sample.toDouble())
        }
    }

    private fun rxSample(value: Double) {
        // NCO down-conversion (fldigi rx_FFTprocess)
        val z = CwComplex(
            value * kotlin.math.cos(FFTphase),
            value * kotlin.math.sin(FFTphase)
        )
        FFTphase += 2.0 * Math.PI * frequency / sampleRate
        if (FFTphase > 2.0 * Math.PI) FFTphase -= 2.0 * Math.PI

        val out = cw_FFT_filter.run(z) ?: return
        for (i in 0 until CwFldigiConstants.CW_FFT_SIZE / 2) {
            ++smpl_ctr
            if (smpl_ctr % CwFldigiConstants.DEC_RATIO != 0) continue
            FFTvalue = out[i].abs()
            FFTvalue = bitfilter.run(FFTvalue)
            decodeStream(FFTvalue)
        }
    }

    /**
     * AGC + hysteresis detection (fldigi cw::decode_stream).
     */
    private fun decodeStream(value: Double) {
        var v = value
        val attack: Int
        val decay: Int
        when (CwFldigiConstants.CWRX_ATTACK_DEFAULT) {
            0 -> attack = 400
            2 -> attack = 100
            else -> attack = 200
        }
        when (CwFldigiConstants.CWRX_DECAY_DEFAULT) {
            0 -> decay = 2000
            2 -> decay = 500
            else -> decay = 1000
        }

        sig_avg = decayAvg(sig_avg, v, decay)

        if (v < sig_avg) {
            noise_floor = if (v < noise_floor) decayAvg(noise_floor, v, attack)
            else decayAvg(noise_floor, v, decay)
        }
        if (v > sig_avg) {
            agc_peak = if (v > agc_peak) decayAvg(agc_peak, v, attack)
            else decayAvg(agc_peak, v, decay)
        }

        val normNoise = noise_floor / agc_peak
        val normSig = sig_avg / agc_peak
        siglevel = normSig

        if (agc_peak != 0.0) v /= agc_peak else v = 0.0

        metric = 0.8 * metric
        if ((noise_floor > 1e-4) && (noise_floor < sig_avg)) {
            val db = 20.0 * log10(sig_avg / noise_floor)
            metric += 0.2 * clamp(2.5 * db, 0.0, 100.0)
        }

        val diff = normSig - normNoise
        upper_threshold = normSig - 0.2 * diff
        lower_threshold = normNoise + 0.7 * diff

        // Squelch gate (fldigi: !progStatus.sqlonoff || metric > sldrSquelchValue).
        // Default sqlonoff=true, sldrSquelchValue=5.0. Without this gate, noise
        // spikes false-trigger KEYDOWN and lock the state machine in IN_TONE.
        if (sqlonoff && metric <= squelchValue) return

        // Power detection using hysteresis
        if ((v > upper_threshold) && (cw_receive_state != CwRxState.IN_TONE)) {
            handleEvent(CW_KEYDOWN)
        }
        if ((v < lower_threshold) && (cw_receive_state == CwRxState.IN_TONE)) {
            handleEvent(CW_KEYUP)
        }

        if (handleEvent(CW_QUERY) == CW_SUCCESS) {
            synchscope = 100
            // emit decoded char(s)
        } else if (--synchscope == 0) {
            synchscope = 25
        }
    }

    private var sqlonoff = true
    private var squelchValue = 5.0

    private fun decayAvg(avg: Double, value: Double, timeConst: Int): Double =
        avg + (value - avg) / timeConst

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else if (v > hi) hi else v

    private fun handleEvent(event: Int): Int {
        val sc = StringBuilder()
        var elementUsec: Int
        when (event) {
            CW_RESET -> {
                syncParameters()
                cw_receive_state = CwRxState.IDLE
                cw_ptr = 0
                smpl_ctr = 0
                rx_rep_buf.clear()
            }
            CW_KEYDOWN -> {
                if (cw_receive_state == CwRxState.IN_TONE) return CW_ERROR
                if (cw_receive_state == CwRxState.IDLE) {
                    smpl_ctr = 0
                    rx_rep_buf.clear()
                    cw_ptr = 0
                }
                cw_rr_start_timestamp = smpl_ctr
                old_cw_receive_state = cw_receive_state
                cw_receive_state = CwRxState.IN_TONE
                return CW_ERROR
            }
            CW_KEYUP -> {
                if (cw_receive_state != CwRxState.IN_TONE) return CW_ERROR
                cw_rr_end_timestamp = smpl_ctr
                elementUsec = usecDiff(cw_rr_start_timestamp, cw_rr_end_timestamp)
                syncParameters()
                if (cw_noise_spike_threshold > 0 && elementUsec < cw_noise_spike_threshold) {
                    cw_receive_state = CwRxState.IDLE
                    return CW_ERROR
                }

                // adaptive speed tracking on dot-dash / dash-dot pairs
                if (last_element > 0) {
                    if ((elementUsec > 2 * last_element) && (elementUsec < 4 * last_element)) {
                        updateTracking(last_element, elementUsec)
                    }
                    if ((last_element > 2 * elementUsec) && (last_element < 4 * elementUsec)) {
                        updateTracking(elementUsec, last_element)
                    }
                }
                last_element = elementUsec

                if (elementUsec <= two_dots) {
                    rx_rep_buf.append(CW_DOT)
                    cw_buffer[cw_ptr++] = last_element.toFloat()
                } else {
                    rx_rep_buf.append(CW_DASH)
                    cw_buffer[cw_ptr++] = last_element.toFloat()
                }

                if (rx_rep_buf.length > CwFldigiConstants.MAX_MORSE_ELEMENTS) {
                    cw_receive_state = CwRxState.IDLE
                    cw_ptr = 0
                    smpl_ctr = 0
                    return CW_ERROR
                } else {
                    if (cw_ptr < cw_buffer.size) cw_buffer[cw_ptr] = 0.0f
                }
                cw_receive_state = CwRxState.AFTER_TONE
                return CW_ERROR
            }
            CW_QUERY -> {
                if (cw_receive_state == CwRxState.IN_TONE) return CW_ERROR
                syncParameters()
                elementUsec = usecDiff(cw_rr_end_timestamp, smpl_ctr)
                if (elementUsec < (2 * cw_receive_dot_length)) return CW_ERROR

                if (elementUsec >= (2 * cw_receive_dot_length) &&
                    elementUsec <= (4 * cw_receive_dot_length) &&
                    cw_receive_state == CwRxState.AFTER_TONE
                ) {
                    val pattern = rx_rep_buf.toString()
                    val decoded = if (useSom) {
                        findWinner(cw_buffer, two_dots)
                    } else {
                        MorseTable.rxLookup(pattern)
                    }
                    if (decoded.isNotEmpty()) {
                        appendDecoded(decoded)
                    }
                    rx_rep_buf.clear()
                    cw_receive_state = CwRxState.IDLE
                    space_sent = false
                    cw_ptr = 0
                    return CW_SUCCESS
                }

                if ((elementUsec > (4 * cw_receive_dot_length)) && !space_sent) {
                    appendDecoded(" ")
                    space_sent = true
                    return CW_SUCCESS
                }
                return CW_ERROR
            }
        }
        return CW_ERROR
    }

    private fun appendDecoded(text: String) {
        val current = _decodedTextFlow.value
        _decodedTextFlow.value = if (current.length > 2000) {
            current.drop(current.length - 1500) + text
        } else {
            current + text
        }
        _signalStrength.value = metric.toFloat() / 100f
        _estimatedSpeed.value = (CwFldigiConstants.KWPM.toDouble() / (two_dots / 2.0)).toFloat()
    }

    private fun syncParameters() {
        if (cwTrack) {
            cw_receive_speed = (CwFldigiConstants.KWPM / (two_dots / 2)).toInt()
        } else {
            cw_receive_speed = cw_send_speed
            two_dots = 2 * (CwFldigiConstants.KWPM / cw_send_speed).toLong()
        }

        if (cw_receive_speed > 0) {
            cw_receive_dot_length = (CwFldigiConstants.KWPM / cw_receive_speed).toLong()
        } else {
            cw_receive_dot_length = (CwFldigiConstants.KWPM / 5).toLong()
        }
        cw_receive_dash_length = 3 * cw_receive_dot_length
        cw_noise_spike_threshold = cw_receive_dot_length / 2
    }

    private fun updateTracking(dur1: Int, dur2: Int) {
        val minDot = CwFldigiConstants.KWPM / 200
        val maxDash = 3 * CwFldigiConstants.KWPM / 5
        if ((dur1 > dur2) && (dur1 > 4 * dur2)) return
        if ((dur2 > dur1) && (dur2 > 4 * dur1)) return
        if (dur1 < minDot || dur2 < minDot) return
        if (dur2 > maxDash || dur2 > maxDash) return

        two_dots = trackingfilter.run(((dur1 + dur2) / 2).toDouble()).toLong()
        syncParameters()
    }

    /**
     * SOM codebook matching (fldigi cw::find_winner + normalize).
     */
    private fun findWinner(inbuf: FloatArray, twodots: Long): String {
        if (normalize(inbuf, twodots) == 0) return " "

        var winner = -1
        var bestDiff = Double.MAX_VALUE
        for ((idx, entry) in SomTable.table.withIndex()) {
            var difference = 0.0
            for (i in 0 until CwFldigiConstants.WGT_SIZE) {
                val diff = inbuf[i] - entry.weights[i]
                difference += diff * diff
                if (difference > bestDiff) break
            }
            if (difference < bestDiff) {
                winner = idx
                bestDiff = difference
            }
        }

        if (winner >= 0 && SomTable.table[winner].pattern.isNotEmpty()) {
            val sc = MorseTable.rxLookup(SomTable.table[winner].pattern)
            return if (sc.isNotEmpty()) sc else CwFldigiConstants.DEFAULT_NOISE_CHAR.toString()
        }
        return CwFldigiConstants.DEFAULT_NOISE_CHAR.toString()
    }

    private fun normalize(v: FloatArray, twodots: Long): Int {
        var max = v[0]
        var min = v[0]
        for (j in 1 until CwFldigiConstants.WGT_SIZE) {
            if (v[j] > max) max = v[j]
            else if (v[j] < min) min = v[j]
        }
        if (max == 0.0f) return 0

        val ratio = if (max > twodots) 1.0f else 0.33f
        val scale = ratio / max
        for (j in 0 until CwFldigiConstants.WGT_SIZE) v[j] *= scale
        return 1
    }

    /** Reset the decoder state (fldigi cw::rx_init + reset). */
    fun resetDecoder() {
        cw_receive_state = CwRxState.IDLE
        smpl_ctr = 0
        cw_ptr = 0
        rx_rep_buf.clear()
        last_element = 0
        space_sent = true
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
        _estimatedSpeed.value = null
        FFTphase = 0.0
        phaseacc = 0.0
    }
}
