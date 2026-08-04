/*
 * WaveLogApi.kt — WaveLog 日志服务器 API v2 客户端(4.5.2, 覆盖修复)。
 *
 * 端点(文档 /temporary/wavelog_docs/docs/developer/api-v2/):
 *   GET  {base}/api/v2/token            whoami(测试连接,任意有效 token)
 *   GET  {base}/api/v2/station/{id}     站点信息(id/name/gridsquare)
 *   POST {base}/api/v2/qso              创建 QSO(qso:write)
 * 认证: Authorization: Bearer wl2_xxx(v2 token)
 *
 * URL 处理(用户实测 404 修复):
 *   1. normalizeUrl 去掉用户输入的尾部 /index.php(我们自己拼, 避免重复)
 *   2. 先试带 index.php 的路径; 404 时自动回退到不带 index.php(服务器重写配置)
 *   3. 409 conflict = 重复 QSO(已存在) → 视为成功(移出队列)
 *   4. 失败信息带实际 URL + HTTP 码, 便于排查
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

    /** 规范化服务器地址: 去尾斜杠/去尾部 index.php; 无协议前缀时补 https:// */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        if (u.endsWith("/index.php")) u = u.removeSuffix("/index.php")
        return u
    }

    /** 测试连接: GET /api/v2/token */
    suspend fun testToken(url: String, apiKey: String): WavelogResult = withContext(Dispatchers.IO) {
        execute(
            url = url,
            apiKey = apiKey,
            relPath = "api/v2/token",
            method = "GET",
            onSuccess = { _ -> WavelogResult.Success("连接成功") },
            onFailure = { code, body, usedUrl ->
                WavelogResult.Failure("HTTP $code: ${shortError(body)} ($usedUrl)")
            }
        )
    }

    /** 站点信息: GET /api/v2/station/{id} */
    suspend fun getStation(url: String, apiKey: String, stationId: String): WavelogResult = withContext(Dispatchers.IO) {
        execute(
            url = url,
            apiKey = apiKey,
            relPath = "api/v2/station/$stationId",
            method = "GET",
            onSuccess = { body ->
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
            },
            onFailure = { code, body, usedUrl ->
                WavelogResult.Failure("HTTP $code: ${shortError(body)} ($usedUrl)")
            }
        )
    }

    /** 创建 QSO: POST /api/v2/qso(单条 JSON) */
    suspend fun postQso(
        url: String,
        apiKey: String,
        stationProfileId: String,
        qso: WavelogQso,
        gridsquare: String
    ): WavelogResult = withContext(Dispatchers.IO) {
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
        execute(
            url = url,
            apiKey = apiKey,
            relPath = "api/v2/qso",
            method = "POST",
            jsonBody = body.toString(),
            onSuccess = { _ -> WavelogResult.Success("已上传") },
            onFailure = { code, resp, usedUrl ->
                if (code == 409) {
                    // 重复 QSO(已存在) → 视为成功, 移出队列
                    WavelogResult.Success("重复(已存在)")
                } else {
                    WavelogResult.Failure("HTTP $code: ${shortError(resp)} ($usedUrl)")
                }
            }
        )
    }

    /**
     * 统一请求执行: 先试带 index.php 的路径; 404 时回退到不带 index.php。
     * 返回 2xx/409 以外的状态都交给 onFailure(带实际使用的 URL)。
     */
    private fun execute(
        url: String,
        apiKey: String,
        relPath: String,
        method: String,
        jsonBody: String? = null,
        onSuccess: (String) -> WavelogResult,
        onFailure: (Int, String, String) -> WavelogResult
    ): WavelogResult {
        val base = normalizeUrl(url)
        if (base.isBlank()) return WavelogResult.Failure("服务器地址为空")

        // 候选路径: 带 index.php → 不带(服务器重写配置)
        val candidates = listOf(
            "$base/index.php/$relPath",
            "$base/$relPath"
        )
        var lastError: WavelogResult? = null
        for (candidate in candidates) {
            try {
                val conn = openConn(candidate, method, apiKey)
                if (jsonBody != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
                }
                val code = conn.responseCode
                val resp = readBody(conn, code)
                if (code in 200..299) return onSuccess(resp)
                if (code == 409) return onFailure(code, resp, candidate) // 409 特殊处理在调用方
                if (code == 404) {
                    // 路径不存在 → 试下一个候选(去掉 index.php)
                    lastError = WavelogResult.Failure("HTTP 404: ${shortError(resp)} ($candidate)")
                    continue
                }
                return onFailure(code, resp, candidate)
            } catch (e: Exception) {
                lastError = WavelogResult.Failure("网络错误: ${e.message ?: e.javaClass.simpleName}")
                return lastError // 网络错误重试没意义
            }
        }
        return lastError ?: WavelogResult.Failure("请求失败")
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
            val err = obj.optJSONObject("error")
            err?.optString("message")?.ifBlank { body.take(120) }
                ?: obj.optString("message").ifBlank { body.take(120) }
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
