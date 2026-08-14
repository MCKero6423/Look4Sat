package com.rtbishop.look4sat.core.domain.aprs

import kotlin.math.abs
import kotlin.math.round
import java.util.Locale

/**
 * APRS-IS protocol core (pure Kotlin, no Android dependencies).
 * Reverse-ported from APRSdroid 1.6.3d: AprsPacket$.scala + ab0oo Position.java.
 */
object AprsPacket {

    /** APRS-IS passcode algorithm (standard): 0x73E2 seed, uppercase callsign + \0, XOR each char pair */
    fun passcode(callsign: String): Int {
        val s = callsign.split("-")[0].uppercase() + "\u0000"
        var hash = 29666 // 0x73E2
        var i = 0
        while (i <= s.length - 2) {
            hash = hash xor (s[i].code * 256 + s[i + 1].code)
            i += 2
        }
        return hash and 0x7FFF
    }

    /** Login line: user CALL-SSID pass XXXX vers XXXX */
    fun formatLogin(callsign: String, ssid: String, passcode: Int, version: String): String {
        val callSsid = formatCallSsid(callsign, ssid)
        return "user $callSsid pass $passcode vers $version"
    }

    /** Callsign-SSID join (BG7NTA + 5 -> BG7NTA-5) */
    fun formatCallSsid(callsign: String, ssid: String): String {
        if (ssid.isNullOrEmpty()) return callsign
        return "$callsign-$ssid"
    }

    /** Optional distance filter: filter r/lat/lon/dist */
    fun formatRangeFilter(latitude: Double, longitude: Double, distKm: Int): String {
        return String.format(Locale.ROOT, "r/%.3f/%.3f/%d", latitude, longitude, distKm)
    }

    /** Altitude extension /A=00000 (feet) */
    fun formatAltitude(altitudeMeters: Double?): String {
        if (altitudeMeters == null) return ""
        return String.format(Locale.ROOT, "/A=%06d", (altitudeMeters * 3.2808399).toInt())
    }

    /** Speed/course extension (knots/degrees) */
    fun formatCourseSpeed(speedMps: Double?, bearing: Float?): String {
        if (speedMps == null || bearing == null) return ""
        val knots = (speedMps * 1.94384449).toInt()
        return String.format(Locale.ROOT, "/%03d/%03d", bearing.toInt(), knots)
    }
}

/**
 * APRS position encoding (reverse-ported from ab0oo Position.java).
 * Uncompressed: DDMM.MMN/DDDMM.MME; compressed: base91.
 */
class AprsPosition(
    val latitude: Double,
    val longitude: Double,
    val symbolTable: Char,
    val symbolCode: Char,
    val positionAmbiguity: Int = 0
) {

    /** Uncompressed format (APRS-IS default reporting format) */
    fun toUncompressedString(): String {
        val lat = getDMS(latitude, true)
        val lon = getDMS(longitude, false)
        return "$lat$symbolTable$lon$symbolCode"
    }

    /** Compressed format (base91, standard APRS algorithm) */
    fun toCompressedString(): String {
        val jRound = round((90.0 - latitude) * 380926.0).toLong()
        val j = jRound / 753571 + 33
        val j2 = jRound % 753571
        val j3 = j2 / 8281 + 33
        val j4 = j2 % 8281
        val i = (j4 % 91).toInt() + 33
        val jRound2 = round((longitude + 180.0) * 190463.0).toLong()
        val j5 = jRound2 / 753571 + 33
        val j6 = jRound2 % 753571
        val j7 = 33 + j6 / 8281
        val j8 = j6 % 8281
        return "" + symbolTable + j.toInt().toChar() + j3.toInt().toChar() +
            ((j4 / 91).toInt() + 33).toChar() + i.toChar() +
            j5.toInt().toChar() + j7.toInt().toChar() +
            ((j8 / 91).toInt() + 33).toChar() +
            ((j8 % 91).toInt() + 33).toChar() + symbolCode
    }

    /** Single-axis DMS encoding (hundredths) */
    private fun getDMS(value: Double, isLat: Boolean): String {
        var iRound = round(value * 6000.0).toInt()
        if (iRound < 0) iRound = -iRound
        val degrees = iRound / 6000
        val minutes = (iRound / 100) % 60
        val hundredths = iRound % 100
        val frac = when (positionAmbiguity) {
            1 -> "  .  "
            2 -> String.format(Locale.ROOT, "%d .  ", minutes / 10)
            3 -> String.format(Locale.ROOT, "%02d.  ", minutes)
            4 -> String.format(Locale.ROOT, "%02d.%d ", minutes, hundredths / 10)
            else -> String.format(Locale.ROOT, "%02d.%02d", minutes, hundredths)
        }
        return if (isLat) {
            val ns = if (value >= 0) 'N' else 'S'
            String.format(Locale.ROOT, "%02d%s%c", degrees, frac, ns)
        } else {
            val ew = if (value >= 0) 'E' else 'W'
            String.format(Locale.ROOT, "%03d%s%c", degrees, frac, ew)
        }
    }
}
