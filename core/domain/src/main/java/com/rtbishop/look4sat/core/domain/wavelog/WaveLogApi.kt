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

    /** LoTW-recognized satellite name: main name before parentheses, uppercased (ISS special case) */
    fun normalizeSatName(raw: String): String {
        val main = raw.substringBefore('(').trim()
            .ifBlank { raw.trim() }
            .uppercase(Locale.ENGLISH)
        // Matching logic (mirrors WaveLog satellite table name/displayname matching + LoTW list):
        // 1. Already in LoTW list (common TLE name == common name) -> return as-is
        // 2. Not present -> check Celestrak alias map (SAUDISAT 1C -> SO-50 etc.); mapped name must be in LoTW list
        // 3. Still unmatched -> return as-is (uploads are not blocked; QSO is still saved)
        val commonName = mapOf(
            "ZARYA" to "ARISS",
            "ARISS" to "ARISS",
            "FUNCUBE-1" to "AO-73",
            "DIWATA-2B" to "PO-101",
            "SAUDISAT-1C" to "SO-50",
            "SAUDISAT 1C" to "SO-50",
            "DIWATA-2A" to "PO-101"
        )
        val candidate = commonName[main] ?: main
        return if (candidate in LotwSatellites.names) candidate else main
    }

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

        val satName = normalizeSatName(qso.satName)

        // v2: POST /index.php/api/v2/qso (JSON fields)
        val v2Body = JSONObject().apply {
            put("station_profile_id", stationProfileId.toIntOrNull() ?: 0)
            put("call", qso.call)
            put("band", "SAT")
            put("mode", qso.mode)
            put("qso_date", utcDate(qso.timeUtcMs))
            put("time_on", utcTime(qso.timeUtcMs))
            put("freq", qso.freqTxHz)
            put("freq_rx", qso.freqRxHz)
            put("gridsquare", gridsquare)
            put("rst_sent", "59")
            put("rst_rcvd", "59")
            put("sat_name", satName)
        }
        val (code, resp) = httpRequest("$base/index.php/api/v2/qso", "POST", apiKey, v2Body.toString())
        if (code in 200..299) return@withContext WavelogResult.Success("已上传 (v2)")
        if (code == 409) return@withContext WavelogResult.Success("重复(已存在)")

        // v1: POST /index.php/api/qso (key in body + ADIF)
        val v1Body = JSONObject().apply {
            put("key", apiKey)
            put("station_profile_id", stationProfileId)
            put("type", "adif")
            put("string", toAdif(qso, gridsquare, satName))
        }
        val (code1, resp1) = httpRequest("$base/index.php/api/qso", "POST", apiKey, v1Body.toString())
        if (code1 in 200..299) return@withContext WavelogResult.Success("已上传 (v1)")

        // v1 without index.php
        val (code1b, resp1b) = httpRequest("$base/api/qso", "POST", apiKey, v1Body.toString())
        if (code1b in 200..299) return@withContext WavelogResult.Success("已上传 (v1)")

        WavelogResult.Failure("上传失败: v2 HTTP $code, v1 HTTP $code1 — ${shortError(resp1.ifBlank { resp1b })}")
    }

    /** v1 ADIF string (freq in MHz, length = UTF-8 byte count, sat_name normalized) */
    private fun toAdif(qso: WavelogQso, gridsquare: String, satName: String): String {
        fun field(name: String, value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8).size
            return "<$name:$bytes>$value"
        }
        return buildString {
            append(field("call", qso.call))
            append(field("band", "SAT"))
            append(field("mode", qso.mode))
            append(field("freq", String.format(Locale.ENGLISH, "%.6f", qso.freqTxHz / 1_000_000.0)))
            if (qso.freqRxHz > 0) {
                append(field("freq_rx", String.format(Locale.ENGLISH, "%.6f", qso.freqRxHz / 1_000_000.0)))
            }
            append(field("qso_date", utcDateCompact(qso.timeUtcMs)))
            append(field("time_on", utcTimeCompact(qso.timeUtcMs)))
            append(field("rst_sent", "59"))
            append(field("rst_rcvd", "59"))
            if (gridsquare.isNotBlank()) append(field("gridsquare", gridsquare.take(4)))
            if (satName.isNotBlank()) {
                append(field("sat_name", satName))
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
