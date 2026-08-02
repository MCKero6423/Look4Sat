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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

data class MutualUiState(
    val stationALat: String = "",
    val stationALon: String = "",
    val stationAGrid: String = "",
    val stationAMinElev: Double = 10.0,
    val stationBLat: String = "",
    val stationBLon: String = "",
    val stationBGrid: String = "",
    val stationBMinElev: Double = 10.0,
    val hoursAhead: Int = 24,
    val mutualPasses: List<MutualPass> = emptyList(),
    val isCalculating: Boolean = false,
    val selectedPassIndex: Int = -1,
    val errorMessage: String? = null
)

class MutualViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(MutualUiState())
    val uiState: StateFlow<MutualUiState> = _uiState.asStateFlow()

    init {
        // Pre-fill station A with the user's current station position (as grid)
        val pos = settingsRepo.stationPosition.value
        val grid = latLonToGrid(pos.latitude, pos.longitude)
        _uiState.update { it.copy(stationAGrid = grid) }
    }

    fun onStationALat(value: String) = _uiState.update { it.copy(stationALat = value) }
    fun onStationALon(value: String) = _uiState.update { it.copy(stationALon = value) }
    fun onStationAGrid(value: String) = _uiState.update { it.copy(stationAGrid = value) }
    fun onStationAMinElev(value: Double) = _uiState.update { it.copy(stationAMinElev = value) }
    fun onStationBLat(value: String) = _uiState.update { it.copy(stationBLat = value) }
    fun onStationBLon(value: String) = _uiState.update { it.copy(stationBLon = value) }
    fun onStationBGrid(value: String) = _uiState.update { it.copy(stationBGrid = value) }
    fun onStationBMinElev(value: Double) = _uiState.update { it.copy(stationBMinElev = value) }
    fun onHoursAhead(value: Int) = _uiState.update { it.copy(hoursAhead = value) }
    fun onSelectPass(index: Int) = _uiState.update { it.copy(selectedPassIndex = index) }

    fun queryMutualPasses() {
        val state = _uiState.value

        // Resolve positions from lat/lon or grid
        val posA = resolvePosition(state.stationALat, state.stationALon, state.stationAGrid)
        val posB = resolvePosition(state.stationBLat, state.stationBLon, state.stationBGrid)

        if (posA == null || posB == null) {
            _uiState.update { it.copy(errorMessage = "请输入有效的位置坐标或网格（4/6/8位）") }
            return
        }

        val satellites = satelliteRepo.satellites.value
        if (satellites.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "没有卫星数据，请先在卫星列表中选择卫星") }
            return
        }

        _uiState.update { it.copy(isCalculating = true, errorMessage = null, mutualPasses = emptyList()) }

        viewModelScope.launch {
            val time = System.currentTimeMillis()
            val minElevA = state.stationAMinElev
            val minElevB = state.stationBMinElev
            val hours = state.hoursAhead

            val results = withContext(Dispatchers.Default) {
                findMutualPasses(satellites, posA, posB, minElevA, minElevB, time, hours)
            }

            _uiState.update {
                it.copy(
                    mutualPasses = results.sortedBy { mp -> mp.startTime },
                    isCalculating = false,
                    errorMessage = if (results.isEmpty()) "未找到共同过境" else null
                )
            }
        }
    }

    /** Resolve a position from lat/lon text or grid square text. */
    private fun resolvePosition(latText: String, lonText: String, gridText: String): GeoPos? {
        val trimmed = gridText.trim().uppercase()
        if (trimmed.length in listOf(4, 6, 8) && trimmed.all { it.isLetterOrDigit() }) {
            return gridToLatLon(trimmed)
        }
        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()
        return if (lat != null && lon != null) GeoPos(lat, lon) else null
    }

    /** Convert lat/lon to Maidenhead grid (6-char). */
    private fun latLonToGrid(lat: Double, lon: Double): String {
        var adjLon = (lon + 180.0) % 360.0
        var adjLat = (lat + 90.0) % 180.0

        val fieldLon = (adjLon / 20.0).toInt()
        val fieldLat = (adjLat / 10.0).toInt()
        adjLon -= fieldLon * 20.0
        adjLat -= fieldLat * 10.0

        val squareLon = (adjLon / 2.0).toInt()
        val squareLat = (adjLat / 1.0).toInt()
        adjLon -= squareLon * 2.0
        adjLat -= squareLat * 1.0

        val subLon = (adjLon * 60.0 / 5.0).toInt()
        val subLat = (adjLat * 60.0 / 2.5).toInt()

        return buildString {
            append('A' + fieldLon)
            append('A' + fieldLat)
            append('0' + squareLon)
            append('0' + squareLat)
            append('A' + subLon)
            append('A' + subLat)
        }
    }

    /** Convert Maidenhead grid (4, 6, or 8 chars) to lat/lon center of the square. */
    private fun gridToLatLon(grid: String): GeoPos? {
        val g = grid.uppercase()
        if (g.length < 4) return null
        val lonField = (g[0] - 'A').toDouble() * 20.0
        val latField = (g[1] - 'A').toDouble() * 10.0
        if (lonField < 0 || lonField > 340 || latField < 0 || latField > 170) return null

        val lonSquare = (g[2] - '0').toDouble() * 2.0
        val latSquare = (g[3] - '0').toDouble() * 1.0
        if (lonSquare < 0 || lonSquare > 18 || latSquare < 0 || latSquare > 9) return null

        var lon = lonField + lonSquare
        var lat = latField + latSquare

        if (g.length >= 6) {
            val lonSub = (g[4] - 'A').toDouble() * 5.0 / 60.0
            val latSub = (g[5] - 'A').toDouble() * 2.5 / 60.0
            if (lonSub < 0 || lonSub > 115.0 / 60.0 || latSub < 0 || latSub > 57.5 / 60.0) return null
            lon += lonSub
            lat += latSub

            if (g.length >= 8) {
                val lonExt = (g[6] - '0').toDouble() * 30.0 / 3600.0
                val latExt = (g[7] - '0').toDouble() * 15.0 / 3600.0
                if (lonExt < 0 || lonExt > 270.0 / 3600.0 || latExt < 0 || latExt > 135.0 / 3600.0) return null
                lon += lonExt + 15.0 / 3600.0
                lat += latExt + 7.5 / 3600.0
            } else {
                lon += 2.5 / 60.0
                lat += 1.25 / 60.0
            }
        } else {
            lon += 1.0
            lat += 0.5
        }

        lon = (lon + 180.0) % 360.0 - 180.0
        lat = (lat + 90.0) % 180.0 - 90.0
        return GeoPos(lat, lon)
    }

    private fun findMutualPasses(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, hours: Int
    ): List<MutualPass> {
        val results = mutableListOf<MutualPass>()
        val endTime = time + hours * 60L * 60L * 1000L
        val sampleInterval = 5_000L // 5 seconds between samples for smooth curves

        for (sat in satellites) {
            if (sat.data.meanmo < 1e-8) continue
            var searchStart = time
            // Find ALL mutual passes for this satellite
            while (true) {
                val t = findNextMutualPass(sat, posA, posB, minElevADeg, minElevBDeg, searchStart, endTime)
                if (t == null) break
                val (aos, los) = t

                // Refine AOS: finer search near the edge
                val refinedAos = refineEdge(sat, posA, posB, minElevADeg, minElevBDeg, aos, step = 1_000L, goingUp = true)
                // Refine LOS: finer search near the edge
                val refinedLos = refineEdge(sat, posA, posB, minElevADeg, minElevBDeg, los, step = 1_000L, goingUp = false)

                val samples = mutableListOf<Pair<Long, Pair<Double, Double>>>()
                var maxElevA = 0.0
                var maxElevB = 0.0

                var tSample = refinedAos
                while (tSample <= refinedLos) {
                    val elevA = elevationDeg(sat, posA, tSample)
                    val elevB = elevationDeg(sat, posB, tSample)
                    if (elevA > maxElevA) maxElevA = elevA
                    if (elevB > maxElevB) maxElevB = elevB
                    samples.add(tSample to (elevA to elevB))
                    tSample += sampleInterval
                }

                if (maxElevA > minElevADeg || maxElevB > minElevBDeg) {
                    results.add(
                        MutualPass(
                            catNum = sat.data.catnum,
                            name = sat.data.name,
                            startTime = refinedAos,
                            endTime = refinedLos,
                            maxElevationA = (maxElevA * 10).roundToInt() / 10.0,
                            maxElevationB = (maxElevB * 10).roundToInt() / 10.0,
                            elevationSamples = samples
                        )
                    )
                }
                // Advance past this pass and keep searching
                searchStart = refinedLos + 120_000L
            }
        }
        return results
    }

    /** Refine the AOS (goingUp=true) or LOS (goingUp=false) with fine steps. */
    private fun refineEdge(
        sat: OrbitalObject, posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        approxTime: Long, step: Long, goingUp: Boolean
    ): Long {
        var t = approxTime
        val limit = 30_000L // search up to 30 seconds
        if (goingUp) {
            // Find the point where both stations just go above minElev
            var best = t
            t -= limit
            while (t <= approxTime + limit) {
                val eA = elevationDeg(sat, posA, t)
                val eB = elevationDeg(sat, posB, t)
                if (eA > minElevADeg && eB > minElevBDeg) {
                    best = t
                    break
                }
                t += step
            }
            return best
        } else {
            // Find the point where either station just drops below minElev
            var best = t
            t -= limit
            while (t <= approxTime + limit) {
                val eA = elevationDeg(sat, posA, t)
                val eB = elevationDeg(sat, posB, t)
                if (eA < minElevADeg || eB < minElevBDeg) {
                    best = t
                    break
                }
                t += step
            }
            return best
        }
    }

    private fun elevationDeg(sat: OrbitalObject, pos: GeoPos, time: Long): Double {
        return sat.getElevation(pos, time) * 180.0 / PI
    }

    private fun findNextMutualPass(
        sat: OrbitalObject,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        startTime: Long, endTime: Long
    ): Pair<Long, Long>? {
        var t = startTime
        val step = 60_000L

        while (t < endTime) {
            val elevA = elevationDeg(sat, posA, t)
            val elevB = elevationDeg(sat, posB, t)

            if (elevA > minElevADeg && elevB > minElevBDeg) {
                var aos = t
                var rew = t
                while (rew > startTime - 600_000L) {
                    val eA = elevationDeg(sat, posA, rew)
                    val eB = elevationDeg(sat, posB, rew)
                    if (eA < minElevADeg || eB < minElevBDeg) {
                        aos = rew + step
                        break
                    }
                    rew -= step
                }

                var los = t
                var fwd = t
                while (fwd < endTime + 600_000L) {
                    val eA = elevationDeg(sat, posA, fwd)
                    val eB = elevationDeg(sat, posB, fwd)
                    if (eA < minElevADeg || eB < minElevBDeg) {
                        los = fwd
                        break
                    }
                    fwd += step
                }

                if (los > aos) return Pair(aos, los)
            }
            t += step
        }
        return null
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    companion object {
        fun factory(container: IMainContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MutualViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo
                ) as T
        }
    }
}