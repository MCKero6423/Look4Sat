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
package com.rtbishop.look4sat.feature.settings

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos

data class PositionSettings(
    val isUpdating: Boolean, val stationPos: GeoPos, val messageResId: Int
)

data class DataSettings(
    val isUpdating: Boolean,
    val entriesTotal: Int,
    val radiosTotal: Int,
    val timestamp: Long
)

data class SettingsState(
    val appVersionName: String,
    val positionSettings: PositionSettings,
    val dataSettings: DataSettings,
    val otherSettings: OtherSettings,
    val rcSettings: RCSettings,
    val radioControlSettings: RadioControlSettings,
    val dataSourcesSettings: DataSourcesSettings
)

sealed interface SettingsAction {
    // Position
    data object SetGpsPosition : SettingsAction
    data class SetGeoPosition(val latitude: Double, val longitude: Double) : SettingsAction
    data class SetQthPosition(val locator: String) : SettingsAction
    data object DismissPosMessages : SettingsAction

    // Data
    data object UpdateFromWeb : SettingsAction
    data class UpdateTLEFromFile(val uri: String, val invalidFileMessage: String) : SettingsAction
    data class UpdateTransceiversFromFile(val uri: String, val invalidFileMessage: String) : SettingsAction
    data object ClearAllData : SettingsAction

    // Toggles
    data class ToggleUtc(val value: Boolean) : SettingsAction
    data class ToggleUpdate(val value: Boolean) : SettingsAction
    data class ToggleSweep(val value: Boolean) : SettingsAction
    data class ToggleSensor(val value: Boolean) : SettingsAction
    data class ToggleLightTheme(val value: Boolean) : SettingsAction
    data class ToggleNightMode(val value: Boolean) : SettingsAction
    // UI settings: toggle a bottom-nav page's hidden state (Screen simpleName)
    data class ToggleScreen(val screenName: String) : SettingsAction
    // UI settings: update main + More menu order (4.5.1 foldable menu)
    data class UpdateMenuOrder(val mainOrder: List<String>, val subOrder: List<String>) : SettingsAction
    // UI settings: reset main + More menu order
    data object ResetMenuOrder : SettingsAction
    // WaveLog logging (4.5.2): update server config
    data class UpdateWavelogSettings(
        val url: String, val apiKey: String, val stationId: String, val autoUpload: Boolean
    ) : SettingsAction
    // WaveLog: test connection
    data object TestWavelogConnection : SettingsAction
    // WaveLog: upload queue manually
    data object UploadWavelogQueue : SettingsAction
    // WaveLog: update LoTW satellite list
    data object UpdateLotwSatellites : SettingsAction

    // Remote control
    data class UpdateRC(val settings: RCSettings) : SettingsAction
    data class UpdateRadioControl(val settings: RadioControlSettings) : SettingsAction

    // Data sources
    data class UpdateDataSources(val settings: DataSourcesSettings) : SettingsAction

    // System
    data class ShowToast(val message: String) : SettingsAction
}
