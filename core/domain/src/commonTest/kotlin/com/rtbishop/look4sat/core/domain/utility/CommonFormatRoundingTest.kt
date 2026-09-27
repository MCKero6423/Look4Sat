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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * java.lang.String.format rounds the shortest decimal representation of a double half-up, not its
 * binary value, so "%.3f" of 0.5005 is "0.501" - the stored double is 0.50049999999999994493.
 * Scaling in binary first (floor(value * 10^precision + 0.5)) answers "0.500" instead, which is
 * what the shared formatter used to do.
 *
 * Every expected string below was produced by java.lang.String.format(Locale.ROOT, ...) and pinned
 * as a literal, so the iOS run checks the same digits with no JVM in reach.
 */
class CommonFormatRoundingTest {

    @Test
    fun `rounds up where the decimal digits reach a half`() {
        assertEquals("0.501", formatString("%.3f", 0.5005))
        assertEquals("1.01", formatString("%.2f", 1.005))
        assertEquals("2.68", formatString("%.2f", 2.675))
        assertEquals("145.68", formatString("%.2f", 145.675))
        assertEquals("0.000125", formatString("%.6f", 1.245e-4))
        assertEquals("51.507", formatString("%.3f", 51.507123))
        assertEquals("10.000", formatString("%.3f", 9.9999))
        assertEquals("1.00", formatString("%.2f", 0.999))
    }

    @Test
    fun `rounds down where the decimal digits fall short of a half`() {
        assertEquals("0.500", formatString("%.3f", 0.50049))
        assertEquals("51.507", formatString("%.3f", 51.5074999))
        assertEquals("2.67", formatString("%.2f", 2.6749))
    }

    @Test
    fun `keeps the shapes the wire formats need`() {
        assertEquals("10000000.000000", formatString("%f", 1.0e7))
        assertEquals("145.675000", formatString("%.6f", 145_675_000.0 / 1_000_000.0))
        assertEquals("0.000", formatString("%.3f", 4.9e-324))
        assertEquals("-0.000", formatString("%.3f", -0.0))
        assertEquals("-0.501", formatString("%.3f", -0.5005))
    }
}
