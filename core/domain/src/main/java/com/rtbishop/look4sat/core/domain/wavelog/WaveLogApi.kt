/*
 * WaveLogApi.kt - WaveLog log server API client (4.5.2 override fix 2).
 *
 * Supports both v1 and v2 (user's server only has v1 in practice; v2 returns 404):
 *   v2: POST {base}/api/v2/qso            (Authorization: Bearer + JSON fields)
 *   v1: POST {base}/index.php/api/qso     (key in JSON body + ADIF string)
 * Strategy: try v2 first, auto-fallback to v1 on 404.
 * Test connection: v2 GET api/v2/token; on 404 use v1 POST api/get_contacts_adif.
 * Station grid: only v2 has GET api/v2/station/{id}; v1 lacks it -> fall back to user QTH.
 */
package com.rtbishop.look4sat.core.domain.wavelog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Station info (GET /api/v2/station/{id} result) */
data class WavelogStation(
    val id: Int,
    val name: String,
    val callsign: String,
    val gridsquare: String
)

sealed class WavelogResult {
    data class Success(val message: String) : WavelogResult()
    data class Failure(val message: String) : WavelogResult()
}

object WaveLogApi {

    private const val TIMEOUT_MS = 15000

    /** Normalize server URL: strip trailing slash/index.php; prepend https:// when missing */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.endsWith("/index.php")) u = u.removeSuffix("/index.php")
        return u
    }

    /** Test connection: v2 GET api/v2/token; on 404 use v1 POST api/get_contacts_adif */
    suspend fun testToken(url: String, apiKey: String, stationId: String = ""): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")

        // v2: GET /index.php/api/v2/token
        val v2 = httpRequest("$base/index.php/api/v2/token", "GET", apiKey, null)
        if (v2.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v2)")

        // v1: POST /index.php/api/get_contacts_adif (key in body)
        if (stationId.isNotBlank()) {
            val body = JSONObject().apply {
                put("key", apiKey)
                put("station_id", stationId)
                put("fetchfromid", 0)
            }.toString()
            val v1 = httpRequest("$base/index.php/api/get_contacts_adif", "POST", apiKey, body)
            if (v1.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v1)")
            if (v1.first == 401) return@withContext WavelogResult.Failure("API 密钥无效 (v1: 401)")
        }
        // v1 attempt without index.php
        val body = JSONObject().apply {
            put("key", apiKey)
            put("station_id", stationId)
            put("fetchfromid", 0)
        }.toString()
        val v1b = httpRequest("$base/api/get_contacts_adif", "POST", apiKey, body)
        if (v1b.first in 200..299) return@withContext WavelogResult.Success("连接成功 (API v1)")
        if (v1b.first == 401) return@withContext WavelogResult.Failure("API 密钥无效 (v1: 401)")

        WavelogResult.Failure("连接失败: v2 HTTP ${v2.first}, v1 HTTP ${v1b.first} — 请确认服务器地址/密钥正确")
    }

    /** Station info: v2 only; v1 lacks the endpoint (grid check falls back to user QTH) */
    suspend fun getStation(url: String, apiKey: String, stationId: String): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")
        val (code, resp) = httpRequest("$base/index.php/api/v2/station/$stationId", "GET", apiKey, null)
        if (code in 200..299) {
            return@withContext try {
                val obj = JSONObject(resp)
                val data = obj.optJSONObject("data") ?: obj
                val station = WavelogStation(
                    id = data.optInt("id"),
                    name = data.optString("name"),
                    callsign = data.optString("callsign"),
                    gridsquare = data.optString("gridsquare")
                )
                WavelogResult.Success(JSONObject().apply {
                    put("id", station.id); put("name", station.name)
                    put("callsign", station.callsign); put("gridsquare", station.gridsquare)
                }.toString())
            } catch (e: Exception) {
                WavelogResult.Failure("解析失败: ${e.message}")
            }
        }
        // v1 has no station endpoint -> return empty Success (caller falls back to user QTH)
        WavelogResult.Success("")
    }

    /**
     * ADIF band code from a frequency in Hz. "SAT" is NOT a legal ADIF band
     * value (the Band enumeration is 160M/80M/.../2M/70CM/23CM...); a logger
     * that fails to parse an illegal band falls back to a default such as
     * 160m. Satellite QSOs must carry the real band of the TX frequency.
     */
    fun bandFromHz(freqHz: Long): String = when {
        freqHz >= 1240_000_000 -> "23CM"
        freqHz >= 902_000_000 -> "33CM"
        freqHz >= 420_000_000 -> "70CM"
        freqHz >= 222_000_000 -> "1.25M"
        freqHz >= 144_000_000 -> "2M"
        freqHz >= 50_000_000 -> "6M"
        freqHz >= 28_000_000 -> "10M"
        freqHz >= 24_890_000 -> "12M"
        freqHz >= 21_000_000 -> "15M"
        freqHz >= 18_068_000 -> "17M"
        freqHz >= 14_000_000 -> "20M"
        freqHz >= 10_000_000 -> "30M"
        freqHz >= 7_000_000 -> "40M"
        freqHz >= 5_102_000 -> "60M"
        freqHz >= 3_500_000 -> "80M"
        freqHz >= 1_800_000 -> "160M"
        else -> "160M"
    }

    /** Band class letter for satellite mode derivation: VHF=V, UHF=U, SHF=S. */
    private fun bandLetter(freqHz: Long): String = when {
        freqHz >= 1_240_000_000 -> "S"
        freqHz >= 420_000_000 -> "U"
        freqHz >= 144_000_000 -> "V"
        else -> "V"
    }

    /**
     * ADIF SAT_MODE (free text, satellite convention): "V/U" = VHF up /
     * UHF down, "U/V", "V/S", "U/S"... Derived from the actual TX/RX bands.
     */
    fun satModeFrom(txFreqHz: Long, rxFreqHz: Long): String {
        if (rxFreqHz <= 0) return ""
        val up = bandLetter(txFreqHz)
        val down = bandLetter(rxFreqHz)
        return if (up == down) "" else "$up/$down"
    }

    /**
     * The name LoTW accepts for this satellite, resolved from its catalogue number when known.
     *
     * LoTW rejects a QSO whose SAT_NAME is not spelled as its accepted list has it - its help
     * page gives AO7 against AO-7 as an example - so this has to produce the exact spelling or
     * nothing useful at all.
     *
     * [catnum] is preferred because the name alone cannot decide it: TLE sources disagree, and
     * of the 49 satellites carried by both Celestrak amateur and AMSAT nasabare, 33 are named
     * differently. NORAD 43017 is "RADFXSAT (FOX-1B)" in one and "AO-91" in the other, 43700 is
     * "ES'HAIL 2" against "QO-100". Deriving the name from the TLE text resolved 0 of 96
     * satellites to something LoTW accepts, because the descriptive part of a TLE name is never
     * the OSCAR designator.
     *
     * The name path remains as a fallback for QSOs logged before the catalogue number was
     * recorded. It tries the whole name, then either side of the parentheses, since which side
     * carries the designator varies - "SAUDISAT 1C (SO-50)" has it inside, "ISS (ZARYA)" does not.
     */
    fun normalizeSatName(raw: String, catnum: Int? = null): String {
        catnum?.let { LotwSatelliteIds.nameFor(it) }?.let { return it }
        val trimmed = raw.trim()
        for (candidate in nameCandidates(trimmed)) {
            LotwSatellites.names.firstOrNull { it.equals(candidate, ignoreCase = true) }
                ?.let { return it }
        }
        // Tolerate a missing or extra hyphen: sources write RS15 where LoTW has RS-15.
        for (candidate in nameCandidates(trimmed)) {
            val squashed = candidate.squashSeparators()
            LotwSatellites.names.firstOrNull { it.squashSeparators() == squashed }
                ?.let { return it }
        }
        return trimmed.uppercase(Locale.ENGLISH)
    }

    /** True when [normalizeSatName] produced a name LoTW will accept rather than a guess. */
    fun isLotwSatellite(name: String, catnum: Int? = null): Boolean {
        val resolved = normalizeSatName(name, catnum)
        return LotwSatellites.names.any { it.equals(resolved, ignoreCase = true) }
    }

    /** The whole name plus either side of the parentheses, longest first. */
    private fun nameCandidates(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val parts = mutableListOf(raw)
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        if (open in 0..<close) {
            parts += raw.substring(open + 1, close).trim()
            parts += raw.substring(0, open).trim()
        }
        // Formation launches are catalogued as "RS-44 & BREEZE-KM R/B".
        if ('&' in raw) parts += raw.substringBefore('&').trim()
        return parts.filter { it.isNotEmpty() }.distinct()
    }

    private fun String.squashSeparators() = replace(Regex("[-\\s._/]"), "").uppercase(Locale.ENGLISH)

    /** Create QSO: v2 first, fall back to v1 (ADIF) on 404 */
    suspend fun postQso(
        url: String,
        apiKey: String,
        stationProfileId: String,
        qso: WavelogQso,
        gridsquare: String
    ): WavelogResult = withContext(Dispatchers.IO) {
        val base = normalizeUrl(url)
        if (base.isBlank()) return@withContext WavelogResult.Failure("服务器地址为空")

        val satName = normalizeSatName(qso.satName, qso.catnum.takeIf { it > 0 })

        // v2: POST /index.php/api/v2/qso (JSON fields)
        val satMode = satModeFrom(qso.freqTxHz, qso.freqRxHz)
        val v2Body = JSONObject().apply {
            put("station_profile_id", stationProfileId.toIntOrNull() ?: 0)
            put("call", qso.call)
            put("band", bandFromHz(qso.freqTxHz))
            put("mode", qso.mode)
            put("qso_date", utcDate(qso.timeUtcMs))
            put("time_on", utcTime(qso.timeUtcMs))
            put("freq", String.format(Locale.ENGLISH, "%.6fM", qso.freqTxHz / 1_000_000.0))
            put("freq_rx", String.format(Locale.ENGLISH, "%.6fM", qso.freqRxHz / 1_000_000.0))
            put("gridsquare", gridsquare)
            put("rst_sent", "59")
            put("rst_rcvd", "59")
            put("sat_name", satName)
            if (satMode.isNotBlank()) put("sat_mode", satMode)
        }
        // The body decides, not the status code: Wavelog validates after responding, so a rejected
        // QSO arrives as HTTP 200 with {"status":"failed"}. Trusting the code marked it uploaded
        // and dropped it from the queue.
        val (code, resp) = httpRequest("$base/index.php/api/v2/qso", "POST", apiKey, v2Body.toString())
        when (val verdict = WavelogResponse.verdict(code, resp)) {
            is WavelogResponse.Verdict.Accepted -> return@withContext WavelogResult.Success("v2")
            WavelogResponse.Verdict.Duplicate -> return@withContext WavelogResult.Success("duplicate")
            is WavelogResponse.Verdict.Rejected ->
                return@withContext WavelogResult.Failure(verdict.reason)
            // Unreadable falls through to v1: an older server may not have the v2 endpoint at all.
            is WavelogResponse.Verdict.Unreadable -> Unit
        }

        // v1: POST /index.php/api/qso (key in body + ADIF)
        val v1Body = JSONObject().apply {
            put("key", apiKey)
            put("station_profile_id", stationProfileId)
            put("type", "adif")
            put("string", toAdif(qso, gridsquare, satName))
        }
        val (code1, resp1) = httpRequest("$base/index.php/api/qso", "POST", apiKey, v1Body.toString())
        when (val verdict = WavelogResponse.verdict(code1, resp1)) {
            is WavelogResponse.Verdict.Accepted -> return@withContext WavelogResult.Success("v1")
            WavelogResponse.Verdict.Duplicate -> return@withContext WavelogResult.Success("duplicate")
            is WavelogResponse.Verdict.Rejected ->
                return@withContext WavelogResult.Failure(verdict.reason)
            is WavelogResponse.Verdict.Unreadable -> Unit
        }

        // v1 without index.php, for a server whose rewrite rules differ
        val (code1b, resp1b) = httpRequest("$base/api/qso", "POST", apiKey, v1Body.toString())
        when (val verdict = WavelogResponse.verdict(code1b, resp1b)) {
            is WavelogResponse.Verdict.Accepted -> return@withContext WavelogResult.Success("v1")
            WavelogResponse.Verdict.Duplicate -> return@withContext WavelogResult.Success("duplicate")
            is WavelogResponse.Verdict.Rejected ->
                return@withContext WavelogResult.Failure(verdict.reason)
            is WavelogResponse.Verdict.Unreadable -> Unit
        }

        // Every endpoint answered something we could not read. Keeping the QSO queued is the only
        // honest outcome: it may have been stored, and dropping it would lose the contact.
        WavelogResult.Failure(
            "unreadable response: v2 HTTP $code, v1 HTTP $code1 - ${shortError(resp1.ifBlank { resp1b })}"
        )
    }

    /** v1 ADIF string (freq in MHz, length = UTF-8 byte count, sat_name normalized) */
    internal fun toAdif(qso: WavelogQso, gridsquare: String, satName: String): String {
        fun field(name: String, value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8).size
            return "<$name:$bytes>$value"
        }
        val satMode = satModeFrom(qso.freqTxHz, qso.freqRxHz)
        return buildString {
            append(field("call", qso.call))
            append(field("band", bandFromHz(qso.freqTxHz)))
            append(field("mode", qso.mode))
            append(field("freq", String.format(Locale.ENGLISH, "%.6f", qso.freqTxHz / 1_000_000.0)))
            if (qso.freqRxHz > 0) {
                append(field("freq_rx", String.format(Locale.ENGLISH, "%.6f", qso.freqRxHz / 1_000_000.0)))
            }
            append(field("qso_date", utcDateCompact(qso.timeUtcMs)))
            append(field("time_on", utcTimeCompact(qso.timeUtcMs)))
            append(field("rst_sent", "59"))
            append(field("rst_rcvd", "59"))
            // Send the grid at full precision. Truncating to 4 characters threw
            // away the 6-character locator the QRZ lookup provides, coarsening the
            // stored position from ~4.6 km to ~100 km and making a QSO logged via
            // v1 disagree with the same QSO logged via v2 (which sends it whole).
            if (gridsquare.isNotBlank()) append(field("gridsquare", gridsquare))
            if (satName.isNotBlank()) {
                append(field("sat_name", satName))
                if (satMode.isNotBlank()) append(field("sat_mode", satMode))
                append(field("prop_mode", "SAT"))
            }
            append("<eor>")
        }
    }

    /** Generic HTTP request (returns code + body) */
    private fun httpRequest(url: String, method: String, apiKey: String, jsonBody: String?): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            code to body
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun shortError(body: String): String {
        if (body.startsWith("<")) return body.take(80) // HTML error page
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            err?.optString("message")?.ifBlank { body.take(120) }
                ?: obj.optString("reason").ifBlank { obj.optString("message").ifBlank { body.take(120) } }
        } catch (_: Exception) {
            body.take(120)
        }
    }

    private fun utcDate(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun utcTime(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    private fun utcDateCompact(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun utcTimeCompact(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return "%02d%02d%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }
}
