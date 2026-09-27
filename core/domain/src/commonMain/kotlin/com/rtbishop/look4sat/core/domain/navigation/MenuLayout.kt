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

/**
 * Single source of truth for the navigation menu layout.
 *
 * The bottom bar holds at most [MAIN_SLOTS] pages; the rest live behind the More
 * button. Both the bar and the settings editor resolve through here, so the list
 * the user edits is exactly the list they get.
 *
 * Lives in `core:domain` (pure Kotlin) so it is unit-testable and KMP-ready.
 */
object MenuLayout {

    /** Bottom-bar capacity, including the Settings entry. */
    const val MAIN_SLOTS = 5

    /** Must stay reachable from some menu, so the user cannot lock themselves out. */
    const val SETTINGS_ID = "Settings"

    /** Bar contents for a fresh install. */
    val defaultMainOrder = listOf("Satellites", "Passes", "Radar", "Map", SETTINGS_ID)

    /** More-menu contents for a fresh install. */
    val defaultMoreOrder = listOf("Mutual", "Roaming", "CwDecode", "WavelogLog", "AMSAT")

    /** What the bar and the More menu actually show. */
    data class Layout(val mainIds: List<String>, val moreIds: List<String>)

    /** A menu assignment ready to be persisted to settings. */
    data class Assignment(val screenOrder: List<String>, val subMenuOrder: List<String>)

    /**
     * Map persisted preferences onto the two menus.
     *
     * A page named by neither persisted list is new to this install and follows
     * the defaults, so upgrades never lose pages. Visible pages that overflow
     * [MAIN_SLOTS] fall through to the More menu instead of disappearing, and
     * [SETTINGS_ID] always survives the slot cut.
     */
    fun resolve(
        allScreenIds: List<String>,
        screenOrder: List<String>,
        subMenuOrder: List<String>,
        hiddenScreenIds: List<String>
    ): Layout {
        val visible = allScreenIds.filter { it !in hiddenScreenIds || it == SETTINGS_ID }
        val wantMain = ArrayList<String>()
        val wantMore = ArrayList<String>()
        for (id in visible) {
            when {
                id in screenOrder -> wantMain.add(id)
                id in subMenuOrder -> wantMore.add(id)
                id in defaultMainOrder -> wantMain.add(id)
                else -> wantMore.add(id)
            }
        }
        wantMain.sortBy { rank(it, screenOrder, defaultMainOrder) }
        wantMore.sortBy { rank(it, subMenuOrder, defaultMoreOrder) }

        // Reserve the Settings slot before cutting so it cannot be truncated away.
        val settingsOnBar = SETTINGS_ID in wantMain
        val budget = if (settingsOnBar) MAIN_SLOTS - 1 else MAIN_SLOTS
        val main = ArrayList<String>(MAIN_SLOTS)
        for (id in wantMain) {
            if (id == SETTINGS_ID) continue
            if (main.size == budget) break
            main.add(id)
        }
        if (settingsOnBar) main.add(SETTINGS_ID)

        val overflow = wantMain.filter { it !in main }
        return Layout(mainIds = main, moreIds = overflow + wantMore)
    }

    /** Move [screenId] onto the bar, evicting the last movable page when full. */
    fun moveToMain(
        screenId: String,
        allScreenIds: List<String>,
        screenOrder: List<String>,
        subMenuOrder: List<String>
    ): Assignment {
        val current = resolve(allScreenIds, screenOrder, subMenuOrder, emptyList())
        val main = current.mainIds.toMutableList()
        val more = current.moreIds.toMutableList()
        val wasInMore = screenId in more
        more.remove(screenId)
        if (screenId !in main) {
            val at = main.indexOf(SETTINGS_ID).let { if (it == -1) main.size else it }
            main.add(at, screenId)
        }
        // Only evict when we actually added a new page from More; internal reordering must not evict.
        if (wasInMore) {
            val movable = main.filter { it != SETTINGS_ID && it != screenId }
            if (main.size > MAIN_SLOTS && movable.isNotEmpty()) {
                val evicted = movable.last()
                main.remove(evicted)
                more.add(0, evicted)
            }
        }
        return Assignment(screenOrder = main, subMenuOrder = more)
    }

    /** Move [screenId] off the bar; Settings is refused so it stays reachable. */
    fun moveToMore(
        screenId: String,
        allScreenIds: List<String>,
        screenOrder: List<String>,
        subMenuOrder: List<String>
    ): Assignment {
        if (screenId == SETTINGS_ID) return Assignment(screenOrder, subMenuOrder)
        val current = resolve(allScreenIds, screenOrder, subMenuOrder, emptyList())
        val more = current.moreIds.toMutableList()
        if (screenId !in more) more.add(screenId)
        return Assignment(
            screenOrder = current.mainIds.filter { it != screenId },
            subMenuOrder = more
        )
    }

    private fun rank(id: String, persisted: List<String>, fallback: List<String>): Int {
        val persistedIndex = persisted.indexOf(id)
        if (persistedIndex != -1) return persistedIndex
        val fallbackIndex = fallback.indexOf(id)
        return if (fallbackIndex != -1) fallbackIndex else Int.MAX_VALUE
    }
}
