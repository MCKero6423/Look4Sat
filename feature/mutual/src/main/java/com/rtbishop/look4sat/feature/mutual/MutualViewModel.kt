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
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class MutualUiState(
    val stationALat: String = "",
    val stationALon: String = "",
    val stationAMinElev: Double = 10.0,
    val stationBLat: String = "",
    val stationBLon: String = "",
    val stationBMinElev: Double = 10.0,
    val hoursAhead: Int = 24,
    val mutualPasses: List<MutualPass> = emptyList(),
    val isCalculating: Boolean = false,
    val selectedPassIndex: Int = -1,
    val errorMessage: String? = null
)

class MutualViewModel(
    private val satelliteRepo: ISatelliteRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(MutualUiState())
    val uiState: StateFlow<MutualUiState> = _uiState.asStateFlow()

    fun onStationALat(value: String) = _uiState.update { it.copy(stationALat = value) }
    fun onStationALon(value: String) = _uiState.update { it.copy(stationALon = value) }
    fun onStationAMinElev(value: Double) = _uiState.update { it.copy(stationAMinElev = value) }
    fun onStationBLat(value: String) = _uiState.update { it.copy(stationBLat = value) }
    fun onStationBLon(value: String) = _uiState.update { it.copy(stationBLon = value) }
    fun onStationBMinElev(value: Double) = _uiState.update { it.copy(stationBMinElev = value) }
    fun onHoursAhead(value: Int) = _uiState.update { it.copy(hoursAhead = value) }
    fun onSelectPass(index: Int) = _uiState.update { it.copy(selectedPassIndex = index) }

    fun queryMutualPasses() {
        val state = _uiState.value
        val latA = state.stationALat.toDoubleOrNull()
        val lonA = state.stationALon.toDoubleOrNull()
        val latB = state.stationBLat.toDoubleOrNull()
        val lonB = state.stationBLon.toDoubleOrNull()

        if (latA == null || lonA == null || latB == null || lonB == null) {
            _uiState.update { it.copy(errorMessage = "请输入有效的位置坐标") }
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
            val posA = GeoPos(latA, lonA)
            val posB = GeoPos(latB, lonB)
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

    private fun findMutualPasses(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevA: Double, minElevB: Double,
        time: Long, hours: Int
    ): List<MutualPass> {
        val results = mutableListOf<MutualPass>()
        val endTime = time + hours * 60L * 60L * 1000L
        val sampleInterval = 10_000L // 10 seconds between samples

        for (sat in satellites) {
            if (sat.data.meanmo < 1e-8) continue
            val t = findNextMutualPass(sat, posA, posB, minElevA, minElevB, time, endTime)
            if (t != null) {
                val (start, end) = t
                val samples = mutableListOf<Pair<Long, Pair<Double, Double>>>()
                var maxElevA = 0.0
                var maxElevB = 0.0

                var tSample = start
                while (tSample <= end) {
                    val elevA = sat.getElevation(posA, tSample)
                    val elevB = sat.getElevation(posB, tSample)
                    if (elevA > maxElevA) maxElevA = elevA
                    if (elevB > maxElevB) maxElevB = elevB
                    samples.add(tSample to (elevA to elevB))
                    tSample += sampleInterval
                }

                results.add(
                    MutualPass(
                        catNum = sat.data.catnum,
                        name = sat.data.name,
                        startTime = start,
                        endTime = end,
                        maxElevationA = (maxElevA * 10).roundToInt() / 10.0,
                        maxElevationB = (maxElevB * 10).roundToInt() / 10.0,
                        elevationSamples = samples
                    )
                )
            }
        }
        return results
    }

    /**
     * Find the first common time window where a satellite is above both stations'
     * minimum elevations. Uses 60-second coarse stepping.
     */
    private fun findNextMutualPass(
        sat: OrbitalObject,
        posA: GeoPos, posB: GeoPos,
        minElevA: Double, minElevB: Double,
        startTime: Long, endTime: Long
    ): Pair<Long, Long>? {
        var t = startTime
        val step = 60_000L // 60s coarse search step

        while (t < endTime) {
            val elevA = sat.getElevation(posA, t)
            val elevB = sat.getElevation(posB, t)

            if (elevA > minElevA && elevB > minElevB) {
                // Both above horizon — find the common pass window
                // Rewind to find AOS (both stations)
                var aos = t
                var rew = t
                while (rew > startTime - 600_000L) {
                    val eA = sat.getElevation(posA, rew)
                    val eB = sat.getElevation(posB, rew)
                    if (eA < minElevA || eB < minElevB) {
                        aos = rew + step
                        break
                    }
                    rew -= step
                }

                // Fast-forward to find LOS (either station drops below)
                var los = t
                var fwd = t
                while (fwd < endTime + 600_000L) {
                    val eA = sat.getElevation(posA, fwd)
                    val eB = sat.getElevation(posB, fwd)
                    if (eA < minElevA || eB < minElevB) {
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
                MutualViewModel(satelliteRepo = container.satelliteRepo) as T
        }
    }
}