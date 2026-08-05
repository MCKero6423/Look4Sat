/* QrzGridClient.kt - QRZ callsign grid scraper (4.5.5, pure JVM in domain).
 * How it works (verified): GET https://www.qrz.com/db/{callsign} with the user's QRZ login Cookie,
 * the Detail table on the page holds <td class="dh">Grid Square</td><td class="di">XXX</td>.
 * Without cookies the Detail is unavailable (confirmed); if the other station has no grid, the row is absent (null).
 * The Cookie is pasted by the user in settings (EditThisCookie JSON export or a raw cookie string),
 * never built into the app.
 */
package com.rtbishop.look4sat.core.domain.qrz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object QrzGridClient {

    /** Parse the pasted Cookie (both formats): EditThisCookie JSON array or raw "k=v; k=v" string */
    fun parseCookies(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // JSON array format: [{"name":"qz_userid","value":"1266043",...}, ...]
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

    /** Detect the callsign logged in with these cookies (fetch db home, extract the account menu callsign). Null on failure */
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
            // Logged-in account menu (verified): <li class="leaf last" onclick="return true">BG7NTA <ul class="sub">
            val pattern = Regex("<li class=\"leaf last\"[^>]*>\\s*([A-Z0-9/]+)\\s*<ul")
            pattern.find(html)?.groupValues?.get(1)?.trim()
        } catch (_: Exception) { null }
    }

    /** Look up a callsign's grid. Null = not set / not found (silent, does not block logging) */
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
            // Detail table Grid Square row (verified format)
            val m = Regex("""<td class="dh">Grid Square</td><td class="di">([^<]+)</td>""")
                .find(html)
            m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
