/*
 * WaveLogApi.kt — WaveLog 日志服务器 API v2 客户端(4.5.2)。
 *
 * 端点(文档 /temporary/wavelog_docs/docs/developer/api-v2/):
 *   GET  {url}/index.php/api/v2/token            whoami(测试连接,任意有效 token)
 *   GET  {url}/index.php/api/v2/station/{id}     站点信息(id/name/gridsquare)
 *   POST {url}/index.php/api/v2/qso              创建 QSO(qso:write)
 * 认证: Authorization: Bearer wl2_xxx(v2 token)
 *
 * 轻量实现: HttpURLConnection + 协程(Dispatchers.IO),不引新依赖。
 */
package com.rtbishop.look4sat.core.domain.wavelog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** 站点信息(GET /api/v2/station/{id} 结果) */
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

    /** 规范化服务器地址: 去尾斜杠; 无协议前缀时补 https:// */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    /** 测试连接: GET /index.php/api/v2/token */
    suspend fun testToken(url: String, apiKey: String): WavelogResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn("${normalizeUrl(url)}/index.php/api/v2/token", "GET", apiKey)
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code in 200..299) {
                // 返回当前 token 元数据(有 type/name 等)
                WavelogResult.Success("连接成功")
            } else {
                WavelogResult.Failure("HTTP $code: ${shortError(body)}")
            }
        } catch (e: Exception) {
            WavelogResult.Failure("网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 站点信息: GET /index.php/api/v2/station/{id} */
    suspend fun getStation(url: String, apiKey: String, stationId: String): WavelogResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn("${normalizeUrl(url)}/index.php/api/v2/station/$stationId", "GET", apiKey)
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code in 200..299) {
                val obj = JSONObject(body)
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
            } else {
                WavelogResult.Failure("HTTP $code: ${shortError(body)}")
            }
        } catch (e: Exception) {
            WavelogResult.Failure("网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 创建 QSO: POST /index.php/api/v2/qso(单条 JSON) */
    suspend fun postQso(
        url: String,
        apiKey: String,
        stationProfileId: String,
        qso: WavelogQso,
        gridsquare: String
    ): WavelogResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("station_profile_id", stationProfileId.toIntOrNull() ?: 0)
                put("call", qso.call)
                put("band", "SAT")
                put("mode", qso.mode)
                put("qso_date", utcDate(qso.timeUtcMs))
                put("time_on", utcTime(qso.timeUtcMs))
                put("freq", qso.freqTxHz)
                put("freq_rx", qso.freqRxHz)
                put("gridsquare", gridsquare)
                put("sat_name", qso.satName)
            }
            val conn = openConn("${normalizeUrl(url)}/index.php/api/v2/qso", "POST", apiKey)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val resp = readBody(conn, code)
            if (code in 200..299) {
                WavelogResult.Success("已上传")
            } else {
                WavelogResult.Failure("HTTP $code: ${shortError(resp)}")
            }
        } catch (e: Exception) {
            WavelogResult.Failure("网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openConn(url: String, method: String, apiKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun shortError(body: String): String {
        return try {
            val obj = JSONObject(body)
            obj.optString("message").ifBlank { body.take(120) }
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
}
