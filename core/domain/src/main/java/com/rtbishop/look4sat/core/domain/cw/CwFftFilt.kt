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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT lowpass/bandpass filter with overlap-add, ported from fldigi
 * fftfilt.cxx (fftfilt class). Used by the CW decoder for the
 * matched receive filter.
 */
internal class CwFftFilt(f1: Double, f2: Double, len: Int) {
    private val flen: Int
    private val flen2: Int
    private val fft: CwGfft
    private val filter: Array<CwComplex>
    private val timedata: Array<CwComplex>
    private val freqdata: Array<CwComplex>
    private val output: Array<CwComplex>
    private val ovlbuf: Array<CwComplex>
    private var inptr = 0
    private var pass = 0

    init {
        flen = len
        flen2 = flen shr 1
        fft = CwGfft(flen)
        filter = Array(flen) { CwComplex() }
        timedata = Array(flen) { CwComplex() }
        freqdata = Array(flen) { CwComplex() }
        output = Array(flen) { CwComplex() }
        ovlbuf = Array(flen2) { CwComplex() }
        createFilter(f1, f2)
    }

    constructor(f: Double, len: Int) : this(0.0, f, len)

    fun flushSize(): Int = flen - inptr

    /**
     * f1 < f2 ==> bandpass; f1 > f2 ==> band reject; f1 == 0 ==> lowpass; f2 == 0 ==> highpass.
     */
    private fun createFilter(f1: Double, f2: Double) {
        for (i in 0 until flen) {
            filter[i] = CwComplex()
        }
        val bLowpass = f2 != 0.0
        val bHighpass = f1 != 0.0

        for (i in 0 until flen2) {
            var h = 0.0
            if (bLowpass) h += fsinc(f2, i, flen2)
            if (bHighpass) h -= fsinc(f1, i, flen2)
            if (bHighpass && f2 < f1 && i == flen2 / 2) h += 1.0
            filter[i] = CwComplex(h * blackman(i, flen2), 0.0)
        }

        // forward FFT of the impulse response
        fft.forward(filter)

        // normalize unity gain
        var scale = 0.0
        for (i in 0 until flen2) {
            val mag = kotlin.math.sqrt(filter[i].re * filter[i].re + filter[i].im * filter[i].im)
            if (mag > scale) scale = mag
        }
        if (scale != 0.0) {
            for (i in 0 until flen) {
                filter[i] = CwComplex(filter[i].re / scale, filter[i].im / scale)
            }
        }
        pass = 1
    }

    fun createLpf(f: Double) = createFilter(0.0, f)

    private fun fsinc(fc: Double, i: Int, len: Int): Double {
        val mid = len / 2
        return if (i == mid) 2.0 * fc
        else sin(2.0 * PI * fc * (i - mid)) / (PI * (i - mid))
    }

    private fun blackman(i: Int, len: Int): Double =
        0.42 - 0.50 * cos(2.0 * PI * i / len) + 0.08 * cos(4.0 * PI * i / len)

    /**
     * Feed one complex input sample. Returns output array + count when flen/2
     * samples are ready, otherwise null.
     */
    fun run(input: CwComplex): Array<CwComplex>? {
        timedata[inptr++] = input
        if (inptr < flen2) return null
        if (pass > 0) pass--

        // copy to freq domain and FFT
        for (i in 0 until flen) {
            freqdata[i] = timedata[i]
        }
        fft.forward(freqdata)

        // multiply by filter shape
        for (i in 0 until flen) {
            val f = filter[i]
            val t = freqdata[i]
            freqdata[i] = CwComplex(t.re * f.re - t.im * f.im, t.re * f.im + t.im * f.re)
        }

        // inverse FFT
        fft.inverse(freqdata)

        // overlap-add: first half is valid output, save second half
        for (i in 0 until flen2) {
            output[i] = CwComplex(ovlbuf[i].re + freqdata[i].re, ovlbuf[i].im + freqdata[i].im)
            ovlbuf[i] = freqdata[i + flen2]
        }

        inptr = 0
        if (pass > 0) return null
        return output
    }
}
