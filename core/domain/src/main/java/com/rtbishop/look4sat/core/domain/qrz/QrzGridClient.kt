/* QrzGridClient.kt — QRZ 呼号网格爬虫(4.5.5, domain 层纯 JVM)。
 * 原理(已实测): 带用户 QRZ 登录 Cookie GET https://www.qrz.com/db/{呼号},
 * 页面 Detail 表格里有 <td class="dh">Grid Square</td><td class="di">XXX</td>。
 * 匿名无 Cookie 拿不到 Detail(已确认); 对方没填网格时表格无此行(返回 null)。
 * Cookie 由用户在设置页粘贴(EditThisCookie 导出的 JSON 数组 或 原始 Cookie 串),
 * 绝不内置/进构建。
 */
package com.rtbishop.look4sat.core.domain.qrz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object QrzGridClient {

    /** 解析用户粘贴的 Cookie(兼容两种格式): EditThisCookie JSON 数组 或 原始 "k=v; k=v" 串 */
    fun parseCookies(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // JSON 数组格式: [{"name":"qz_userid","value":"1266043",...}, ...]
        if (text.startsWith("[")) {
            return try {
                val arr = org.json.JSONArray(text)
                val parts = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name")
                    val value = o.optString("value")
                    if (name.isNotBlank()) parts.add("$name=$value")
                }
                parts.joinToString("; ")
            } catch (_: Exception) { text }
        }
        return text
    }

    /** 检测 cookies 对应的登录呼号(请求 db 首页, 提取账号菜单呼号)。失败返回 null */
    suspend fun fetchOwnCallsign(cookieHeader: String): String? = withContext(Dispatchers.IO) {
        if (cookieHeader.isBlank()) return@withContext null
        try {
            val url = URL("https://www.qrz.com/db/")
            val conn = url.openConnection()
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Look4Sat-Pro")
            conn.setRequestProperty("Cookie", cookieHeader)
            val html = conn.getInputStream().bufferedReader().use { it.readText() }
            // 登录账号菜单(实测结构): <li class="leaf last" onclick="return true">BG7NTA <ul class="sub">
            val pattern = Regex("<li class=\"leaf last\"[^>]*>\\s*([A-Z0-9/]+)\\s*<ul")
            pattern.find(html)?.groupValues?.get(1)?.trim()
        } catch (_: Exception) { null }
    }

    /** 查询呼号的网格。返回 null = 未填/查不到(静默, 不阻塞录入) */
    suspend fun lookupGrid(callsign: String, cookieHeader: String): String? = withContext(Dispatchers.IO) {
        if (callsign.isBlank() || cookieHeader.isBlank()) return@withContext null
        try {
            val url = URL("https://www.qrz.com/db/${callsign.trim().uppercase()}")
            val conn = url.openConnection()
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Look4Sat-Pro")
            conn.setRequestProperty("Cookie", cookieHeader)
            val html = conn.getInputStream().bufferedReader().use { it.readText() }
            // Detail 表格 Grid Square 行(已实测格式)
            val m = Regex("""<td class="dh">Grid Square</td><td class="di">([^<]+)</td>""")
                .find(html)
            m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
