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
package com.rtbishop.look4sat.feature.mutual

/**
 * A mutual pass where a satellite is visible from two stations simultaneously.
 *
 * @param catNum Satellite catalog number
 * @param name Satellite name
 * @param startTime Common AOS time (millis)
 * @param endTime Common LOS time (millis)
 * @param maxElevationA Peak elevation for station A
 * @param maxElevationB Peak elevation for station B
 * @param elevationSamples List of (time, (elevationA, elevationB)) pairs
 */
data class MutualPass(
    val catNum: Int,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val maxElevationA: Double,
    val maxElevationB: Double,
    val elevationSamples: List<Pair<Long, Pair<Double, Double>>>
)