package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Pins the grid the AMSAT status page draws.
 *
 * Two contracts matter. The day cell renders one stripe per slot, so "every day has
 * exactly 12 slots, newest first" became load-bearing. And the day columns are UTC
 * calendar days, so a report must land in the cell whose label matches its UTC date - an
 * earlier rolling window anchored on "now" put 17.9 hours of yesterday into the cell
 * labelled today, and 73% of a live 1021-report page landed in the wrong column.
 *
 * This drives [AmSatRepository.buildStatuses] directly rather than `fetchStatus`, because
 * the parsing around it uses Android's `JSONObject`, a stub on the JVM: a `fetchStatus`
 * test returns null for every input and proves nothing.
 */
class AmSatSlotBuildTest {

    private object UnusedSource : IRemoteSource {
        override suspend fun getFileStream(uri: String): InputStream? = null
        override suspend fun getNetworkStream(url: String): InputStream? = null
        override suspend fun getAmSatCatalog(): String? = null
        override suspend fun getAmSatReports(hours: Int, limit: Int): String? = null
        override suspend fun getAmSatSummary(hours: Int): String? = null
    }

    private val repo = AmSatRepository(UnusedSource)

    /** Epoch seconds for a UTC wall-clock instant, so every case reads unambiguously. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis / 1000
    }

    /** Midday, so "today" has hours on both sides of the fetch. */
    private val nowSec = utc(2026, 8, 22, 12)

    private fun report(name: String, status: String, at: Long, id: String = "r-$name-$at") =
        ApiReport(
            id = id,
            name = name,
            callsign = "TEST",
            report = status,
            gridSquare = "AA00",
            reportedTimeUtcSec = at
        )

    private fun build(names: List<String>, reports: List<ApiReport>) =
        repo.buildStatuses(names, reports, nowSec)

    @Test
    fun `every day carries exactly twelve slots`() {
        val statuses = build(
            listOf("AO-91", "SO-50", "ISS"),
            listOf(report("AO-91", "heard", utc(2026, 8, 22, 11)))
        )
        assertEquals(3, statuses.size)
        for (status in statuses) {
            assertEquals("${status.name} must have 3 days", 3, status.days.size)
            for (day in status.days) {
                assertEquals(
                    "${status.name} ${day.dateLabel} must have 12 slots for the stripe renderer",
                    12, day.slots.size
                )
            }
        }
    }

    @Test
    fun `a satellite nobody reported still gets twelve slots per day`() {
        // The renderer must never receive an empty list, which would draw nothing at all.
        val status = build(listOf("QUIET-1"), emptyList()).single()
        assertEquals(3, status.days.size)
        status.days.forEach { assertEquals(12, it.slots.size) }
        assertTrue(
            "a silent satellite must be all no-report slots",
            status.days.all { day -> day.slots.all { it.count == 0 } }
        )
    }

    @Test
    fun `days are labelled with UTC calendar dates`() {
        val status = build(listOf("AO-91"), emptyList()).single()
        assertEquals("today", "Aug 22", status.days[0].dateLabel)
        assertEquals("yesterday", "Aug 21", status.days[1].dateLabel)
        assertEquals("the day before", "Aug 20", status.days[2].dateLabel)
    }

    @Test
    fun `the label does not drift with the time of day`() {
        // The old rolling window relabelled the same data depending on when it was
        // fetched. A calendar day must not care.
        for (hour in listOf(0, 6, 12, 18, 23)) {
            val labels = repo.buildStatuses(listOf("AO-91"), emptyList(), utc(2026, 8, 22, hour))
                .single().days.map { it.dateLabel }
            assertEquals("fetched at ${hour}:00 UTC", listOf("Aug 22", "Aug 21", "Aug 20"), labels)
        }
    }

    @Test
    fun `slots cover fixed UTC bands, newest first`() {
        // Slot 0 is 22:00-24:00 and slot 11 is 00:00-02:00, matching amsat.org.
        val status = build(
            listOf("AO-91"),
            listOf(
                report("AO-91", "heard", utc(2026, 8, 22, 23), id = "lateToday"),
                report("AO-91", "not heard", utc(2026, 8, 22, 1), id = "earlyToday")
            )
        ).single()
        val today = status.days[0]

        assertTrue(
            "23:00 belongs in slot 0, the day's last band",
            "lateToday" in today.slots[0].reportIds
        )
        assertTrue(
            "01:00 belongs in slot 11, the day's first band",
            "earlyToday" in today.slots[11].reportIds
        )
    }

    @Test
    fun `a report lands in the day matching its UTC date`() {
        val status = build(
            listOf("AO-91"),
            listOf(
                report("AO-91", "heard", utc(2026, 8, 22, 11), id = "today"),
                report("AO-91", "heard", utc(2026, 8, 21, 15), id = "yesterday"),
                report("AO-91", "heard", utc(2026, 8, 20, 5), id = "dayBefore")
            )
        ).single()

        // Positions computed from the UTC bands: 11:00 -> slot 6, 15:00 -> slot 4,
        // 05:00 -> slot 9.
        assertTrue("today's report", "today" in status.days[0].slots[6].reportIds)
        assertTrue("yesterday's report", "yesterday" in status.days[1].slots[4].reportIds)
        assertTrue("the day before", "dayBefore" in status.days[2].slots[9].reportIds)
    }

    @Test
    fun `a report just after midnight stays in the new day`() {
        // The boundary the rolling window got wrong: 00:30 today must not appear as
        // yesterday.
        val status = build(
            listOf("AO-91"),
            listOf(report("AO-91", "heard", utc(2026, 8, 22, 0, 30), id = "justAfterMidnight"))
        ).single()

        assertTrue(
            "00:30 belongs to today's first band",
            "justAfterMidnight" in status.days[0].slots[11].reportIds
        )
        assertTrue(
            "yesterday must stay empty",
            status.days[1].slots.all { it.count == 0 }
        )
    }

    @Test
    fun `each status maps to its own colour`() {
        // The stripes are now the only carrier of status, so distinct states must stay
        // distinct all the way out of the repository.
        val at = utc(2026, 8, 22, 11)
        val statuses = build(
            listOf("A", "B", "C", "D"),
            listOf(
                report("A", "heard", at),
                report("B", "telemetry only", at),
                report("C", "not heard", at),
                report("D", "something the api invented", at)
            )
        )
        val colours = statuses.map { status -> status.days[0].slots[6].statusColor }
        assertTrue("no state may be colourless", colours.none { it == 0L })
        assertEquals(
            "heard, telemetry and not heard must be visually distinct",
            3, colours.take(3).toSet().size
        )
    }

    @Test
    fun `a slot keeps every report it contains`() {
        // The tap dialog lists reports from the slots, so none may be dropped when several
        // land in the same two-hour window. 10:00-12:00 is slot 6.
        val status = build(
            listOf("AO-91"),
            listOf(
                report("AO-91", "heard", utc(2026, 8, 22, 10, 15), id = "a"),
                report("AO-91", "heard", utc(2026, 8, 22, 11, 0), id = "b"),
                report("AO-91", "not heard", utc(2026, 8, 22, 11, 45), id = "c")
            )
        ).single()
        val slot = status.days[0].slots[6]
        assertEquals("all three reports fall in the same band", 3, slot.count)
        assertEquals(setOf("a", "b", "c"), slot.reportIds.toSet())
    }

    @Test
    fun `a slot shows the newest status when reports disagree`() {
        // Within one band the most recent observation wins; anything else would keep
        // showing a failure after the satellite recovered.
        fun colourFor(firstStatus: String, secondStatus: String): Long = build(
            listOf("AO-91"),
            listOf(
                report("AO-91", firstStatus, utc(2026, 8, 22, 10, 15), id = "older"),
                report("AO-91", secondStatus, utc(2026, 8, 22, 11, 45), id = "newer")
            )
        ).single().days[0].slots[6].statusColor

        assertTrue(
            "the slot colour must follow the newest report, not the first",
            colourFor("not heard", "heard") != colourFor("heard", "not heard")
        )
    }

    @Test
    fun `reports outside the three-day window are ignored`() {
        val status = build(
            listOf("AO-91"),
            listOf(
                report("AO-91", "heard", utc(2026, 8, 18, 12), id = "tooOld"),
                report("AO-91", "heard", utc(2026, 8, 23, 12), id = "future")
            )
        ).single()
        assertTrue(
            "nothing outside the window may appear",
            status.days.all { day -> day.slots.all { it.count == 0 } }
        )
    }

    @Test
    fun `reports for other satellites do not leak between rows`() {
        val statuses = build(
            listOf("AO-91", "SO-50"),
            listOf(report("AO-91", "heard", utc(2026, 8, 22, 11), id = "onlyAo91"))
        )
        val ao91 = statuses.first { it.name == "AO-91" }
        val so50 = statuses.first { it.name == "SO-50" }

        assertEquals("AO-91 has its report", 1, ao91.days[0].slots[6].count)
        assertTrue(
            "SO-50 must stay empty",
            so50.days.all { day -> day.slots.all { it.count == 0 } }
        )
    }

    @Test
    fun `an empty catalog yields no rows rather than a malformed grid`() {
        assertTrue(build(emptyList(), emptyList()).isEmpty())
    }

    /**
     * Slots older than the data we received must not claim nobody was listening.
     *
     * The API caps at 500 records however many hours are asked for. Measured live, a
     * 72-hour request returned 500 reports covering only 49 hours, so the oldest 9.5 hours
     * of the third day had no data at all - 352 of 3168 cells were painting "nobody heard
     * it" over "we never looked".
     */
    @Test
    fun `slots before the data starts are marked no-data, not no-report`() {
        // The only report is midday yesterday, so nothing older than that was covered.
        val oldestReport = utc(2026, 8, 21, 12)
        val status = build(
            listOf("AO-91"),
            listOf(report("AO-91", "heard", oldestReport, id = "only"))
        ).single()

        val noReport = 0xFFC0C0C0
        val noData = 0xFFE8E8E8

        // The day before yesterday is entirely before the data begins.
        assertTrue(
            "every slot older than the data must read as no-data",
            status.days[2].slots.all { it.statusColor == noData }
        )

        // Yesterday straddles it: bands after midday are covered, bands before are not.
        val yesterday = status.days[1]
        assertEquals("the report's own band", 1, yesterday.slots[5].count)
        assertTrue(
            "bands after the oldest report are covered, so silence there is real",
            yesterday.slots.take(6).all { it.statusColor != noData }
        )
        assertTrue(
            "the earliest band of yesterday is before any data",
            yesterday.slots[11].statusColor == noData
        )

        // Today is entirely after the data starts, so its silence is genuine.
        assertTrue(
            "today's empty slots mean nobody reported",
            status.days[0].slots.all { it.statusColor == noReport }
        )
    }

    @Test
    fun `coverage is judged from all reports, not one satellite's`() {
        // A satellite nobody reported must not show as no-data for the whole grid: the
        // slots were covered, that satellite simply was not heard.
        val statuses = build(
            listOf("LOUD", "QUIET"),
            listOf(report("LOUD", "heard", utc(2026, 8, 20, 1), id = "early"))
        )
        val quiet = statuses.first { it.name == "QUIET" }
        val noData = 0xFFE8E8E8

        assertTrue(
            "coverage reaches back to the earliest report of any satellite",
            quiet.days.all { day -> day.slots.none { it.statusColor == noData } }
        )
    }

    /**
     * A report whose timestamp failed to parse must not disable the distinction.
     *
     * parseIsoUtcSec returns 0 for an unparseable reported_time, and coverage is the
     * minimum timestamp in the response - so one such record would put the coverage
     * boundary in 1970 and mark every slot as reported-on. Measured on a grid that should
     * have had 18 no-data cells, a single zero timestamp took it to none.
     */
    @Test
    fun `a report with an unparseable timestamp does not disable the no-data marking`() {
        val noData = 0xFFE8E8E8
        val realReport = report("AO-91", "heard", utc(2026, 8, 21, 12), id = "real")
        val brokenTimestamp = ApiReport(
            id = "broken",
            name = "AO-91",
            callsign = "TEST",
            report = "heard",
            gridSquare = "AA00",
            reportedTimeUtcSec = 0L
        )

        val withoutBroken = build(listOf("AO-91"), listOf(realReport))
            .single().days.sumOf { day -> day.slots.count { it.statusColor == noData } }
        val withBroken = build(listOf("AO-91"), listOf(realReport, brokenTimestamp))
            .single().days.sumOf { day -> day.slots.count { it.statusColor == noData } }

        assertTrue("the baseline must have uncovered slots to compare", withoutBroken > 0)
        assertEquals(
            "a zero timestamp must not change what counts as covered",
            withoutBroken, withBroken
        )
    }

    @Test
    fun `an empty response marks nothing as covered`() {
        // With no reports at all there is no evidence about any slot.
        val status = build(listOf("AO-91"), emptyList()).single()
        val noData = 0xFFE8E8E8
        assertTrue(
            "yesterday and earlier cannot be claimed as silent",
            status.days.drop(1).all { day -> day.slots.all { it.statusColor == noData } }
        )
    }
}
