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

import com.rtbishop.look4sat.core.domain.source.HttpResult
import com.rtbishop.look4sat.core.domain.source.IHttpClient
import com.rtbishop.look4sat.core.domain.utility.formatString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    /** Milliseconds in a day; date fields are derived from the QSO timestamp without Calendar. */
    private const val MS_PER_DAY = 86_400_000L

    /**
     * Platform HTTP client for every request below. Handed over by the DI container because an
     * object has no constructor for it, and a shared object has no platform socket API to use
     * on its own.
     */
    private var httpClient: IHttpClient? = null

    fun installHttpClient(client: IHttpClient) {
        httpClient = client
    }

    /** Normalize server URL: strip trailing slash/index.php; prepend https:// when missing */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.endsWith("/index.php")) u = u.removeSuffix("/index.php")
        return u
    }

    /** Test connection: v2 GET api/v2/token; on 404 use v1 POST api/get_contacts_adif */
    suspend fun testToken(url: String, apiKey: String, stationId: String = ""): WavelogResult {
        val base = normalizeUrl(url)
        if (base.isBlank()) return WavelogResult.Failure("服务器地址为空")

        // v2: GET /index.php/api/v2/token
        val v2 = httpRequest("$base/index.php/api/v2/token", apiKey, null)
        if (v2.code in 200..299) return WavelogResult.Success("连接成功 (API v2)")

        // v1: POST /index.php/api/get_contacts_adif (key in body)
        if (stationId.isNotBlank()) {
            val body = buildJsonObject {
                put("key", apiKey)
                put("station_id", stationId)
                put("fetchfromid", 0)
            }.toString()
            val v1 = httpRequest("$base/index.php/api/get_contacts_adif", apiKey, body)
            if (v1.code in 200..299) return WavelogResult.Success("连接成功 (API v1)")
            if (v1.code == 401) return WavelogResult.Failure("API 密钥无效 (v1: 401)")
        }
        // v1 attempt without index.php
        val body = buildJsonObject {
            put("key", apiKey)
            put("station_id", stationId)
            put("fetchfromid", 0)
        }.toString()
        val v1b = httpRequest("$base/api/get_contacts_adif", apiKey, body)
        if (v1b.code in 200..299) return WavelogResult.Success("连接成功 (API v1)")
        if (v1b.code == 401) return WavelogResult.Failure("API 密钥无效 (v1: 401)")

        return WavelogResult.Failure("连接失败: v2 HTTP ${v2.code}, v1 HTTP ${v1b.code} — 请确认服务器地址/密钥正确")
    }

    /** Station info: v2 only; v1 lacks the endpoint (grid check falls back to user QTH) */
    suspend fun getStation(url: String, apiKey: String, stationId: String): WavelogResult {
        val base = normalizeUrl(url)
        if (base.isBlank()) return WavelogResult.Failure("服务器地址为空")
        val (code, resp) = httpRequest("$base/index.php/api/v2/station/$stationId", apiKey, null)
        if (code in 200..299) {
            return try {
                val obj = Json.parseToJsonElement(resp).jsonObject
                val data = obj["data"] as? JsonObject ?: obj
                val station = WavelogStation(
                    id = data["id"]?.jsonPrimitive?.intOrNull ?: 0,
                    name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    callsign = data["callsign"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    gridsquare = data["gridsquare"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
                WavelogResult.Success(buildJsonObject {
                    put("id", station.id); put("name", station.name)
                    put("callsign", station.callsign); put("gridsquare", station.gridsquare)
                }.toString())
            } catch (e: Exception) {
                WavelogResult.Failure("解析失败: ${e.message}")
            }
        }
        // v1 has no station endpoint -> return empty Success (caller falls back to user QTH)
        return WavelogResult.Success("")
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
        return trimmed.uppercase()
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

    private fun String.squashSeparators() = replace(Regex("[-\\s._/]"), "").uppercase()

    /** Create QSO: v2 first, fall back to v1 (ADIF) on 404 */
    suspend fun postQso(
        url: String,
        apiKey: String,
        stationProfileId: String,
        qso: WavelogQso,
        gridsquare: String
    ): WavelogResult {
        val base = normalizeUrl(url)
        if (base.isBlank()) return WavelogResult.Failure("服务器地址为空")

        val satName = normalizeSatName(qso.satName, qso.catnum.takeIf { it > 0 })

        // v2: POST /index.php/api/v2/qso (JSON fields)
        val satMode = satModeFrom(qso.freqTxHz, qso.freqRxHz)
        val v2Body = buildJsonObject {
            put("station_profile_id", stationProfileId.toIntOrNull() ?: 0)
            put("call", qso.call)
            put("band", bandFromHz(qso.freqTxHz))
            put("mode", qso.mode)
            put("qso_date", utcDate(qso.timeUtcMs))
            put("time_on", utcTime(qso.timeUtcMs))
            put("freq", formatString("%.6fM", qso.freqTxHz / 1_000_000.0))
            put("freq_rx", formatString("%.6fM", qso.freqRxHz / 1_000_000.0))
            put("gridsquare", gridsquare)
            put("rst_sent", "59")
            put("rst_rcvd", "59")
            put("sat_name", satName)
            if (satMode.isNotBlank()) put("sat_mode", satMode)
        }
        // The body decides, not the status code: Wavelog validates after responding, so a rejected
        // QSO arrives as HTTP 200 with {"status":"failed"}. Trusting the code marked it uploaded
        // and dropped it from the queue.
        val (code, resp) = httpRequest("$base/index.php/api/v2/qso", apiKey, v2Body.toString())
        val v2Verdict = WavelogResponse.verdict(code, resp)
        when (v2Verdict) {
            is WavelogResponse.Verdict.Accepted -> return WavelogResult.Success("v2")
            WavelogResponse.Verdict.Duplicate -> return WavelogResult.Success("duplicate")
            // Anything else falls through to v1. A rejection here is NOT final: v2 refuses a legacy
            // v1 key with 401 invalid_token, and returning at that point stopped a v1-only operator
            // from uploading at all. The v1 attempt below is the one that can speak for them.
            else -> Unit
        }

        // v1: POST /index.php/api/qso (key in body + ADIF)
        val v1Body = buildJsonObject {
            put("key", apiKey)
            put("station_profile_id", stationProfileId)
            put("type", "adif")
            put("string", toAdif(qso, gridsquare, satName))
        }
        val (code1, resp1) = httpRequest("$base/index.php/api/qso", apiKey, v1Body.toString())
        val v1Verdict = WavelogResponse.verdict(code1, resp1)
        when (v1Verdict) {
            is WavelogResponse.Verdict.Accepted -> return WavelogResult.Success("v1")
            WavelogResponse.Verdict.Duplicate -> return WavelogResult.Success("duplicate")
            // Also falls through: a server with different rewrite rules answers this path with a
            // 404 page, which is a rejection but says nothing about whether the QSO can be stored.
            else -> Unit
        }

        // v1 without index.php, for a server whose rewrite rules differ
        val (code1b, resp1b) = httpRequest("$base/api/qso", apiKey, v1Body.toString())
        when (val verdict = WavelogResponse.verdict(code1b, resp1b)) {
            is WavelogResponse.Verdict.Accepted -> return WavelogResult.Success("v1")
            WavelogResponse.Verdict.Duplicate -> return WavelogResult.Success("duplicate")
            is WavelogResponse.Verdict.Rejected ->
                return WavelogResult.Failure(verdict.reason)
            is WavelogResponse.Verdict.Unreadable -> Unit
        }

        // Every endpoint answered something we could not read. Keeping the QSO queued is the only
        // honest outcome: it may have been stored, and dropping it would lose the contact.
        // No endpoint accepted it. The v2 reason is preferred when it explained itself, since a 401
        // invalid_token is the most actionable thing an operator can be told; otherwise all three
        // status codes go out, because the third was previously dropped from this message.
        val reasons = listOfNotNull(
            (v1Verdict as? WavelogResponse.Verdict.Rejected)?.reason,
            (v2Verdict as? WavelogResponse.Verdict.Rejected)?.reason
        ).filter { it.isNotBlank() }
        return WavelogResult.Failure(
            reasons.firstOrNull()
                ?: ("no endpoint accepted it: v2 HTTP $code, v1 HTTP $code1, v1-alt HTTP $code1b" +
                    " - " + shortError(resp1.ifBlank { resp1b }))
        )
    }

    /** v1 ADIF string (freq in MHz, length = UTF-8 byte count, sat_name normalized) */
    internal fun toAdif(qso: WavelogQso, gridsquare: String, satName: String): String {
        fun field(name: String, value: String): String {
            val bytes = value.encodeToByteArray().size
            return "<$name:$bytes>$value"
        }
        val satMode = satModeFrom(qso.freqTxHz, qso.freqRxHz)
        return buildString {
            append(field("call", qso.call))
            append(field("band", bandFromHz(qso.freqTxHz)))
            append(field("mode", qso.mode))
            append(field("freq", formatString("%.6f", qso.freqTxHz / 1_000_000.0)))
            if (qso.freqRxHz > 0) {
                append(field("freq_rx", formatString("%.6f", qso.freqRxHz / 1_000_000.0)))
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

    /**
     * POST [jsonBody] when one is given, GET otherwise. A request that could not be sent at all
     * comes back as code 0, so callers only have to look at the code.
     */
    private suspend fun httpRequest(url: String, apiKey: String, jsonBody: String?): HttpResult {
        val client = httpClient ?: error("WaveLogApi has no HTTP client installed")
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            if (jsonBody != null) {
                put("Content-Type", "application/json")
                put("Accept", "application/json")
            }
        }
        val result = if (jsonBody != null) client.post(url, headers, jsonBody) else client.get(url, headers)
        // A request that never left the phone used to report the exception text where the body
        // goes, which is what the failure messages below read; keep it there.
        val failure = result.failure
        return if (result.body.isEmpty() && failure != null) result.copy(body = failure) else result
    }

    private fun shortError(body: String): String {
        if (body.startsWith("<")) return body.take(80) // HTML error page
        return try {
            val obj = Json.parseToJsonElement(body).jsonObject
            val err = obj["error"] as? JsonObject
            if (err != null) {
                err["message"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { body.take(120) }
            } else {
                obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .ifBlank { obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { body.take(120) } }
            }
        } catch (_: Exception) {
            body.take(120)
        }
    }

    /** UTC civil time of a Unix millisecond stamp; the JVM Calendar is not multiplatform. */
    private data class UtcFields(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)

    private fun utcFieldsOf(ms: Long): UtcFields {
        val days = ms.floorDiv(MS_PER_DAY)
        val millisOfDay = ms.mod(MS_PER_DAY)
        val shifted = days + 719_468
        val era = shifted.floorDiv(146_097)
        val dayOfEra = shifted - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthPart = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthPart + 2) / 5 + 1).toInt()
        val month = (if (monthPart < 10) monthPart + 3 else monthPart - 9).toInt()
        val year = yearOfEra.toInt() + era.toInt() * 400 + (if (month <= 2) 1 else 0)
        val secondOfDay = (millisOfDay / 1000).toInt()
        return UtcFields(year, month, day, secondOfDay / 3_600, secondOfDay / 60 % 60, secondOfDay % 60)
    }

    private fun utcDate(ms: Long): String {
        val utc = utcFieldsOf(ms)
        return formatString("%04d-%02d-%02d", utc.year, utc.month, utc.day)
    }

    private fun utcTime(ms: Long): String {
        val utc = utcFieldsOf(ms)
        return formatString("%02d:%02d:%02d", utc.hour, utc.minute, utc.second)
    }

    private fun utcDateCompact(ms: Long): String {
        val utc = utcFieldsOf(ms)
        return formatString("%04d%02d%02d", utc.year, utc.month, utc.day)
    }

    private fun utcTimeCompact(ms: Long): String {
        val utc = utcFieldsOf(ms)
        return formatString("%02d%02d%02d", utc.hour, utc.minute, utc.second)
    }
}
