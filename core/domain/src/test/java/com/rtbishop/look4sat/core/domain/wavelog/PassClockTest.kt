package com.rtbishop.look4sat.core.domain.wavelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen used to stamp the current time with no way to change it, which is wrong for the way
 * satellite operators actually work: record the pass, transcribe it afterwards. Every transcribed
 * contact was then off by however long the transcription took.
 *
 * The parse is deliberately narrow. A mis-parsed time silently backdates a contact and nothing
 * downstream would notice, so anything unclear has to be rejected rather than guessed.
 */
class PassClockTest {

    private val dayStart = 1_800_000_000_000L // some midnight UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `a colon separated time is understood`() {
        assertEquals(PassClock.Command.At(14 * 60 + 55), PassClock.parse("14:55"))
    }

    @Test
    fun `a bare four digit time is understood`() {
        assertEquals(PassClock.Command.At(14 * 60 + 55), PassClock.parse("1455"))
    }

    @Test
    fun `midnight and the last minute of the day both parse`() {
        assertEquals(PassClock.Command.At(0), PassClock.parse("00:00"))
        assertEquals(PassClock.Command.At(23 * 60 + 59), PassClock.parse("23:59"))
    }

    @Test
    fun `an impossible time is not guessed at`() {
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("24:00"))
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("12:60"))
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("99:99"))
    }

    @Test
    fun `a callsign shaped entry is not a time`() {
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("BG7NTA"))
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("59"))
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("OL72AP"))
    }

    @Test
    fun `a shift in minutes is understood in both directions`() {
        assertEquals(PassClock.Command.Shift(3), PassClock.parse("+3"))
        assertEquals(PassClock.Command.Shift(-2), PassClock.parse("-2"))
        assertEquals(PassClock.Command.Shift(15), PassClock.parse("+15m"))
    }

    /** A pass lasts minutes, so a huge shift is a typo rather than an intention. */
    @Test
    fun `an absurd shift is rejected`() {
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("+99999"))
        assertEquals(PassClock.Command.Unrecognised, PassClock.parse("+x"))
    }

    @Test
    fun `empty or now returns to live time`() {
        assertEquals(PassClock.Command.Live, PassClock.parse(""))
        assertEquals(PassClock.Command.Live, PassClock.parse("   "))
        assertEquals(PassClock.Command.Live, PassClock.parse("now"))
        assertEquals(PassClock.Command.Live, PassClock.parse("NOW"))
    }

    @Test
    fun `live time resolves to now`() {
        val now = dayStart + 10 * hour
        assertEquals(now, PassClock.resolve(PassClock.Command.Live, now, dayStart))
    }

    /** An unrecognised entry must not move the clock. */
    @Test
    fun `an unrecognised command leaves the time alone`() {
        val now = dayStart + 10 * hour
        assertEquals(now, PassClock.resolve(PassClock.Command.Unrecognised, now, dayStart))
    }

    @Test
    fun `an absolute time earlier today resolves to today`() {
        val now = dayStart + 14 * hour
        val resolved = PassClock.resolve(PassClock.Command.At(10 * 60 + 30), now, dayStart)
        assertEquals(dayStart + 10 * hour + 30 * minute, resolved)
    }

    /**
     * The case that would otherwise put a contact a day in the future. Transcribing at 00:05 UTC a
     * pass that ran at 23:58 is normal, not an error - passes cross midnight UTC routinely.
     */
    @Test
    fun `an absolute time later than now belongs to the previous day`() {
        val now = dayStart + 5 * minute // 00:05 UTC
        val resolved = PassClock.resolve(PassClock.Command.At(23 * 60 + 58), now, dayStart)
        assertEquals(dayStart - 86_400_000L + 23 * hour + 58 * minute, resolved)
        assertTrue("must be in the past", resolved < now)
    }

    @Test
    fun `a shift moves the time by whole minutes`() {
        val now = dayStart + 10 * hour
        assertEquals(now + 3 * minute, PassClock.resolve(PassClock.Command.Shift(3), now, dayStart))
        assertEquals(now - 2 * minute, PassClock.resolve(PassClock.Command.Shift(-2), now, dayStart))
    }

    /** The UI needs to show that the clock is no longer following real time. */
    @Test
    fun `holding is reported only when the clock has moved`() {
        assertTrue(PassClock.isHolding(PassClock.Command.At(600)))
        assertTrue(PassClock.isHolding(PassClock.Command.Shift(-5)))
        assertFalse(PassClock.isHolding(PassClock.Command.Live))
        assertFalse(PassClock.isHolding(PassClock.Command.Unrecognised))
        assertFalse("a zero shift is still live", PassClock.isHolding(PassClock.Command.Shift(0)))
    }

    /** Whitespace and case must not change the verdict - this is typed one-handed outdoors. */
    @Test
    fun `surrounding whitespace does not matter`() {
        assertEquals(PassClock.Command.At(14 * 60 + 55), PassClock.parse("  14:55  "))
        assertEquals(PassClock.Command.Shift(3), PassClock.parse(" +3 "))
        assertEquals(PassClock.Command.Shift(15), PassClock.parse("+15M"))
    }
}
