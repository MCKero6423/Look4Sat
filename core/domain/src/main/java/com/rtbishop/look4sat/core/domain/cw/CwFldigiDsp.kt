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

/**
 * Constants and primitives ported from fldigi (w1hkj/fldigi),
 * GPL v3, cw.h / cw.cxx / filters.cxx / fftfilt.cxx.
 *
 * Faithful port: identifiers and formulas mirror the C++ original so
 * behaviour matches the desktop decoder bit-for-bit.
 */
internal object CwFldigiConstants {
    const val CW_SAMPLERATE = 8000
    const val DEC_RATIO = 16
    const val CW_FFT_SIZE = 2048 // must be power of 2
    const val MAX_MORSE_ELEMENTS = 6
    const val WGT_SIZE = 7
    const val TRACKING_FILTER_SIZE = 16
    const val MAX_PIPE_SIZE = (22 * CW_SAMPLERATE * 12 / 800)
    const val CLRCOUNT = 16
    const val CW_MAX_SPEED = 50
    const val INITIAL_SEND_SPEED = 18
    const val INITIAL_RECEIVE_SPEED = 18

    /** # samples in a dot: KWPM / WPM. fldigi: KWPM = 12 * samplerate / 10 */
    const val KWPM = 12 * CW_SAMPLERATE / 10

    const val CW_SUCCESS = 0
    const val CW_ERROR = -1

    // Defaults mirroring fldigi progdefaults
    const val DEFAULT_SPEED = 18 // WPM
    const val DEFAULT_BANDWIDTH = 150 // Hz
    const val DEFAULT_RANGE = 10 // WPM tracking range
    const val LOWER_LIMIT = 5 // WPM
    const val UPPER_LIMIT = 50 // WPM
    const val DEFAULT_NOISE_CHAR = '*'
    const val CWRX_ATTACK_DEFAULT = 1 // medium
    const val CWRX_DECAY_DEFAULT = 1 // medium
    const val CW_LOWER_THRESHOLD = 0.4
    const val CW_UPPER_THRESHOLD = 0.6
}

/**
 * Moving average filter, ported from fldigi Cmovavg (filters.cxx).
 */
internal class CwMovAvg(private var len: Int) {
    private val buf = DoubleArray(MAX_MOVAVG)
    private var out = 0.0
    private var pint = 0
    private var empty = true

    fun run(a: Double): Double {
        if (empty) {
            empty = false
            out = 0.0
            for (i in 0 until len) {
                buf[i] = a
                out += a
            }
            pint = 0
            return a
        }
        out = out - buf[pint] + a
        buf[pint] = a
        if (++pint >= len) pint = 0
        return out / len
    }

    fun setLength(newLen: Int) {
        len = newLen.coerceAtMost(MAX_MOVAVG).coerceAtLeast(1)
        empty = true
    }

    fun reset() {
        empty = true
    }

    fun value(): Double = if (len > 0) out / len else 0.0

    private companion object {
        const val MAX_MOVAVG = 2048
    }
}

/**
 * Complex sample used by the FFT filter chain.
 */
internal data class CwComplex(var re: Double = 0.0, var im: Double = 0.0) {
    operator fun timesAssign(other: CwComplex) {
        val r = re * other.re - im * other.im
        val i = re * other.im + im * other.re
        re = r; im = i
    }

    fun abs(): Double = kotlin.math.sqrt(re * re + im * im)
}

/**
 * In-place radix-2 complex FFT, ported from fldigi gfft.h semantics
 * (ComplexFFT / InverseComplexFFT, forward = -1).
 */
internal class CwGfft(private val n: Int) {
    init {
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be power of 2, got $n" }
    }

    private val rev = IntArray(n)

    init {
        val log2n = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            var r = 0
            var x = i
            for (bit in 0 until log2n) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    /**
     * In-place complex FFT.
     * @param data array of CwComplex, size n
     * @param inverse if true, performs inverse transform (no 1/n scaling; caller divides)
     */
    fun transform(data: Array<CwComplex>, inverse: Boolean) {
        val sign = if (inverse) 1 else -1

        // bit reversal permutation
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val t = data[i]; data[i] = data[j]; data[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * Math.PI / len
            val wRe = Math.cos(ang)
            val wIm = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (j in 0 until len / 2) {
                    val u = data[i + j]
                    val v = data[i + j + len / 2]
                    val tRe = curRe * v.re - curIm * v.im
                    val tIm = curRe * v.im + curIm * v.re
                    data[i + j] = CwComplex(u.re + tRe, u.im + tIm)
                    data[i + j + len / 2] = CwComplex(u.re - tRe, u.im - tIm)
                    val nRe = curRe * wRe - curIm * wIm
                    val nIm = curRe * wIm + curIm * wRe
                    curRe = nRe; curIm = nIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun forward(data: Array<CwComplex>) = transform(data, false)

    fun inverse(data: Array<CwComplex>) = transform(data, true)
}
