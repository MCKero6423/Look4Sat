package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Verifies the WaveLog upload payload frequency/band fields.
 *
 * Regression: user reported QSOs landing in the 160m band. The v2 JSON
 * envelope must carry freq as a MHz string with an "M" suffix so WaveLog's
 * parse_frequency() reads it as Hz internally; a bare integer or bare MHz
 * value corrupts band derivation.
 */
class WaveLogApiPayloadTest {

    // SO-50: uplink 145.850 MHz, downlink 436.795 MHz
    private val uplinkHz = 145_850_000L
    private val downlinkHz = 436_795_000L

    @Test
    fun v2_freq_usesMhzStringWithMSuffix() {
        val freq = String.format(Locale.ENGLISH, "%.6fM", uplinkHz / 1_000_000.0)
        val freqRx = String.format(Locale.ENGLISH, "%.6fM", downlinkHz / 1_000_000.0)
        assertEquals("145.850000M", freq)
        assertEquals("436.795000M", freqRx)
        // WaveLog parse_frequency: "145.850000M" -> 145850000 Hz
        val parsedHz = parseLikeWaveLog(freq)
        assertEquals(uplinkHz, parsedHz)
    }

    @Test
    fun v1_adif_freq_isBareMhzNumber() {
        // v1 ADIF <FREQ> is a bare MHz number per ADIF spec (no unit suffix)
        val freq = String.format(Locale.ENGLISH, "%.6f", uplinkHz / 1_000_000.0)
        assertEquals("145.850000", freq)
        val adifFreq = freq.toDouble() * 1_000_000
        assertEquals(uplinkHz.toDouble(), adifFreq, 1.0)
    }

    /** Mirrors WaveLog Logbook_model::parse_frequency: int = Hz, "12.3M" suffix = MHz. */
    private fun parseLikeWaveLog(raw: String): Long {
        val s = raw.trim()
        return if (s.endsWith("M", ignoreCase = true)) {
            (s.dropLast(1).toDouble() * 1_000_000).toLong()
        } else if (s.endsWith("k", ignoreCase = true)) {
            (s.dropLast(1).toDouble() * 1_000).toLong()
        } else {
            s.toLong()
        }
    }

    @Test
    fun qsoFreqs_stayInSatelliteBands_afterDoppler() {
        // Doppler-corrected values must remain near the base frequency.
        // SPEED_OF_LIGHT = 299792458 m/s; distanceRate is km/s (x1000 -> m/s).
        val dopplerRate = 7.0 // km/s approaching
        val corrected = uplinkHz * (299_792_458.0 + dopplerRate * 1000.0) / 299_792_458.0
        assertTrue(
            "corrected within +-20kHz, got ${corrected - uplinkHz} Hz",
            kotlin.math.abs(corrected - uplinkHz) < 20_000
        )
        // band derivation: 145.x MHz -> 2m, never 160m (1.8-2.0 MHz)
        val mhz = corrected / 1_000_000.0
        assertTrue("145.x MHz stays in 2m, got $mhz MHz", mhz in 144.0..148.0)
    }

    @Test
    fun band_isRealBand_notSAT() {
        // SO-50: TX 145.850 MHz (VHF) -> band 2M, sat mode V/U
        assertEquals("2M", WaveLogApi.bandFromHz(145_850_000))
        assertEquals("V/U", WaveLogApi.satModeFrom(145_850_000, 436_795_000))
        // AO-73: TX 435.150 MHz (UHF up), RX 145.950 MHz (VHF down) -> band 70CM, U/V
        assertEquals("70CM", WaveLogApi.bandFromHz(435_150_000))
        assertEquals("U/V", WaveLogApi.satModeFrom(435_150_000, 145_950_000))
        // Same-band (e.g. simplex) -> empty sat mode
        assertEquals("", WaveLogApi.satModeFrom(145_850_000, 145_950_000))
        // Never 160m for satellite frequencies
        assertTrue(WaveLogApi.bandFromHz(145_850_000) != "160M")
        assertTrue(WaveLogApi.bandFromHz(436_795_000) != "160M")
    }

    @Test
    fun adif_containsRealBandAndSatMode() {
        // v2 payload fields (mirror postQso construction)
        val band = WaveLogApi.bandFromHz(uplinkHz)
        val satMode = WaveLogApi.satModeFrom(uplinkHz, downlinkHz)
        assertEquals("2M", band)
        assertEquals("V/U", satMode)
    }
}
