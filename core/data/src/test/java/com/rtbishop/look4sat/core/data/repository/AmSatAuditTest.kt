package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * ADVERSARIAL AUDIT SCRATCH FILE - delete when the audit report is written.
 * Probes buildStatuses for aliasing, midnight arithmetic and boundary defects.
 */
class AmSatAuditTest {

    private object UnusedSource : IRemoteSource {
        override suspend fun getFileStream(uri: String): InputStream? = null
        override suspend fun getNetworkStream(url: String): InputStream? = null
        override suspend fun getAmSatCatalog(): String? = null
        override suspend fun getAmSatReports(hours: Int, limit: Int): String? = null
    }

    private val repo = AmSatRepository(UnusedSource)

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y, mo - 1, d, h, mi, s)
        return c.timeInMillis / 1000
    }

    private fun rep(name: String, at: Long, id: String, status: String = "heard") =
        ApiReport(id, name, "T", status, "AA00", at)

    private fun labelsAt(now: Long) =
        repo.buildStatuses(listOf("X"), emptyList(), now).single().days.map { it.dateLabel }

    /** Reference: the label a UTC instant's day should carry. */
    private fun expectLabel(y: Int, mo: Int, d: Int): String {
        val mn = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
            "Sep", "Oct", "Nov", "Dec")
        return "${mn[mo - 1]} $d"
    }

    // ---------- 1. midnight arithmetic under hostile inputs ----------

    @Test
    fun auditMidnightExactlyAtMidnight() {
        assertEquals(
            listOf(expectLabel(2026, 8, 22), expectLabel(2026, 8, 21), expectLabel(2026, 8, 20)),
            labelsAt(utc(2026, 8, 22, 0, 0, 0))
        )
    }

    @Test
    fun auditMidnightOneSecondBeforeAndAfter() {
        assertEquals(
            "23:59:59 on Aug 21 must still be Aug 21",
            listOf("Aug 21", "Aug 20", "Aug 19"),
            labelsAt(utc(2026, 8, 21, 23, 59, 59))
        )
        assertEquals(
            "00:00:01 on Aug 22 must already be Aug 22",
            listOf("Aug 22", "Aug 21", "Aug 20"),
            labelsAt(utc(2026, 8, 22, 0, 0, 1))
        )
    }

    @Test
    fun auditLeapDay2028() {
        assertEquals(
            "Feb 29 2028 back to Feb 27",
            listOf("Feb 29", "Feb 28", "Feb 27"),
            labelsAt(utc(2028, 2, 29, 12))
        )
        assertEquals(
            "Mar 1 2028 must reach back through the leap day",
            listOf("Mar 1", "Feb 29", "Feb 28"),
            labelsAt(utc(2028, 3, 1, 0, 0, 0))
        )
        assertEquals(
            "Mar 1 2027 (no leap day) must skip straight to Feb 27",
            listOf("Mar 1", "Feb 28", "Feb 27"),
            labelsAt(utc(2027, 3, 1, 12))
        )
    }

    @Test
    fun auditYearBoundary() {
        assertEquals(
            listOf("Jan 1", "Dec 31", "Dec 30"),
            labelsAt(utc(2027, 1, 1, 0, 0, 0))
        )
        assertEquals(
            listOf("Jan 2", "Jan 1", "Dec 31"),
            labelsAt(utc(2027, 1, 2, 23, 59, 59))
        )
    }

    @Test
    fun auditMonthBoundariesEveryMonth() {
        // First of every month in a leap and a non-leap year.
        for (year in listOf(2027, 2028)) {
            for (mo in 1..12) {
                val now = utc(year, mo, 1, 0, 0, 0)
                val got = labelsAt(now)
                val ref = GregorianCalendar(TimeZone.getTimeZone("UTC"))
                ref.timeInMillis = now * 1000
                val want = (0 until 3).map {
                    val c = ref.clone() as Calendar
                    c.add(Calendar.DAY_OF_MONTH, -it)
                    expectLabel(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                        c.get(Calendar.DAY_OF_MONTH))
                }
                assertEquals("$year-$mo-01", want, got)
            }
        }
    }

    /**
     * The load-bearing claim: subtracting 86400 equals Calendar day arithmetic in UTC.
     * Proven exhaustively over 20 years of days rather than argued.
     */
    @Test
    fun auditSubtracting86400EqualsCalendarDayArithmeticForTwentyYears() {
        val ref = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        var now = utc(2020, 1, 1, 12)
        val end = utc(2040, 1, 1, 12)
        var checked = 0
        while (now < end) {
            val got = labelsAt(now)
            ref.timeInMillis = now * 1000
            val want = (0 until 3).map {
                val c = ref.clone() as Calendar
                c.add(Calendar.DAY_OF_MONTH, -it)
                expectLabel(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH))
            }
            assertEquals("at epoch $now", want, got)
            now += 86400
            checked++
        }
        assertTrue("must have checked >7000 days, got $checked", checked > 7000)
    }

    /**
     * The device default zone must not reach the computation. Run the whole build under
     * hostile default zones including ones with DST and half-hour offsets, and under the
     * DST transition instants of those zones.
     */
    @Test
    fun auditDefaultTimeZoneCannotInfluenceTheGrid() {
        val original = TimeZone.getDefault()
        try {
            val zones = listOf(
                "UTC", "America/New_York", "Europe/Berlin", "Australia/Lord_Howe",
                "Asia/Kolkata", "Pacific/Kiritimati", "Pacific/Niue", "Pacific/Chatham",
                "America/Sao_Paulo", "Asia/Kathmandu"
            )
            // Instants that are DST transitions in at least one zone above.
            val instants = listOf(
                utc(2026, 3, 8, 7), utc(2026, 11, 1, 6), utc(2026, 3, 29, 1),
                utc(2026, 10, 25, 1), utc(2026, 4, 5, 16), utc(2026, 10, 4, 16),
                utc(2026, 8, 22, 0, 0, 0), utc(2026, 8, 22, 23, 59, 59),
                utc(2027, 1, 1, 0, 0, 0), utc(2028, 2, 29, 0, 0, 0)
            )
            val baseline = HashMap<Long, List<String>>()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            for (i in instants) baseline[i] = labelsAt(i)

            for (z in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(z))
                for (i in instants) {
                    assertEquals("zone $z at $i", baseline[i], labelsAt(i))
                    // and the placement of a report must not move either
                    val s = repo.buildStatuses(
                        listOf("X"), listOf(rep("X", i - 3600, "r")), i
                    ).single()
                    val cell = s.days.withIndex().flatMap { (d, day) ->
                        day.slots.withIndex().filter { "r" in it.value.reportIds }
                            .map { d to it.index }
                    }
                    assertEquals("zone $z placement at $i", 1, cell.size)
                    baseline["p$i".hashCode().toLong()]?.let { }
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** Locale can swap the calendar system out from under Calendar.getInstance. */
    @Test
    fun auditDefaultLocaleCannotInfluenceTheGrid() {
        val original = Locale.getDefault()
        try {
            val want = run {
                Locale.setDefault(Locale.US)
                labelsAt(utc(2026, 8, 22, 12))
            }
            for (l in listOf(
                Locale("th", "TH", "TH"), Locale("ja", "JP", "JP"),
                Locale("ar", "SA"), Locale.forLanguageTag("th-TH-u-ca-buddhist")
            )) {
                Locale.setDefault(l)
                assertEquals("locale $l", want, labelsAt(utc(2026, 8, 22, 12)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---------- aliasing / shared Calendar state leak ----------

    /**
     * The shared Calendar is mutated by the labels loop after todayMidnightSec is read.
     * If any later step re-read it, day 0 would inherit day 2's date. Prove day 0's
     * slots are anchored on today, not on the last value the Calendar held.
     */
    @Test
    fun auditSharedCalendarIsNotReReadAfterTheLabelsLoop() {
        val now = utc(2026, 8, 22, 12)
        // A report at today 12:30 must be in day 0. If the anchor had leaked to Aug 20
        // it would fall outside the grid entirely.
        val s = repo.buildStatuses(
            listOf("X"), listOf(rep("X", utc(2026, 8, 22, 12, 30), "r")), now
        ).single()
        assertEquals("Aug 22", s.days[0].dateLabel)
        assertTrue("today's report must be in day 0 slot 5", "r" in s.days[0].slots[5].reportIds)
        assertTrue(
            "no other day may hold it",
            s.days.drop(1).all { d -> d.slots.all { it.count == 0 } }
        )
    }

    /** Two consecutive calls on the same repository must be identical (no instance state). */
    @Test
    fun auditRepeatedCallsAreIdempotent() {
        val now = utc(2026, 8, 22, 12)
        val reports = listOf(
            rep("X", utc(2026, 8, 22, 1), "a"), rep("X", utc(2026, 8, 21, 23), "b"),
            rep("X", utc(2026, 8, 20, 0, 0, 0), "c")
        )
        fun shape() = repo.buildStatuses(listOf("X"), reports, now).single()
            .days.map { d -> d.dateLabel to d.slots.map { it.reportIds } }
        val first = shape()
        repeat(5) { assertEquals("call must not drift", first, shape()) }
    }

    // ---------- slot boundary exactness ----------

    /** No report may appear in two cells, and none inside the window may vanish. */
    @Test
    fun auditEveryBoundaryInstantLandsInExactlyOneCell() {
        val now = utc(2026, 8, 22, 12)
        val mid = utc(2026, 8, 22, 0, 0, 0)
        // every slot edge of all three days, and one second either side of each
        val probes = ArrayList<Long>()
        for (d in 0 until 3) for (s in 0..12) {
            val edge = mid - d * 86400L + s * 7200L
            probes.add(edge - 1); probes.add(edge); probes.add(edge + 1)
        }
        for (t in probes.distinct()) {
            val s = repo.buildStatuses(listOf("X"), listOf(rep("X", t, "r")), now).single()
            val hits = s.days.withIndex().flatMap { (di, day) ->
                day.slots.withIndex().filter { "r" in it.value.reportIds }.map { di to it.index }
            }
            val inWindow = t >= mid - 2 * 86400L && t < mid + 86400L
            if (inWindow) {
                assertEquals("epoch $t must occupy exactly one cell, got $hits", 1, hits.size)
                // and the cell's day must match the report's UTC date
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                c.timeInMillis = t * 1000
                val want = expectLabel(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH))
                assertEquals("epoch $t day label", want, s.days[hits[0].first].dateLabel)
                // slot index must invert the hour band
                assertEquals("epoch $t slot", 11 - c.get(Calendar.HOUR_OF_DAY) / 2, hits[0].second)
            } else {
                assertEquals("epoch $t is outside the window", 0, hits.size)
            }
        }
    }

    /** Counts must sum to the number of in-window reports: nothing dropped, nothing doubled. */
    @Test
    fun auditCountsConserveReports() {
        val now = utc(2026, 8, 22, 12)
        val mid = utc(2026, 8, 22, 0, 0, 0)
        val reports = ArrayList<ApiReport>()
        var i = 0
        var t = mid - 2 * 86400L
        while (t < mid + 86400L) { reports.add(rep("X", t, "r${i++}")); t += 1801 }
        val s = repo.buildStatuses(listOf("X"), reports, now).single()
        val total = s.days.sumOf { d -> d.slots.sumOf { it.count } }
        val ids = s.days.flatMap { d -> d.slots.flatMap { it.reportIds } }
        assertEquals("every in-window report must be counted once", reports.size, total)
        assertEquals("no id may repeat", ids.size, ids.toSet().size)
        assertEquals("id set must be complete", reports.map { it.id }.toSet(), ids.toSet())
    }

    // ---------- duplicate catalogue names ----------

    @Test
    fun auditDuplicateCatalogueNamesProduceDuplicateRows() {
        val s = repo.buildStatuses(
            listOf("DUP", "DUP", "OTHER"),
            listOf(rep("DUP", utc(2026, 8, 22, 11), "r")),
            utc(2026, 8, 22, 12)
        )
        assertEquals("a duplicated catalogue name yields a duplicated row", 3, s.size)
        assertEquals(2, s.count { it.name == "DUP" })
        // both duplicated rows carry the same report -> the tap dialog double lists it
        assertEquals(
            listOf(1, 1),
            s.filter { it.name == "DUP" }.map { it.days[0].slots[6].count }
        )
    }

    @Test
    fun auditReportsForNamesAbsentFromCatalogueAreSilentlyDropped() {
        val s = repo.buildStatuses(
            listOf("IN-CATALOG"),
            listOf(rep("NOT-IN-CATALOG", utc(2026, 8, 22, 11), "ghost")),
            utc(2026, 8, 22, 12)
        )
        assertTrue(
            "a report whose satellite is not in the catalogue never renders",
            s.single().days.all { d -> d.slots.all { it.count == 0 } }
        )
    }

    // ---------- unparsable timestamps ----------

    @Test
    fun auditZeroTimestampFromFailedParseIsDroppedNotShownAsEpoch() {
        val s = repo.buildStatuses(
            listOf("X"), listOf(rep("X", 0L, "unparsable")), utc(2026, 8, 22, 12)
        ).single()
        assertTrue(
            "a 0L timestamp (parse failure) must not render",
            s.days.all { d -> d.slots.all { it.count == 0 } }
        )
    }

    // ---------- future reports ----------

    @Test
    fun auditFutureReportsLaterTodayStillRender() {
        // Fetched at 07:00; a report stamped 23:00 today lands in slot 0 of today.
        val s = repo.buildStatuses(
            listOf("X"), listOf(rep("X", utc(2026, 8, 22, 23), "later")), utc(2026, 8, 22, 7)
        ).single()
        assertTrue("today's later bands are pre-drawn", "later" in s.days[0].slots[0].reportIds)
    }

    // ---------- complexity ----------

    /** One pass per slot over the satellite's own reports; not O(all reports x slots). */
    @Test
    fun auditBuildIsLinearInReportsNotQuadratic() {
        fun timeFor(nSats: Int, nReports: Int): Long {
            val names = (0 until nSats).map { "S$it" }
            val now = utc(2026, 8, 22, 12)
            val mid = utc(2026, 8, 22, 0, 0, 0)
            val reports = (0 until nReports).map {
                rep(names[it % nSats], mid - (it % 172800).toLong(), "r$it")
            }
            repo.buildStatuses(names, reports, now) // warm
            val t0 = System.nanoTime()
            repeat(3) { repo.buildStatuses(names, reports, now) }
            return System.nanoTime() - t0
        }
        val small = timeFor(88, 500)
        val big = timeFor(88, 5000)
        val ratio = big.toDouble() / small
        println("AUDIT complexity: 500 reports=${small / 1_000_000}ms 5000=${big / 1_000_000}ms ratio=$ratio")
        assertNotNull(ratio)
        assertTrue("10x the reports must not cost >40x the time (ratio=$ratio)", ratio < 40)
    }

    /** toSatReport's YEAR is locale sensitive: proves whether the dialog date corrupts. */
    @Test
    fun auditReportDialogDateUnderThaiLocale() {
        val original = Locale.getDefault()
        try {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
            c.timeInMillis = utc(2026, 8, 22, 11) * 1000
            val gregorianYear = c.get(Calendar.YEAR)
            Locale.setDefault(Locale("th", "TH", "TH"))
            val c2 = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c2.timeInMillis = utc(2026, 8, 22, 11) * 1000
            val thaiYear = c2.get(Calendar.YEAR)
            println("AUDIT locale year: gregorian=$gregorianYear thai=$thaiYear class=${c2.javaClass.name}")
            assertEquals(
                "if these differ, toSatReport prints a Buddhist year in the dialog",
                gregorianYear, thaiYear
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}
