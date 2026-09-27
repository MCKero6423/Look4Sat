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
package com.rtbishop.look4sat.core.domain.aprs

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APRS-IS is an ASCII line protocol. Formatting the position, altitude and course/speed extensions
 * with the JVM default locale produced Eastern Arabic or Bengali digits on devices set to ar/fa/bn
 * and the server rejects those packets.
 *
 * The port dropped java.util.Locale, and Locale.setDefault does not exist on iOS, so these three
 * cases live here now: on the JVM, which is what the Android app runs on. The shared formatter
 * never consults a locale, so the same literals hold on iOS - AprsPacketLocaleTest in commonTest
 * pins them there.
 */
class AprsPacketDefaultLocaleTest {

    private val original: Locale = Locale.getDefault()

    private val asciiPacket = Regex("^[\\x20-\\x7E]*$")

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `position stays ascii under an arabic locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        val encoded = AprsPosition(39.9042, 116.4074, '/', '>').toUncompressedString()

        assertTrue(asciiPacket.matches(encoded), "not ASCII: $encoded")
        assertEquals("3954.25N/11624.44E>", encoded)
    }

    @Test
    fun `position stays ascii under a bengali locale`() {
        Locale.setDefault(Locale.forLanguageTag("bn-BD"))

        val encoded = AprsPosition(-33.8688, 151.2093, '/', '>').toUncompressedString()

        assertTrue(asciiPacket.matches(encoded), "not ASCII: $encoded")
        assertEquals("3352.13S/15112.56E>", encoded)
    }

    @Test
    fun `altitude and course speed stay ascii under a persian locale`() {
        Locale.setDefault(Locale.forLanguageTag("fa-IR"))

        val altitude = AprsPacket.formatAltitude(100.0)
        val courseSpeed = AprsPacket.formatCourseSpeed(10.0, 90f)
        val filter = AprsPacket.formatRangeFilter(39.9042, 116.4074, 100)

        assertTrue(asciiPacket.matches(altitude), "not ASCII: $altitude")
        assertTrue(asciiPacket.matches(courseSpeed), "not ASCII: $courseSpeed")
        assertTrue(asciiPacket.matches(filter), "not ASCII: $filter")
        assertEquals("/A=000328", altitude)
        assertEquals("/090/019", courseSpeed)
        assertEquals("r/39.904/116.407/100", filter)
    }
}
