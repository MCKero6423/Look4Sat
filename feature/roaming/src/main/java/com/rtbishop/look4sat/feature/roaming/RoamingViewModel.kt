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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoamingState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val qthLocator: String = "----",
    val gridSquares: List<String> = List(9) { "----" },
    val isUpdating: Boolean = false,
    val messageResId: Int = 0
)

class RoamingViewModel(private val settingsRepo: ISettingsRepo) : ViewModel() {

    private val _uiState = MutableStateFlow(RoamingState())
    val uiState: StateFlow<RoamingState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { geoPos ->
                _uiState.update { it.copy(isUpdating = false, messageResId = 0).withPosition(geoPos) }
            }
        }
    }

    fun onGpsClick() {
        val success = settingsRepo.setStationPosition()
        if (success) {
            _uiState.update { it.copy(isUpdating = true) }
        }
    }

    private fun RoamingState.withPosition(pos: GeoPos): RoamingState {
        val locator = positionToQth(pos.latitude, pos.longitude) ?: return copy(qthLocator = "----")
        val square = qthToSquare(locator)
        val squares = if (square != "----") qthNeighbors(square) else List(9) { "----" }
        return copy(
            latitude = pos.latitude,
            longitude = pos.longitude,
            altitude = pos.altitude,
            qthLocator = locator,
            gridSquares = squares
        )
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer { RoamingViewModel(container.settingsRepo) }
        }
    }
}
