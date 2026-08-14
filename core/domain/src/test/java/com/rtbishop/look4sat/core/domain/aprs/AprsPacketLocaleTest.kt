package com.rtbishop.look4sat.core.domain.aprs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * APRS-IS is an ASCII line protocol. Formatting the position, altitude and
 * course/speed extensions with the JVM default locale produced Eastern Arabic
 * or Bengali digits on devices set to ar/fa/bn, and the server rejects those
 * packets.
 *
 * Regression guard: every formatted field must stay ASCII regardless of the
 * default locale.
 */
class AprsPacketLocaleTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    private val asciiPacket = Regex("^[\\x20-\\x7E]*$")

    @Test
    fun position_staysAsciiUnderArabicLocale() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        val encoded = AprsPosition(39.9042, 116.4074, '/', '>').toUncompressedString()

        assertTrue("not ASCII: $encoded", asciiPacket.matches(encoded))
        assertEquals("3954.25N/11624.44E>", encoded)
    }

    @Test
    fun position_staysAsciiUnderBengaliLocale() {
        Locale.setDefault(Locale.forLanguageTag("bn-BD"))

        val encoded = AprsPosition(-33.8688, 151.2093, '/', '>').toUncompressedString()

        assertTrue("not ASCII: $encoded", asciiPacket.matches(encoded))
        assertEquals("3352.13S/15112.56E>", encoded)
    }

    @Test
    fun altitudeAndCourseSpeed_stayAsciiUnderPersianLocale() {
        Locale.setDefault(Locale.forLanguageTag("fa-IR"))

        val altitude = AprsPacket.formatAltitude(100.0)
        val courseSpeed = AprsPacket.formatCourseSpeed(10.0, 90f)
        val filter = AprsPacket.formatRangeFilter(39.9042, 116.4074, 100)

        assertTrue("not ASCII: $altitude", asciiPacket.matches(altitude))
        assertTrue("not ASCII: $courseSpeed", asciiPacket.matches(courseSpeed))
        assertTrue("not ASCII: $filter", asciiPacket.matches(filter))
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
    fun ambiguousPosition_staysAsciiUnderArabicLocale() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        for (ambiguity in 1..4) {
            val encoded = AprsPosition(39.9042, 116.4074, '/', '>', ambiguity)
                .toUncompressedString()
            assertTrue("ambiguity=$ambiguity not ASCII: $encoded", asciiPacket.matches(encoded))
        }
    }
}
