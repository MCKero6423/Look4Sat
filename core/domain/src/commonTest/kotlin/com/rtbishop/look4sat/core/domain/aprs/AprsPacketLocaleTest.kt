package com.rtbishop.look4sat.core.domain.aprs

import com.rtbishop.look4sat.core.domain.utility.formatString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * APRS-IS is an ASCII line protocol. Formatting the position, altitude and
 * course/speed extensions with the JVM default locale produced Eastern Arabic
 * or Bengali digits on devices set to ar/fa/bn, and the server rejects those
 * packets.
 *
 * Regression guard: every formatted field must stay ASCII, and every expected
 * value below is a hardcoded literal. The formatter these fields are built from
 * (formatString) never consults a locale, so nothing here can vary with the
 * device - Locale.setDefault itself is JVM-only and does not exist on iOS.
 */
class AprsPacketLocaleTest {

    private val asciiPacket = Regex("^[\\x20-\\x7E]*$")

    /** The primitive the fields above are built from: fixed digits, never a locale's digits. */
    @Test
    fun formatString_producesAsciiLiterals() {
        assertEquals("/A=000328", formatString("/A=%06d", 328))
        assertEquals("/090/019", formatString("/%03d/%03d", 90, 19))
        assertEquals("r/39.904/116.407/100", formatString("r/%.3f/%.3f/%d", 39.9042, 116.4074, 100))
        assertEquals("3954.25N", formatString("%02d%s%c", 39, "54.25", 'N'))
    }

    @Test
    fun position_staysAscii() {
        val encoded = AprsPosition(39.9042, 116.4074, '/', '>').toUncompressedString()

        assertTrue(asciiPacket.matches(encoded), "not ASCII: $encoded")
        assertEquals("3954.25N/11624.44E>", encoded)
    }

    @Test
    fun position_staysAsciiForSouthernCoordinates() {
        val encoded = AprsPosition(-33.8688, 151.2093, '/', '>').toUncompressedString()

        assertTrue(asciiPacket.matches(encoded), "not ASCII: $encoded")
        assertEquals("3352.13S/15112.56E>", encoded)
    }

    @Test
    fun altitudeAndCourseSpeed_stayAscii() {
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

    @Test
    fun altitude_clampsNegativeToKeepSixDigitField() {
        // "%06d" of a negative value yields "/A=-00164": the '-' takes a digit
        // slot, so the extension is no longer a valid fixed-width field.
        assertEquals("/A=000000", AprsPacket.formatAltitude(-50.0))
        assertEquals("/A=000000", AprsPacket.formatAltitude(-1.0))
        assertEquals("/A=000328", AprsPacket.formatAltitude(100.0))
    }

    @Test
    fun courseSpeed_wrapsCourseIntoValidRange() {
        assertEquals("/000/019", AprsPacket.formatCourseSpeed(10.0, 360f))
        assertEquals("/359/019", AprsPacket.formatCourseSpeed(10.0, -1f))
        assertEquals("/090/019", AprsPacket.formatCourseSpeed(10.0, 90f))
    }

    @Test
    fun ambiguousPosition_staysAscii() {
        for (ambiguity in 1..4) {
            val encoded = AprsPosition(39.9042, 116.4074, '/', '>', ambiguity)
                .toUncompressedString()
            assertTrue(asciiPacket.matches(encoded), "ambiguity=$ambiguity not ASCII: $encoded")
        }
    }
}
