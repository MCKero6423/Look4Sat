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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthNeighbors
import com.rtbishop.look4sat.core.domain.utility.qthToSquare
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RoamingState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val qthLocator: String = "----",
    val gridSquares: List<String> = List(9) { "----" },
    /** Fractional position of the red marker inside the center cell, 0..1. */
    val markerX: Float = 0.5f,
    val markerY: Float = 0.5f,
    /** True when a fresh GPS fix is available (station position updated recently). */
    val gpsEnabled: Boolean = false,
    val isUpdating: Boolean = false
)

class RoamingViewModel(private val settingsRepo: ISettingsRepo) : ViewModel() {

    private val _uiState = MutableStateFlow(RoamingState())
    val uiState: StateFlow<RoamingState> = _uiState

    private var liveEnabled: Boolean = true

    init {
        // Follow station position updates
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { geoPos ->
                _uiState.update { state ->
                    state.copy(isUpdating = false, gpsEnabled = isFixFresh(geoPos)).withPosition(geoPos)
                }
            }
        }
        // Follow the "live update" preference; when enabled, periodically refresh the fix
        viewModelScope.launch {
            settingsRepo.otherSettings.collect { other ->
                liveEnabled = other.stateOfRoamingLive
                if (liveEnabled) refreshPosition()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                if (liveEnabled) refreshPosition()
                _uiState.update { state ->
                    state.copy(gpsEnabled = isFixFresh(settingsRepo.stationPosition.value))
                }
            }
        }
    }

    /** Ask the settings repo for the latest fix (triggers GPS request if stale). */
    private fun refreshPosition() {
        settingsRepo.setStationPosition()
    }

    private fun isFixFresh(pos: GeoPos): Boolean {
        return pos.timestamp > 0L && System.currentTimeMillis() - pos.timestamp < 600_000L
    }

    private fun RoamingState.withPosition(pos: GeoPos): RoamingState {
        val locator = positionToQth(pos.latitude, pos.longitude) ?: return copy(qthLocator = "----")
        val square = qthToSquare(locator)
        val squares = if (square != "----") qthNeighbors(square) else List(9) { "----" }
        return copy(
            latitude = pos.latitude,
            longitude = pos.longitude,
            qthLocator = locator,
            gridSquares = squares,
            markerX = markerFraction(pos.longitude, square, isLat = false),
            markerY = markerFraction(pos.latitude, square, isLat = true)
        )
    }

    /**
     * Fractional position of [value] (lon or lat) inside its 4-char square.
     * A square spans 2° of longitude and 1° of latitude; the fraction is
     * measured from the square's south-west corner, 0..1.
     */
    private fun markerFraction(value: Double, square: String, isLat: Boolean): Float {
        if (square.length != 4) return 0.5f
        val fieldIndex = (square[if (isLat) 1 else 0].uppercaseChar().code - 65).coerceIn(0, 17)
        val squareIndex = square[if (isLat) 3 else 2].digitToIntOrNull() ?: return 0.5f
        val cellSize = if (isLat) 1.0 else 2.0
        val cellStart = if (isLat) {
            fieldIndex * 10.0 + squareIndex - 90.0
        } else {
            fieldIndex * 20.0 + squareIndex * 2.0 - 180.0
        }
        return ((value - cellStart) / cellSize).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer { RoamingViewModel(container.settingsRepo) }
        }
    }
}
