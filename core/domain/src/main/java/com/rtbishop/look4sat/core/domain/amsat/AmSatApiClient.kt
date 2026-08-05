/* AmSatApiClient.kt - AMSAT official status API v1 client (pure JVM).
 * Endpoints (verified 2026-08):
 *   GET https://www.amsat.org/status/api/v1/catalog.php          -> satellite list
 *   GET https://www.amsat.org/status/api/v1/reports.php?hours=N&limit=500 -> reports
 * Report fields: id, name ("SO-50_[FM]"), callsign, report, grid_square, reported_time (ISO 8601 UTC).
 * Status values: Heard / Telemetry Only / Not Heard / Crew Active.
 */
package com.rtbishop.look4sat.core.domain.amsat

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** One report from the AMSAT API. */
data class ApiReport(
    val id: String,
    val name: String,        // "SO-50_[FM]" (API name, includes mode suffix)
    val callsign: String,
    val report: String,      // Heard / Telemetry Only / Not Heard / Crew Active
    val gridSquare: String,
    val reportedTimeUtcSec: Long
)

/** AMSAT official satellite status API v1 client. */
class AmSatApiClient(private val baseUrl: String = "https://www.amsat.org/status/api/v1") {

    /** Fetch the full satellite catalog. Returns API names (e.g. "SO-50_[FM]"). */
    fun fetchCatalog(): List<String> {
        val body = httpGet("$baseUrl/catalog.php") ?: return emptyList()
        return try {
            val arr = JSONObject(body).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Fetch reports for a rolling UTC window. Empty on failure. */
    fun fetchAllReports(hours: Int = 168): List<ApiReport> {
        val body = httpGet("$baseUrl/reports.php?hours=$hours&limit=500") ?: return emptyList()
        return try {
            val arr = JSONObject(body).getJSONArray("data")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val iso = o.optString("reported_time", "")
                if (iso.isEmpty()) null else ApiReport(
                    id = o.optString("id", ""),
                    name = o.optString("name", ""),
                    callsign = o.optString("callsign", ""),
                    report = o.optString("report", ""),
                    gridSquare = o.optString("grid_square", ""),
                    reportedTimeUtcSec = parseIsoUtcSec(iso)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Parse "2026-08-05T07:30:00Z" to epoch seconds (minSdk 24: no java.time). */
    fun parseIsoUtcSec(iso: String): Long {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(iso) ?: return 0L
        val (y, mo, d, h, mi, s) = m.destructured
        val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
        return days * 86400L + h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()
    }

    /** Days since 1970-01-01 (civil calendar, proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Look4Sat/4.5.5")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            text
        } catch (e: Exception) {
            null
        }
    }
}
