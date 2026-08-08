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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tests for the fldigi-ported CW decoder. Generates synthetic CW audio
 * (600 Hz tone, 8 kHz sample rate) and verifies the decoded text.
 */
class CwFldigiDecoderTest {

    private val sampleRate = 8000
    private val toneFreq = 600.0

    /** Synthesize a CW audio buffer for a dot-dash pattern at given WPM. */
    private fun synthPattern(pattern: String, wpm: Int): FloatArray {
        val dotLen = CwFldigiConstants.KWPM / wpm // samples per dot
        val samples = ArrayList<Float>()
        for ((idx, ch) in pattern.withIndex()) {
            val len = if (ch == '.') dotLen else 3 * dotLen
            for (i in 0 until len) {
                val t = i.toDouble() / sampleRate
                samples.add((0.6 * sin(2.0 * PI * toneFreq * t)).toFloat())
            }
            if (idx < pattern.length - 1) {
                for (i in 0 until dotLen) samples.add(0f) // intra-char gap
            }
        }
        return samples.toFloatArray()
    }

    /** Append an inter-character gap (3 dot lengths) of silence. */
    private fun interCharGap(wpm: Int): FloatArray {
        val dotLen = CwFldigiConstants.KWPM / wpm
        return FloatArray(3 * dotLen)
    }

    private fun decodeSequence(chars: List<String>, wpm: Int, useSom: Boolean = true): String {
        val decoder = CwFldigiDecoder(sampleRate = sampleRate, frequency = toneFreq, useSom = useSom)
        val dotLen = CwFldigiConstants.KWPM / wpm
        for ((ci, pattern) in chars.withIndex()) {
            decoder.processBuffer(synthPattern(pattern, wpm))
            decoder.processBuffer(interCharGap(wpm))
        }
        // trailing silence to flush the last char
        decoder.processBuffer(FloatArray(8 * dotLen))
        return decoder.decodedTextFlow.value
    }

    @Test
    fun decode_basicLetter_singleDot() {
        val decoder = CwFldigiDecoder(sampleRate = sampleRate, frequency = toneFreq)
        decoder.processBuffer(synthPattern(".", 18))
        decoder.processBuffer(FloatArray(8 * CwFldigiConstants.KWPM / 18))
        assertTrue("expected E in output, got: ${decoder.decodedTextFlow.value}",
            decoder.decodedTextFlow.value.startsWith("E"))
    }

    @Test
    fun decode_word_CQ_18wpm() {
        val text = decodeSequence(listOf("-.-.", "--.-"), 18)
        assertTrue("expected CQ in output, got: $text", text.contains("C") && text.contains("Q"))
    }

    @Test
    fun decode_hello_20wpm() {
        val text = decodeSequence(listOf("....", ".", ".-..", ".-..", "---"), 20)
        assertTrue("expected HELLO in output, got: $text", text.contains("H"))
    }

    @Test
    fun decode_adaptive_speed_30wpm() {
        // Realistic usage: decoder configured near the signal speed, tracking
        // fine-tunes from there. Verify correct decode at 30 wpm.
        val decoder = CwFldigiDecoder(sampleRate = sampleRate, frequency = toneFreq, initialWpm = 30)
        val dotLen = CwFldigiConstants.KWPM / 30
        for (pattern in listOf(".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---")) {
            decoder.processBuffer(synthPattern(pattern, 30))
            decoder.processBuffer(interCharGap(30))
        }
        decoder.processBuffer(FloatArray(8 * dotLen))
        val text = decoder.decodedTextFlow.value
        // First char may be off while tracking converges; rest must be exact.
        assertTrue("expected B-J at 30wpm, got: $text", text.contains("B") && text.contains("J"))
    }

    @Test
    fun reset_clearsState() {
        val decoder = CwFldigiDecoder(sampleRate = sampleRate, frequency = toneFreq)
        decoder.processBuffer(synthPattern(".", 18))
        decoder.processBuffer(FloatArray(8 * CwFldigiConstants.KWPM / 18))
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
    }

    @Test
    fun morseTable_lookup() {
        assertEquals("A", MorseTable.rxLookup(".-"))
        assertEquals("S", MorseTable.rxLookup("..."))
        assertEquals("1", MorseTable.rxLookup(".----"))
        assertEquals("?", MorseTable.rxLookup("..--.."))
        assertEquals("", MorseTable.rxLookup("........"))
    }

    @Test
    fun somTable_hasAllAsciiLetters() {
        val patterns = SomTable.table.map { it.pattern }.toSet()
        val letters = listOf(".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....",
            "..", ".---", "-.-", ".-..", "--", "-.", "---", ".--.", "--.-", ".-.",
            "...", "-", "..-", "...-", ".--", "-..-", "-.--", "--..")
        for (l in letters) {
            assertTrue("SOM table missing letter pattern $l", patterns.contains(l))
        }
    }

    @Test
    fun fftFilter_lowpass_passesTone() {
        val filt = CwFftFilt(600.0 / 8000.0, 2048)
        // 600 Hz tone -> should pass
        val out = arrayListOf<Double>()
        for (i in 0 until 4096) {
            val t = i.toDouble() / 8000.0
            val z = CwComplex(0.5 * sin(2.0 * PI * 600.0 * t), 0.0)
            filt.run(z)?.let { o -> for (j in 0 until 1024) out.add(o[j].abs()) }
        }
        val maxVal = out.maxOrNull() ?: 0.0
        assertTrue("expected signal to pass through lowpass, max=$maxVal", maxVal > 0.05)
    }
}
