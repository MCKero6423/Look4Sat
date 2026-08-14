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
package com.rtbishop.look4sat.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.clipLat
import com.rtbishop.look4sat.core.domain.utility.clipLon
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.toMapGeoPos
import com.rtbishop.look4sat.core.domain.utility.toDegrees
import com.rtbishop.look4sat.core.domain.utility.toTimerString
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs

class MapViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {

    private val stationPos = settingsRepo.stationPosition.value
    private val defaultPass = getDefaultPass()
    private val _uiState = MutableStateFlow(
        MapState(
            isLightUi = settingsRepo.otherSettings.value.stateOfLightTheme,
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            orbitalPass = defaultPass
        )
    )
    private var allPasses = satelliteRepo.passes.value
    private var allSatellites = satelliteRepo.satellites.value
    private var dataUpdateJob: Job? = null
    private var dataUpdateRate = 1000L
    private var selectedOrbitalObject: OrbitalObject? = null
    val uiState: StateFlow<MapState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update { it.copy(isUtc = settings.stateOfUtc) }
            }
        }
        val (selectedCatNum, _) = satelliteRepo.selectedPass.value
        selectDefaultSatellite(if (selectedCatNum != 0) selectedCatNum else -1)
    }

    fun onAction(action: MapAction) {
        when (action) {
            MapAction.SelectPrev -> scrollSelection(true)
            MapAction.SelectNext -> scrollSelection(false)
            is MapAction.SelectItem -> selectSatellite(action.item)
            is MapAction.SelectDefaultItem -> selectDefaultSatellite(action.catnum)
        }
    }

    private fun getStationPosition() {
        val osmLat = clipLat(stationPos.latitude)
        val osmLon = clipLon(stationPos.longitude)
        _uiState.update { it.copy(stationPosition = GeoPos(osmLat, osmLon)) }
    }

    private fun selectDefaultSatellite(catnum: Int) {
        if (allSatellites.isNotEmpty()) {
            if (catnum == -1) {
                allPasses.find { pass -> pass.progress < 100 && !pass.isDeepSpace }
                    ?.let { pass -> selectSatellite(pass.orbitalObject) }
            } else {
                allSatellites.find { it.data.catnum == catnum }?.let { selectSatellite(it) }
            }
        }
    }

    private fun scrollSelection(decrement: Boolean) {
        if (allSatellites.isNotEmpty()) {
            val index = allSatellites.indexOf(selectedOrbitalObject)
            if (decrement) {
                if (index > 0) selectSatellite(allSatellites[index - 1])
                else selectSatellite(allSatellites[allSatellites.size - 1])
            } else {
                if (index < allSatellites.size - 1) selectSatellite(allSatellites[index + 1])
                else selectSatellite(allSatellites[0])
            }
        }
    }

    private fun selectSatellite(orbitalObject: OrbitalObject, updateFreq: Long = dataUpdateRate) {
        selectedOrbitalObject = orbitalObject
        viewModelScope.launch {
            dataUpdateJob?.cancelAndJoin()
            dataUpdateJob = launch {
                val dateNow = Date()
                getStationPosition()
                getSatTrack(orbitalObject, stationPos, dateNow)
                // Scale update interval based on satellite count to avoid excessive CPU usage
                val effectiveRate = when {
                    allSatellites.size > 5000 -> maxOf(updateFreq, 3000L)
                    allSatellites.size > 1000 -> maxOf(updateFreq, 2000L)
                    else -> updateFreq
                }
                while (isActive) {
                    dateNow.time = System.currentTimeMillis()
                    updateMapState(orbitalObject, allSatellites, stationPos, dateNow)
                    delay(effectiveRate)
                }
            }
        }
    }

    /**
     * Combined update: computes all satellite positions in parallel, then derives
     * the selected satellite's footprint and info panel data from the same batch,
     * and emits a single state update to avoid redundant recompositions.
     */
    private suspend fun updateMapState(
        selected: OrbitalObject,
        orbitalObjects: List<OrbitalObject>,
        pos: GeoPos,
        date: Date
    ) {
        // 1. Compute all positions in parallel, collecting full OrbitalPos for the selected sat
        val positionsMap = HashMap<OrbitalObject, GeoPos>(orbitalObjects.size)
        var selectedSatPos: OrbitalPos? = null

        coroutineScope {
            // Chunk satellites to balance parallelism vs coroutine overhead
            val chunkSize = maxOf(1, orbitalObjects.size / PARALLEL_CHUNKS)
            val deferreds = orbitalObjects.chunked(chunkSize).map { chunk ->
                async {
                    val localPositions = ArrayList<Pair<OrbitalObject, GeoPos>>(chunk.size)
                    var localSelectedPos: OrbitalPos? = null
                    for (satellite in chunk) {
                        val satPos = satelliteRepo.getPosition(satellite, pos, date.time)
                        localPositions.add(satellite to satPos.toMapGeoPos())
                        if (satellite === selected) {
                            localSelectedPos = satPos
                        }
                    }
                    Pair(localPositions, localSelectedPos)
                }
            }
            for ((localPositions, localSelectedPos) in deferreds.awaitAll()) {
                for (pair in localPositions) {
                    positionsMap[pair.first] = pair.second
                }
                if (localSelectedPos != null) {
                    selectedSatPos = localSelectedPos
                }
            }
        }

        // 2. Derive footprint, info data, sun and moon position from already-computed state
        val satPos = selectedSatPos ?: satelliteRepo.getPosition(selected, pos, date.time)
        val footprint = satPos
        val mapData = buildMapData(selected, satPos, date)
        val sunPos = CelestialComputer.getSunPosition(stationPos, date.time)
        val moonPos = CelestialComputer.getMoonPosition(stationPos, date.time)

        // 3. Single atomic state update — one recomposition per cycle
        _uiState.update {
            it.copy(
                positions = positionsMap,
                footprint = footprint,
                mapData = mapData.first,
                orbitalPass = mapData.second,
                sunLatDeg = sunPos.latitude,
                sunLonDeg = sunPos.longitude,
                moonLatDeg = moonPos.declination, // sub-lunar latitude = declination
                moonLonDeg = if (moonPos.gha <= 180.0) -moonPos.gha else 360.0 - moonPos.gha
            )
        }
    }

    /**
     * Builds the map info panel data from an already-computed OrbitalPos.
     * No additional getPosition() call needed.
     */
    private fun buildMapData(
        sat: OrbitalObject,
        satPos: OrbitalPos,
        date: Date
    ): Pair<MapData, OrbitalPass> {
        var orbitalPass = defaultPass
        var aosTime = 0L.toTimerString()
        var isTimeAos = true
        // Select by time, not by OrbitalPass.progress: that field defaults to 0
        // and is only ever filled in by PassesViewModel's own local copy, never
        // written back to the repository. Filtering on `progress < 1` therefore
        // always matched the satellite's *first* pass, so once it ended the map
        // countdown stuck at 00:00:00 and never advanced to the next pass.
        currentOrNextPass(sat.data.catnum, date.time)?.let { satPass ->
            orbitalPass = satPass
            if (!satPass.isDeepSpace) {
                if (date.time < satPass.aosTime) {
                    isTimeAos = true
                    aosTime = (satPass.aosTime - date.time).toTimerString()
                } else if (date.time < satPass.losTime) {
                    isTimeAos = false
                    aosTime = (satPass.losTime - date.time).toTimerString()
                }
            }
        }
        val azimuth = satPos.azimuth.toDegrees()
        val elevation = satPos.elevation.toDegrees()
        val osmPos = satPos.toMapGeoPos()
        val qthLoc = positionToQth(osmPos.latitude, osmPos.longitude) ?: "-- --"
        val velocity = satPos.getOrbitalVelocity()
        val phase = satPos.phase.toDegrees()
        val visibility = satPos.eclipsed
        val satData = MapData(
            sat.data.catnum,
            sat.data.name,
            aosTime,
            isTimeAos,
            azimuth,
            elevation,
            satPos.distance,
            satPos.altitude,
            velocity,
            qthLoc,
            osmPos,
            sat.data.orbitalPeriod,
            phase,
            visibility
        )
        return satData to orbitalPass
    }

    /** Ongoing pass for [catnum] if any, otherwise its next upcoming pass. */
    private fun currentOrNextPass(catnum: Int, timeMillis: Long): OrbitalPass? =
        selectCurrentOrNextPass(allPasses, catnum, timeMillis)

    private suspend fun getSatTrack(orbitalObject: OrbitalObject, pos: GeoPos, date: Date) {
        val endDate = Date(date.time + (orbitalObject.data.orbitalPeriod * 2.4 * 60000L).toLong())
        val track = satelliteRepo.getTrack(orbitalObject, pos, date.time, endDate.time)
            .map { it.toMapGeoPos() }
        _uiState.update { it.copy(track = splitAtAntimeridian(track)) }
    }

    companion object {
        /** Number of parallel chunks for satellite position computation */
        private const val PARALLEL_CHUNKS = 4

        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                MapViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo
                )
            }
        }
    }
}

/**
 * Splits a ground track into polylines that never span the antimeridian.
 *
 * Each crossing closes the current polyline on the edge it leaves through and
 * opens the next one on the opposite edge, both at the interpolated crossing
 * latitude. Without the entry point a new segment started inland (e.g. at -178)
 * and the drawn track broke visibly at 180 degrees; reusing the next sample's
 * latitude for the edge made the closing leg jump north or south.
 */
internal fun splitAtAntimeridian(track: List<GeoPos>): List<List<GeoPos>> {
    val segments = mutableListOf<List<GeoPos>>()
    val current = mutableListOf<GeoPos>()
    var previous: GeoPos? = null
    track.forEach { position ->
        val last = previous
        if (last != null && abs(position.longitude - last.longitude) > 180.0) {
            val exitEdge = if (last.longitude > 0.0) 180.0 else -180.0
            val edgeLatitude = crossingLatitude(last, position, exitEdge)
            current.add(GeoPos(edgeLatitude, exitEdge))
            segments.add(current.toList())
            current.clear()
            current.add(GeoPos(edgeLatitude, -exitEdge))
        }
        previous = position
        current.add(position)
    }
    segments.add(current.toList())
    return segments
}

/** Latitude where the leg between [from] and [to] crosses [edgeLongitude]. */
internal fun crossingLatitude(from: GeoPos, to: GeoPos, edgeLongitude: Double): Double {
    // Unwrap the destination so the leg is continuous, then interpolate.
    val unwrappedTo = when {
        from.longitude > 0.0 && to.longitude < 0.0 -> to.longitude + 360.0
        from.longitude < 0.0 && to.longitude > 0.0 -> to.longitude - 360.0
        else -> to.longitude
    }
    val span = unwrappedTo - from.longitude
    if (abs(span) < 1e-12) return from.latitude
    val fraction = (edgeLongitude - from.longitude) / span
    return from.latitude + (to.latitude - from.latitude) * fraction
}
/**
 * Picks the pass to describe for [catnum] at [timeMillis]: the one in progress,
 * or else the earliest one still to come.
 *
 * Deliberately ignores OrbitalPass.progress. That field is a UI-side value filled
 * in by PassesViewModel on its own copy of the list; the repository always
 * reports 0, so any `progress` predicate here silently matches everything.
 */
internal fun selectCurrentOrNextPass(
    passes: List<OrbitalPass>,
    catnum: Int,
    timeMillis: Long
): OrbitalPass? {
    val forSatellite = passes.filter { it.catNum == catnum }
    return forSatellite.firstOrNull { timeMillis >= it.aosTime && timeMillis < it.losTime }
        ?: forSatellite.filter { it.aosTime > timeMillis }.minByOrNull { it.aosTime }
}
