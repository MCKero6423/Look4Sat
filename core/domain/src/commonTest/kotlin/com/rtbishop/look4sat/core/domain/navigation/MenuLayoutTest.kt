/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Menu layout rules. Every case here is a bug that shipped at least once, so
 * treat a failure as a regression rather than a spec question.
 */
class MenuLayoutTest {

    private val all = listOf(
        "Satellites", "Passes", "Radar", "Mutual", "Roaming",
        "CwDecode", "WavelogLog", "AMSAT", "Map", "Settings"
    )

    private fun layout(
        screenOrder: List<String> = emptyList(),
        subMenuOrder: List<String> = emptyList(),
        hidden: List<String> = emptyList()
    ) = MenuLayout.resolve(all, screenOrder, subMenuOrder, hidden)

    @Test
    fun defaultsPutFivePagesOnTheBarAndTheRestBehindMore() {
        val l = layout()
        assertEquals(listOf("Satellites", "Passes", "Radar", "Map", "Settings"), l.mainIds)
        assertEquals(listOf("Mutual", "Roaming", "CwDecode", "WavelogLog", "AMSAT"), l.moreIds)
    }

    @Test
    fun settingsIsAlwaysReachable() {
        // Moving any page onto the bar used to push Settings out of BOTH menus,
        // permanently locking the user out of the settings page.
        for (page in listOf("Mutual", "Roaming", "CwDecode", "WavelogLog", "AMSAT")) {
            val moved = MenuLayout.moveToMain(page, all, emptyList(), emptyList())
            val l = layout(moved.screenOrder, moved.subMenuOrder)
            assertTrue(
                "Settings" in l.mainIds || "Settings" in l.moreIds,
                "moving $page hid Settings: main=${l.mainIds} more=${l.moreIds}"
            )
        }
    }

    @Test
    fun movingAmsatToMainActuallyTakesEffect() {
        // The legacy migration re-appended AMSAT to the sub-menu unconditionally,
        // silently undoing the user's choice.
        val moved = MenuLayout.moveToMain("AMSAT", all, emptyList(), emptyList())
        val l = layout(moved.screenOrder, moved.subMenuOrder)
        assertTrue("AMSAT" in l.mainIds, "AMSAT missing from the bar: ${l.mainIds}")
        assertTrue("AMSAT" !in l.moreIds, "AMSAT still behind More: ${l.moreIds}")
    }

    @Test
    fun movingWavelogLogToMainActuallyTakesEffect() {
        val moved = MenuLayout.moveToMain("WavelogLog", all, emptyList(), emptyList())
        val l = layout(moved.screenOrder, moved.subMenuOrder)
        assertTrue("WavelogLog" in l.mainIds, "WavelogLog missing from the bar: ${l.mainIds}")
    }

    @Test
    fun newPagesUnknownToPersistedOrderDefaultToTheMoreMenu() {
        // Upgrading from a build that predates AMSAT and WavelogLog: neither list
        // mentions them, so both must land behind More rather than vanishing.
        val l = layout(
            screenOrder = listOf("Satellites", "Passes", "Radar", "Map", "Settings"),
            subMenuOrder = listOf("Mutual", "Roaming", "CwDecode")
        )
        assertTrue("AMSAT" in l.moreIds, "AMSAT should land behind More")
        assertTrue("WavelogLog" in l.moreIds, "WavelogLog should land behind More")
    }

    @Test
    fun everyVisiblePageIsReachableFromSomeMenu() {
        // Pages beyond the five slots used to vanish instead of overflowing.
        val l = layout(
            screenOrder = listOf("Satellites", "Passes", "Radar", "Mutual", "Roaming", "Map", "Settings"),
            subMenuOrder = listOf("CwDecode", "WavelogLog", "AMSAT")
        )
        assertEquals(all.toSet(), (l.mainIds + l.moreIds).toSet(), "every page must be reachable")
    }

    @Test
    fun hiddenPagesAppearInNeitherMenu() {
        val l = layout(hidden = listOf("Radar", "Map"))
        assertTrue("Radar" !in l.mainIds && "Radar" !in l.moreIds)
        assertTrue("Map" !in l.mainIds && "Map" !in l.moreIds)
    }

    @Test
    fun settingsCannotBeHidden() {
        val l = layout(hidden = listOf("Settings"))
        assertTrue("Settings" in l.mainIds || "Settings" in l.moreIds)
    }

    @Test
    fun theBarNeverExceedsFiveSlots() {
        val l = layout(screenOrder = all)
        assertTrue(l.mainIds.size <= MenuLayout.MAIN_SLOTS, "bar had ${l.mainIds.size} slots: ${l.mainIds}")
    }

    @Test
    fun movingAPageOutOfTheBarPutsItBehindMore() {
        val moved = MenuLayout.moveToMore("Radar", all, emptyList(), emptyList())
        val l = layout(moved.screenOrder, moved.subMenuOrder)
        assertTrue("Radar" in l.moreIds)
        assertTrue("Radar" !in l.mainIds)
    }

    @Test
    fun settingsCannotBeMovedOffTheBar() {
        val moved = MenuLayout.moveToMore("Settings", all, emptyList(), emptyList())
        val l = layout(moved.screenOrder, moved.subMenuOrder)
        assertTrue("Settings" in l.mainIds || "Settings" in l.moreIds)
    }

    @Test
    fun resolveIsStableWhenAppliedTwice() {
        // Persisting what resolve() produced must not change the outcome, or the
        // settings list and the bar drift apart on the next recomposition.
        val first = layout()
        val second = layout(first.mainIds, first.moreIds)
        assertEquals(first.mainIds, second.mainIds)
        assertEquals(first.moreIds, second.moreIds)
    }

    @Test
    fun movingAPageOntoAFullBarEvictsAnotherPageIntoMore() {
        // The bar starts full (5 including Settings), so making room must push an
        // existing page into More instead of dropping it.
        val moved = MenuLayout.moveToMain("CwDecode", all, emptyList(), emptyList())
        val l = layout(moved.screenOrder, moved.subMenuOrder)
        assertTrue("CwDecode" in l.mainIds)
        assertEquals(all.toSet(), (l.mainIds + l.moreIds).toSet(), "nothing may be lost")
    }
}
