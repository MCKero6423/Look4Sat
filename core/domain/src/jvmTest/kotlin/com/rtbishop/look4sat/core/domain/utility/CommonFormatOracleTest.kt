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
package com.rtbishop.look4sat.core.domain.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * The shared formatter replaced java.lang.String.format, which Android used to call directly.
 * The strings it builds are wire formats - APRS packets, Wavelog upload payloads - so it has to
 * produce byte-identical output. java.lang.String.format is the reference and is only available
 * on the JVM, which is why this test lives in jvmTest and runs with `:core:domain:jvmTest`.
 */
class CommonFormatOracleTest {

    private fun check(pattern: String, vararg args: Any?) {
        val expected = String.format(Locale.ROOT, pattern, *args)
        assertEquals("pattern=$pattern args=${args.toList()}", expected, formatString(pattern, *args))
    }

    @Test
    fun `integers match java`() {
        listOf(0, 5, 42, -7, 999, Int.MIN_VALUE, Int.MAX_VALUE).forEach { check("%d", it) }
        listOf(5, -5, 0).forEach { check("%02d", it) }
        listOf(5, 42, 999).forEach { check("%03d", it) }
        listOf(5, 123456, -123456).forEach { check("%06d", it) }
        listOf(5L, 123456789L).forEach { check("%010d", it) }
    }

    @Test
    fun `hexadecimal matches java`() {
        listOf(0, 15, 255, 4095).forEach { check("%02X", it) }
    }

    @Test
    fun `decimals match java`() {
        val values = listOf(
            0.0, 1.0, -1.0, 0.5, -0.5, 51.6447, 309.4881, 145.9, 145.900005, 436.795, 12345.6789,
            1.2345, 1.2344, 2.675, 2.6749, 8.835, 0.125, 0.375, 0.0005, -0.0005, 1e-7, -0.0
        )
        values.forEach { check("%.3f", it) }
        values.forEach { check("%.6f", it) }
        values.forEach { check("%.1f", it) }
        values.forEach { check("%.4f", it) }
    }

    @Test
    fun `composed patterns used by the app match java`() {
        // every literal pattern the shared code actually hands to formatString
        check("%02d.  ", 5)
        check("%02d.%02d", 3, 4)
        check("%02d.%d ", 5, 3)
        check("%02d%02d%02d", 1, 2, 3)
        check("%02d:%02d:%02d", 1, 2, 3)
        check("%03d%s%c", 45, "X", 'N')
        check("%04d%02d%02d", 2026, 9, 27)
        check("%04d-%02d-%02d", 2026, 9, 27)
        check("%02d%s%c", 12, "20", 'N')
        check("%d .  ", 3)
        check("r/%.3f/%.3f/%d", 51.6447, 309.4881, 99999)
        check("/A=%06d", 1234)
        check("/%03d/%03d", 5, 7)
        check("%.6fM", 145.9)
        check("%.6f", 145.900005)
        check("%s-%d-%.3f", "a", 1, 2.5)
        check("%f", 1.5)
        check("%f", -0.0000001)
        check("%d%%", 50)
        check("%02X %s", 255, "ok")
    }

    /**
     * The hardcoded values above only exercise the digits they happen to have. Rounding the
     * shortest decimal representation only differs from scaling the binary value where a digit
     * lands exactly on the boundary, so this samples for those: `"%.3f"` of 0.5005 is "0.501".
     */
    @Test
    fun `decimals match java over sampled values`() {
        val random = Random(20260927)
        val values = mutableListOf(
            0.5005, 1.005, 2.675, 1.245e-4, 145.675, 43123.4565, 0.50049, 8.835, 0.0005, -0.0005,
            4.9e-324, -0.0, 9_999_999.9999995, 1.0e7, 123456.789012345, 51.6447, 309.4881
        )
        repeat(20_000) { values += random.nextDouble() * 1_000_000.0 }
        repeat(20_000) { values += random.nextDouble() }
        val mismatches = mutableListOf<String>()
        for (value in values) {
            for (pattern in listOf("%.1f", "%.2f", "%.3f", "%.4f", "%.6f", "%f")) {
                val expected = String.format(Locale.ROOT, pattern, value)
                val actual = formatString(pattern, value)
                if (expected != actual) mismatches += "$pattern of $value: java=$expected shared=$actual"
            }
        }
        assertEquals("first mismatches: ${mismatches.take(10)}", emptyList<String>(), mismatches)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported conversion fails loudly`() {
        formatString("%q", 1)
    }

    /**
     * `%0.3f` is illegal for java.lang.String.format - the `0` flag needs a width - so the shared
     * formatter has to reject it too rather than format it one way on Android and another on iOS.
     */
    @Test
    fun `zero flag without width is rejected, like java`() {
        assertRejected { String.format(Locale.ROOT, "%0.3f", 2.5) }
        assertRejected { formatString("%0.3f", 2.5) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // both implementations agree
        }
    }
}
