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
package com.rtbishop.look4sat.feature.roaming

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthNeighbors
import com.rtbishop.look4sat.core.domain.utility.qthToSquare

data class RoamingState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val qthLocator: String = "----",
    val gridSquares: List<String> = List(9) { "----" },
    /** Fractional position of the red marker inside the center cell, 0..1. */
    val markerX: Float = 0.5f,
    val markerY: Float = 0.5f,
    /** True when a fresh GPS fix is available (station position updated recently). */
    val gpsEnabled: Boolean = false
) {
    companion object {
        /**
         * Builds the state from a [GeoPos]. The 3x3 grid and the marker
         * position are derived from the 8-char locator exactly like the
         * QTH定位器 app: the marker is placed by the 3rd character pair
         * (e.g. the "ih" in "OL42ih45"), mapped to a 0..1 fraction.
         */
        fun fromPosition(pos: GeoPos): RoamingState {
            val locator = positionToQth(pos.latitude, pos.longitude) ?: return RoamingState()
            val square = qthToSquare(locator)
            val squares = if (square != "----") qthNeighbors(square) else List(9) { "----" }
            val fresh = pos.timestamp > 0L && System.currentTimeMillis() - pos.timestamp < 600_000L
            return RoamingState(
                latitude = pos.latitude,
                longitude = pos.longitude,
                qthLocator = locator,
                gridSquares = squares,
                markerX = markerFraction(locator, isLat = false),
                markerY = markerFraction(locator, isLat = true),
                gpsEnabled = fresh
            )
        }

        /**
         * Fractional position of the fix inside the 4-char square, derived from
         * the 3rd character pair of an 8-char locator (e.g. the "ih" in
         * "OL42ih45"). Ported from the QTH定位器 app: it maps the 3rd-pair
         * letters (a..x) to a position inside the center cell instead of
         * recomputing from coordinates.
         * X: a = west edge .. x = east edge (left -> right). Y: a = south ..
         * x = north, inverted so the value grows downward on screen, matching
         * the reference app.
         */
        private fun markerFraction(locator: String, isLat: Boolean): Float {
            if (locator.length < 6) return 0.5f
            val index = if (isLat) locator[5] else locator[4]
            val fraction = (index.lowercaseChar().code - 97).coerceIn(0, 23) / 24f
            return if (isLat) 1f - fraction else fraction
        }
    }
}
