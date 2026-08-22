package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatDay
import com.rtbishop.look4sat.core.domain.model.SatReport
import com.rtbishop.look4sat.core.domain.model.SatSlot
import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.model.SatStatusPage
import com.rtbishop.look4sat.core.domain.repository.IAmSatRepository
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * One report from the AMSAT API (data layer model).
 *
 * Internal rather than private so [AmSatRepository.buildStatuses] can be unit-tested:
 * the JSON parsing around it needs Android's JSONObject, which is a stub on the JVM.
 */
internal data class ApiReport(
    val id: String,
    val name: String,
    val callsign: String,
    val report: String,
    val gridSquare: String,
    val reportedTimeUtcSec: Long
)

/** AMSAT status repository using RemoteSource (Clean Architecture: data layer handles HTTP). */
class AmSatRepository(private val remoteSource: IRemoteSource) : IAmSatRepository {

    private val isoUtcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun fetchStatus(): SatStatusPage? = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000
        val catalogJson = remoteSource.getAmSatCatalog() ?: return@withContext null
        // 72h = 3 days; API hard cap is limit=500 regardless of what we send.
        // 500 records across ~100 catalog satellites ≈ ~1-5 reports/satellite/day — enough for 3 days.
        // Upgrade path: paginate or request AMSAT to raise the cap if catalog grows beyond ~200 sats.
        val reportsJson = remoteSource.getAmSatReports(hours = 72, limit = 500) ?: return@withContext null
        val names = parseCatalog(catalogJson)
        val reports = parseReports(reportsJson)

        if (names.isEmpty() && reports.isEmpty()) return@withContext null

        val statuses = buildStatuses(names, reports, nowSec)
        val reportMap = reports.associate { it.id to toSatReport(it) }

        // The summary endpoint tells us how many reports each satellite actually has,
        // independent of the 500-record cap. Mark any satellite whose global pull is
        // incomplete so the UI can show a data-coverage note.
        val summaryJson = remoteSource.getAmSatSummary(hours = 72)
        val expectedCounts = parseSummary(summaryJson)
        val marked = statuses.map { status ->
            val expected = expectedCounts[status.name]
            val actual = status.days.sumOf { day -> day.slots.sumOf { it.count } }
            if (expected != null && expected > actual) status.copy(summaryCount = expected)
            else status
        }

        SatStatusPage(System.currentTimeMillis(), marked, reportMap)
    }

    /** Parse catalog JSON to list of satellite names */
    private fun parseCatalog(json: String): List<String> {
        return try {
            val arr = JSONObject(json).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Parse reports JSON to list of ApiReport domain objects */
    private fun parseReports(json: String): List<ApiReport> {
        return try {
            val arr = JSONObject(json).getJSONArray("data")
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parse summary JSON to per-satellite report counts.
     *
     * The summary aggregates across all statuses, so a satellite with both "heard" and
     * "not heard" entries appears once; we sum its report_count across all its rows.
     * Returns an empty map (not null) on failure so the caller can just check for
     * missing keys — a failed summary call degrades gracefully to "no coverage marker".
     */
    private fun parseSummary(json: String?): Map<String, Int> {
        if (json == null) return emptyMap()
        return try {
            val arr = JSONObject(json).getJSONArray("data")
            val out = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                val count = o.optInt("report_count", 0)
                if (name.isNotEmpty() && count > 0) {
                    out[name] = (out[name] ?: 0) + count
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Parse ISO 8601 UTC timestamp to epoch seconds (e.g., "2026-08-05T07:30:00Z") */
    private fun parseIsoUtcSec(iso: String): Long {
        return try {
            (isoUtcFormat.parse(iso)?.time ?: 0L) / 1000
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Build one SatStatus (3 days x 12 two-hour slots) per catalog satellite.
     *
     * Days are UTC calendar days and slots are fixed UTC bands, matching amsat.org: day 0
     * is today, its slot 0 covers 22:00-24:00 UTC and slot 11 covers 00:00-02:00, so both
     * the day list and the slots inside it read newest-first.
     *
     * A rolling window anchored on "now" was wrong: fetching at 06:07 UTC put 17.9 hours
     * of yesterday into the cell labelled today. Checked against a live amsat.org page of
     * 1021 reports, 73% landed in the wrong day column.
     */
    internal fun buildStatuses(names: List<String>, reports: List<ApiReport>, nowSec: Long): List<SatStatus> {
        val byName = reports.groupBy { it.name }
        val monthAbbr = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        // Midnight UTC today, the anchor every slot boundary is derived from.
        utc.timeInMillis = nowSec * 1000
        utc.set(Calendar.HOUR_OF_DAY, 0)
        utc.set(Calendar.MINUTE, 0)
        utc.set(Calendar.SECOND, 0)
        utc.set(Calendar.MILLISECOND, 0)
        val todayMidnightSec = utc.timeInMillis / 1000

        // Reuses the same Calendar, which is safe only because each pass assigns
        // timeInMillis outright rather than adjusting fields. After this loop it points at
        // the oldest day, so anything added below must set the time again before reading.
        val labels = (0 until 3).map { d ->
            utc.timeInMillis = (todayMidnightSec - d * 86400L) * 1000
            "${monthAbbr[utc.get(Calendar.MONTH)]} ${utc.get(Calendar.DAY_OF_MONTH)}"
        }
        // Oldest report across the whole response, marking how far back the data reaches.
        // Taken globally rather than per satellite: a quiet satellite has no reports of its
        // own, but the slots it shares with the rest of the response were still covered.
        //
        // Timestamps of zero are excluded: parseIsoUtcSec returns 0 when a reported_time
        // fails to parse, and a single such record would drag this back to 1970 and mark
        // nothing as uncovered, silently reverting the distinction.
        val dataFromSec = reports.asSequence()
                .map { it.reportedTimeUtcSec }
                .filter { it > 0L }
                .minOrNull()
                ?: todayMidnightSec

        return names.map { name ->
            val satReports = byName[name].orEmpty()
            val days = (0 until 3).map { dayIdx ->
                val dayStart = todayMidnightSec - dayIdx * 86400L
                val slots = (0 until 12).map { slotIdx ->
                    // Slot 0 is the last band of the day, so the day reads newest-first.
                    val slotStart = dayStart + (11 - slotIdx) * 7200L
                    val slotEnd = slotStart + 7200L
                    val inSlot = satReports.filter { it.reportedTimeUtcSec in slotStart until slotEnd }
                    if (inSlot.isEmpty()) {
                        // A slot entirely before the data starts is unknown, not silent.
                        val colour = if (slotEnd <= dataFromSec) NO_DATA_GRAY else NO_REPORT_GRAY
                        SatSlot(statusColor = colour, count = 0)
                    } else {
                        val newest = inSlot.maxByOrNull { it.reportedTimeUtcSec }!!
                        SatSlot(
                            statusColor = statusColorOf(newest.report),
                            count = inSlot.size,
                            reportIds = inSlot.map { it.id }
                        )
                    }
                }
                SatDay(dateLabel = labels[dayIdx], slots = slots)
            }
            SatStatus(name = name, days = days)
        }
    }

    private fun toSatReport(r: ApiReport): SatReport {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = r.reportedTimeUtcSec * 1000
        val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val y = cal.get(Calendar.YEAR)
        val mo = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return SatReport(
            id = r.id,
            statusText = r.report,
            call = r.callsign,
            grid = r.gridSquare,
            dateUtc = "$y-$mo-$d",
            timeUtc = "$hh:$mm UTC"
        )
    }

    /** Map status text to color value (for UI rendering). */
    private fun statusColorOf(report: String): Long = when (report.lowercase()) {
        "heard", "crew active" -> ACTIVE_BLUE
        "telemetry only" -> TLM_ORANGE
        "not heard" -> NOT_HEARD_PINK
        else -> CONFLICT_DEEP_ORANGE
    }

    companion object {
        // AMSAT official status colors (from amsat.org/status)
        private const val ACTIVE_BLUE = 0xFF648FFF
        private const val TLM_ORANGE = 0xFFFFB000
        private const val NOT_HEARD_PINK = 0xFFDC267F
        private const val CONFLICT_DEEP_ORANGE = 0xFFFE6100
        private const val NO_REPORT_GRAY = 0xFFC0C0C0

        /**
         * Slots older than the data we actually received.
         *
         * The API caps at 500 records however many hours are requested. Measured live: a
         * 72-hour request returned 500 reports spanning only 49 hours, leaving the oldest
         * 9.5 hours of the third day with no data at all. Painting those the same grey as
         * "nobody reported" claimed knowledge we do not have, so they get a lighter shade.
         */
        private const val NO_DATA_GRAY = 0xFFE8E8E8
    }
}
