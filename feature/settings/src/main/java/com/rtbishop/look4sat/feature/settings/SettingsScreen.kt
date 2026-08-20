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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import android.content.Context
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.PrimaryIconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.WhatsNewDialog
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.core.domain.navigation.MenuLayout
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsDestination() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(uiState, viewModel::onAction, container.provideLotwSatellitesRepo())

    // WaveLog grid mismatch confirmation dialog (4.5.2)
    val gridConfirm = viewModel.gridConfirm
    if (gridConfirm != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveGridConfirm(false) },
            title = { Text(text = stringResource(id = R.string.wavelog_title)) },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.wavelog_grid_mismatch,
                        gridConfirm.userGrid.take(4),
                        gridConfirm.stationGrid.take(4)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveGridConfirm(true) }) {
                    Text(text = stringResource(id = R.string.wavelog_ignore))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveGridConfirm(false) }) {
                    Text(text = stringResource(id = R.string.wavelog_cancel))
                }
            }
        )
    }

    // WaveLog error detail dialog (one-tap copy)
    val wavelogError = viewModel.wavelogError
    if (wavelogError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.wavelogError = null },
            title = { Text(text = stringResource(id = R.string.wavelog_title)) },
            text = {
                Text(
                    text = wavelogError,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WaveLog error", wavelogError))
                    viewModel.wavelogError = null
                }) {
                    Text(text = stringResource(id = R.string.wavelog_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.wavelogError = null }) {
                    Text(text = stringResource(id = R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsScreen(
    uiState: SettingsState,
    onAction: (SettingsAction) -> Unit,
    lotwRepo: com.rtbishop.look4sat.core.domain.wavelog.ILotwSatellitesRepo
) {
    val dialogs = rememberDialogVisibility()
    val pendingCustomSourcesGrant = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingCustomSourcesDeny = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberSettingsPermissions(
        sendAction = onAction,
        onBluetoothGranted = { dialogs.bluetooth = true },
        onNetworkGranted = { dialogs.network = true },
        onCustomSourcesPermissionGranted = {
            pendingCustomSourcesGrant.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        },
        onCustomSourcesPermissionDenied = {
            pendingCustomSourcesDeny.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        }
    )

    // Dialogs
    if (dialogs.position) {
        PositionDialog(
            uiState.positionSettings.stationPos.latitude,
            uiState.positionSettings.stationPos.longitude,
            dismiss = { dialogs.position = false },
            save = { lat, lon -> onAction(SettingsAction.SetGeoPosition(lat, lon)) }
        )
    }
    if (dialogs.locator) {
        LocatorDialog(
            uiState.positionSettings.stationPos.qthLocator,
            dismiss = { dialogs.locator = false },
            save = { onAction(SettingsAction.SetQthPosition(it)) }
        )
    }
    if (dialogs.dataSources) {
        DataSourcesDialog(
            useCustomTle = uiState.dataSourcesSettings.useCustomTLE,
            useCustomTransceivers = uiState.dataSourcesSettings.useCustomTransceivers,
            tleUrl = uiState.dataSourcesSettings.tleUrl,
            transceiversUrl = uiState.dataSourcesSettings.transceiversUrl,
            requestCustomSourcesPermission = { onGranted, onDenied ->
                pendingCustomSourcesGrant.value = onGranted
                pendingCustomSourcesDeny.value = onDenied
                permissions.launchCustomSourcesPermission()
            },
            onImportTle = { permissions.launchTleImport(); dialogs.dataSources = false },
            onImportTransceivers = { permissions.launchTransceiverImport(); dialogs.dataSources = false },
            onDismiss = { dialogs.dataSources = false },
            onSave = { useTle, useTx, tleUrl, txUrl ->
                val newSettings = DataSourcesSettings(
                    useCustomTLE = useTle,
                    useCustomTransceivers = useTx,
                    tleUrl = tleUrl,
                    transceiversUrl = txUrl
                )
                if (newSettings != uiState.dataSourcesSettings) onAction(SettingsAction.UpdateDataSources(newSettings))
                onAction(SettingsAction.UpdateFromWeb)
            }
        )
    }
    if (dialogs.network) {
        NetworkOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.network = false },
            onSave = { rotState, rotAddr, rotPort, rotFmt, freqState, freqAddr, freqPort, freqFmt, freqOffsetHz ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            rotatorState = rotState, rotatorAddress = rotAddr,
                            rotatorPort = rotPort, rotatorFormat = rotFmt,
                            frequencyState = freqState, frequencyAddress = freqAddr,
                            frequencyPort = freqPort, frequencyFormat = freqFmt,
                            frequencyOffsetHz = freqOffsetHz
                        )
                    )
                )
            }
        )
    }
    if (dialogs.bluetooth) {
        BluetoothOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.bluetooth = false },
            onSave = { rotState, rotAddr, rotFmt, freqState, freqAddr, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            bluetoothRotatorState = rotState, bluetoothRotatorAddress = rotAddr,
                            bluetoothRotatorFormat = rotFmt, bluetoothFrequencyState = freqState,
                            bluetoothFrequencyAddress = freqAddr, bluetoothFrequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.radioControl) {
        RadioControlDialog(
            initialSettings = uiState.radioControlSettings,
            pairedBluetoothDevices = uiState.pairedBluetoothDevices,
            onDismiss = { dialogs.radioControl = false },
            onSave = { onAction(SettingsAction.UpdateRadioControl(it)) }
        )
    }
    if (dialogs.whatsNew) {
        WhatsNewDialog(onDismiss = { dialogs.whatsNew = false })
    }

    // URLs for top bar
    val uriHandler = LocalUriHandler.current
    val appUrl = stringResource(R.string.prefs_app_url)
    val donateUrl = stringResource(R.string.prefs_donate_url)
    val fdroidTitle = stringResource(R.string.prefs_fdroid_title)
    val fdroidUrl = stringResource(R.string.prefs_fdroid_url)
    val gitHubTitle = stringResource(R.string.prefs_github_title)
    val gitHubUrl = stringResource(R.string.prefs_github_url)
    val licenseUrl = stringResource(R.string.prefs_license_url)
    val privacyUrl = stringResource(R.string.prefs_privacy_url)
    val safeOpenUri: (String) -> Unit = { url ->
        try { uriHandler.openUri(url) } catch (_: Exception) {}
    }

    ScreenColumn(
        topBar = { isVerticalLayout ->
            if (isVerticalLayout) {
                TopBar {
                    TopCard(
                        onClick = { dialogs.whatsNew = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_like)
                }
                TopBar {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            } else {
                TopBar {
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_like)
                    TopCard(
                        onClick = { dialogs.whatsNew = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            }
        }
    ) { _ ->
        val isVerticalLayout = isVerticalLayout()
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isVerticalLayout) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
        ) {
            item {
                LocationCard(
                    settings = uiState.positionSettings,
                    setGpsPos = permissions.launchLocation,
                    showPosDialog = { dialogs.position = true },
                    showLocDialog = { dialogs.locator = true },
                    dismissPosMessage = { onAction(SettingsAction.DismissPosMessages) },
                    onAction = onAction
                )
            }
            item {
                DataCard(
                    settings = uiState.dataSettings,
                    updateFromWeb = { onAction(SettingsAction.UpdateFromWeb) },
                    clearAllData = { onAction(SettingsAction.ClearAllData) },
                    showDataSourcesDialog = { dialogs.dataSources = true }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AprsCard()
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutputCard(
                    onNetworkClick = permissions.launchNetwork,
                    onBluetoothClick = permissions.launchBluetooth,
                    onRadioControlClick = { dialogs.radioControl = true }
                )
            }
            item { OtherCard(uiState.otherSettings, onAction) }
            item { WavelogCard(uiState.otherSettings, onAction, lotwRepo) }
            item {
                UiSettingsCard(
                    hiddenScreens = uiState.otherSettings.hiddenScreens,
                    screenOrder = uiState.otherSettings.screenOrder,
                    subMenuOrder = uiState.otherSettings.subMenuOrder,
                    onToggle = { name -> onAction(SettingsAction.ToggleScreen(name)) },
                    onResetOrder = { onAction(SettingsAction.ResetMenuOrder) },
                    onUpdateMenu = { main, sub -> onAction(SettingsAction.UpdateMenuOrder(main, sub)) }
                )
            }
            item { CardCredits() }

        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationCardPreview() = MainTheme {
    val stationPos = GeoPos(0.0, 0.0, 0.0, "IO91vl", 0L)
    val settings = PositionSettings(true, stationPos, 0)
    LocationCard(settings = settings, setGpsPos = {}, showPosDialog = {}, {}, {}) {}
}

@Composable
private fun LocationCard(
    settings: PositionSettings,
    setGpsPos: () -> Unit,
    showPosDialog: () -> Unit,
    showLocDialog: () -> Unit,
    dismissPosMessage: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_loc_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.stationPos.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.loc_lat_text, settings.stationPos.latitude))
                Text(text = stringResource(R.string.loc_lon_text, settings.stationPos.longitude))
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.loc_qth_text, settings.stationPos.qthLocator))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                CardButton(
                    onClick = setGpsPos,
                    text = stringResource(id = R.string.prefs_loc_gps_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showPosDialog,
                    text = stringResource(id = R.string.prefs_loc_input_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showLocDialog,
                    text = stringResource(id = R.string.prefs_loc_qth_title),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    if (settings.messageResId != 0) {
        val errorString = stringResource(id = settings.messageResId)
        LaunchedEffect(key1 = settings.messageResId) {
            onAction(SettingsAction.ShowToast(errorString))
            dismissPosMessage()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataCardPreview() = MainTheme {
    val settings = DataSettings(true, 5000, 2500, 0L)
    DataCard(settings = settings, updateFromWeb = {}, clearAllData = {}, showDataSourcesDialog = {})
}

@Composable
private fun DataCard(
    settings: DataSettings,
    updateFromWeb: () -> Unit,
    clearAllData: () -> Unit,
    showDataSourcesDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_data_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_data_entries, settings.entriesTotal))
                Text(text = stringResource(R.string.prefs_data_radios, settings.radiosTotal))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = updateFromWeb,
                    text = stringResource(id = R.string.prefs_data_update),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = showDataSourcesDialog,
                    text = stringResource(id = R.string.prefs_data_import),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = clearAllData,
                    text = stringResource(id = R.string.prefs_data_clear),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OutputCardPreview() = MainTheme { OutputCard({}, {}, {}) }

@Composable
private fun OutputCard(
    onNetworkClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRadioControlClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_data_output_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onNetworkClick,
                    text = stringResource(id = R.string.prefs_net_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onBluetoothClick,
                    text = stringResource(id = R.string.prefs_bt_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onRadioControlClick,
                    text = stringResource(id = R.string.prefs_cat_output),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtherCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        stateOfNightMode = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    OtherCard(settings = values, onAction = {})
}

@Composable
private fun OtherCard(settings: OtherSettings, onAction: (SettingsAction) -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {

            Text(
                text = stringResource(id = R.string.prefs_other_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Display preferences: UTC clock + night filter
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchTile(R.string.prefs_other_switch_utc, settings.stateOfUtc) {
                    onAction(SettingsAction.ToggleUtc(it))
                }
                SwitchTile(R.string.prefs_other_switch_night_mode, settings.stateOfNightMode) {
                    onAction(SettingsAction.ToggleNightMode(it))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Radar behavior: sweep animation + sensor control
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchTile(R.string.prefs_other_switch_sweep, settings.stateOfSweep) {
                    onAction(SettingsAction.ToggleSweep(it))
                }
                SwitchTile(R.string.prefs_other_switch_sensors, settings.stateOfSensors) {
                    onAction(SettingsAction.ToggleSensor(it))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Data management (full width)
            SwitchRow(R.string.prefs_other_switch_update, settings.stateOfAutoUpdate) {
                onAction(SettingsAction.ToggleUpdate(it))
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Compass calibration sliders at the bottom
            CompassOffsetRow(
                labelResId = R.string.prefs_other_compass_offset,
                value = settings.radarCompassOffset,
                range = -180f..180f
            ) { onAction(SettingsAction.SetRadarCompassOffset(it)) }
            Spacer(modifier = Modifier.height(4.dp))
            CompassOffsetRow(
                labelResId = R.string.prefs_other_compass_offset_elev,
                value = settings.radarCompassOffsetElev,
                range = -90f..90f
            ) { onAction(SettingsAction.SetRadarCompassOffsetElev(it)) }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * UI settings card: controls which pages appear in the bottom menu bar.
 * Order is fixed (Satellites/Passes/Radar/Mutual/Roaming/Map/Settings); turning one off makes the rest close up;
 * the Settings page is always shown (so the settings entry can't be lost).
 */
/** WaveLog log server settings card (4.5.2): matches the user's screenshot (dark card / label left, input right / button row) */
@Composable
private fun WavelogCard(
    settings: OtherSettings,
    onAction: (SettingsAction) -> Unit,
    lotwRepo: com.rtbishop.look4sat.core.domain.wavelog.ILotwSatellitesRepo
) {
    val qrzContext = LocalContext.current
    var showQrzDialog by remember { mutableStateOf(false) }
    var qrzCookie by remember {
        mutableStateOf(
            qrzContext.getSharedPreferences("qrz_cookie", android.content.Context.MODE_PRIVATE)
                .getString("cookie", "") ?: ""
        )
    }
    var qrzTestResult by remember { mutableStateOf<String?>(null) }
    var qrzTesting by remember { mutableStateOf(false) }
    val qrzScope = rememberCoroutineScope()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_wavelog_title),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showQrzDialog = true }) {
                    Text("\u2699")
                }
            }
            OutlinedTextField(
                value = settings.wavelogUrl,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(it, settings.wavelogApiKey, settings.wavelogStationId, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_url_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.wavelogApiKey,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, it, settings.wavelogStationId, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_key_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.wavelogStationId,
                onValueChange = {
                    onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, settings.wavelogApiKey, it, settings.wavelogAutoUpload))
                },
                label = { Text(stringResource(id = R.string.prefs_wavelog_station_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(R.string.prefs_wavelog_auto, settings.wavelogAutoUpload) {
                onAction(SettingsAction.UpdateWavelogSettings(settings.wavelogUrl, settings.wavelogApiKey, settings.wavelogStationId, it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(
                    onClick = { onAction(SettingsAction.TestWavelogConnection) },
                    text = stringResource(id = R.string.prefs_wavelog_test),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(SettingsAction.UpdateLotwSatellites) },
                    text = stringResource(id = R.string.prefs_wavelog_update_sats),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(SettingsAction.UploadWavelogQueue) },
                    text = stringResource(id = R.string.prefs_wavelog_upload),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    if (showQrzDialog) {
        AlertDialog(
            onDismissRequest = { showQrzDialog = false },
            title = { Text(text = stringResource(id = R.string.prefs_wavelog_qrz_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(id = R.string.prefs_wavelog_qrz_hint),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = qrzCookie,
                        onValueChange = { qrzCookie = it },
                        label = { Text(stringResource(id = R.string.prefs_wavelog_qrz_label)) },
                        minLines = 3,
                        maxLines = 8,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (qrzTestResult != null) {
                        Text(
                            text = qrzTestResult!!,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(
                        onClick = {
                            qrzTesting = true
                            qrzTestResult = null
                            qrzScope.launch {
                                val header = com.rtbishop.look4sat.core.domain.qrz.QrzGridClient.parseCookies(qrzCookie)
                                if (header.isBlank()) {
                                    qrzTestResult = "Cookie 为空, 请先粘贴"
                                    qrzTesting = false
                                    return@launch
                                }
                                val own = com.rtbishop.look4sat.core.domain.qrz.QrzGridClient.fetchOwnCallsign(header)
                                if (own == null) {
                                    qrzTestResult = "Cookie 无效或已过期(无法识别登录呼号)"
                                } else {
                                    val grid = com.rtbishop.look4sat.core.domain.qrz.QrzGridClient.lookupGrid(own, header)
                                    qrzTestResult = if (grid != null) {
                                        "登录呼号: $own  网格: $grid ✅"
                                    } else {
                                        "登录呼号: $own  网格: 未填写"
                                    }
                                }
                                qrzTesting = false
                            }
                        },
                        enabled = !qrzTesting
                    ) {
                        Text(text = if (qrzTesting) "测试中…" else "测试查询")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    qrzContext.getSharedPreferences("qrz_cookie", android.content.Context.MODE_PRIVATE)
                        .edit().putString("cookie", qrzCookie.trim()).apply()
                    showQrzDialog = false
                }) { Text(text = stringResource(id = R.string.btn_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showQrzDialog = false }) {
                    Text(text = stringResource(id = R.string.wavelog_cancel))
                }
            }
        )
    }
}

@Composable
private fun UiSettingsCard(
    hiddenScreens: List<String>,
    screenOrder: List<String>,
    subMenuOrder: List<String>,
    onToggle: (String) -> Unit,
    onResetOrder: () -> Unit,
    onUpdateMenu: (List<String>, List<String>) -> Unit
) {
    val screens = listOf(
        R.string.nav_sat to "Satellites",
        R.string.nav_pass to "Passes",
        R.string.nav_radar to "Radar",
        R.string.nav_mutual to "Mutual",
        R.string.nav_roaming to "Roaming",
        R.string.nav_cw to "CwDecode",
        R.string.nav_log to "WavelogLog",
        R.string.nav_amsat to "AMSAT",
        R.string.nav_map to "Map",
        R.string.nav_prefs to "Settings"
    ) // name 必须与 Screen.screenId 一致 (R8 安全)
    // Resolved by the same core:domain rule the bottom bar uses, so this list is
    // exactly what the user will see on the bar.
    val menuLayout = remember(screens, screenOrder, subMenuOrder, hiddenScreens) {
        MenuLayout.resolve(
            allScreenIds = screens.map { it.second },
            screenOrder = screenOrder,
            subMenuOrder = subMenuOrder,
            hiddenScreenIds = hiddenScreens
        )
    }
    val mainItems = menuLayout.mainIds
    val subItems = menuLayout.moreIds
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_ui_title),
                color = MaterialTheme.colorScheme.primary
            )
            screens.forEach { (labelRes, name) ->
                if (name == "Settings") {
                    // Settings always shown; its switch is disabled
                    SwitchRow(labelRes, true, null)
                } else {
                    SwitchRow(labelRes, name !in hiddenScreens) {
                        onToggle(name)
                    }
                }
            }
            Text(
                text = stringResource(id = R.string.prefs_ui_order_title),
                color = MaterialTheme.colorScheme.primary
            )
            // Main menu area (bottom bar, max 5 items, Settings locked last)
            Text(
                text = stringResource(id = R.string.prefs_ui_main_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DragOrderList(
                items = mainItems,
                labels = screens,
                locked = setOf("Settings"),
                moveLabel = stringResource(id = R.string.prefs_ui_move_out),
                moveEnabled = { name -> name != "Settings" },
                onMove = { name ->
                    val moved = MenuLayout.moveToMore(
                        name, screens.map { it.second }, screenOrder, subMenuOrder
                    )
                    onUpdateMenu(moved.screenOrder, moved.subMenuOrder)
                },
                onReorder = { main -> 
                    // Settings is locked last; exclude it so resolve continues to use its default position
                    val userOrder = main.filter { it != "Settings" }
                    onUpdateMenu(userOrder, subMenuOrder) 
                }
            )
            // More-menu area (pages behind "More")
            Text(
                text = stringResource(id = R.string.prefs_ui_sub_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DragOrderList(
                items = subItems,
                labels = screens,
                moveLabel = stringResource(id = R.string.prefs_ui_move_back),
                // Buttons always visible; when the main menu hits 5, the last non-Settings item moves to More (swap)
                moveEnabled = { true },
                onMove = { name ->
                    val moved = MenuLayout.moveToMain(
                        name, screens.map { it.second }, screenOrder, subMenuOrder
                    )
                    onUpdateMenu(moved.screenOrder, moved.subMenuOrder)
                },
                onReorder = { sub -> onUpdateMenu(screenOrder, sub) }
            )
            TextButton(onClick = onResetOrder) {
                Text(text = stringResource(id = R.string.prefs_ui_order_reset))
            }
        }
    }
}

/** Page-order drag list: handle on each row's right; drag up/down to reorder live; persists on release */
@Composable
private fun DragOrderList(
    items: List<String>,
    labels: List<Pair<Int, String>>,
    locked: Set<String> = emptySet(),
    moveLabel: String? = null,
    moveEnabled: (String) -> Boolean = { true },
    onMove: ((String) -> Unit)? = null,
    onReorder: (List<String>) -> Unit
) {
    val itemHeight = 48.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val listState = rememberLazyListState()
    val localItems = remember(items) { mutableStateListOf<String>().apply { addAll(items) } }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    // Fixed height: nested inside LazyVerticalGrid it needs a bounded height or infinite constraints crash
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight * localItems.size)
    ) {
        itemsIndexed(localItems, key = { _, name -> name }) { index, name ->
            val isDragging = draggingIndex == index
            val isLocked = name in locked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .animateItem()
            ) {
                Text(
                    text = stringResource(id = labels.first { it.second == name }.first),
                    modifier = Modifier.weight(1f)
                )
                if (moveLabel != null && onMove != null && moveEnabled(name)) {
                    TextButton(onClick = { onMove(name) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(moveLabel, fontSize = 11.sp)
                    }
                }
                if (isLocked) {
                    // Locked row (Settings): no handle, pinned last
                    Text(
                        text = stringResource(id = R.string.prefs_ui_order_locked),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .padding(20.dp)
                            .pointerInput(name) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = localItems.indexOf(name) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.y
                                        val currentIndex = draggingIndex
                                        val target = (currentIndex + (dragOffset / itemHeightPx).roundToInt())
                                            .coerceIn(0, localItems.size - 1)
                                        if (target != currentIndex) {
                                            localItems.add(target, localItems.removeAt(currentIndex))
                                            dragOffset += (currentIndex - target) * itemHeightPx
                                            draggingIndex = target
                                        }
                                    },
                                    onDragEnd = {
                                        onReorder(localItems.toList())
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    }
                                )
                            }
                    ) {
                        // Dot handle (touch area padding 20dp centered -> 8dp dot)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompassOffsetRow(
    labelResId: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Text(text = "${value.toInt()}°")
    }
    Spacer(modifier = Modifier.height(4.dp))
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range
    )
}

@Composable
private fun SwitchRow(labelResId: Int, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RowScope.SwitchTile(labelResId: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = stringResource(id = labelResId),
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun formatUpdateTime(updateTime: Long): String {
    val timePattern = stringResource(id = R.string.prefs_updated_time)
    val placeholder = stringResource(id = R.string.pass_time_placeholder)
    val updateDate = remember(updateTime) {
        if (updateTime != 0L) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(updateTime))
        } else {
            placeholder
        }
    }
    return stringResource(id = R.string.prefs_updated_title, updateDate)
}

@Composable
private fun UpdateIndicator(isUpdating: Boolean, modifier: Modifier = Modifier) = if (isUpdating) {
    LinearProgressIndicator(modifier = modifier.padding(start = 6.dp))
} else {
    LinearProgressIndicator(
        progress = { 0f },
        drawStopIndicator = {},
        modifier = modifier.padding(start = 6.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun CardCreditsPreview() = MainTheme { CardCredits() }

@Composable
private fun CardCredits(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth()

    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_outro_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_thanks)
            )
            // Keep roughly two lines of spacing between the thanks list and the license block (user request)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(id = R.string.prefs_outro_license),
                color = MaterialTheme.colorScheme.primary
            )
            // AGPL-3.0 attribution for the vendored DeepCW model.
            // See feature/cw/licenses/NOTICE.md for the full notice.
            Text(
                text = stringResource(id = R.string.prefs_outro_deepcw),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopCard(onClick: () -> Unit, modifier: Modifier = Modifier, version: String) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(
                painter = painterResource(id = R.drawable.ic_sputnik),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.prefs_app_title, version),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun BotCard(onClick: () -> Unit, resId: Int, text: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = resId), contentDescription = null)
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

// region Dialog visibility state holder

@Stable
private class DialogVisibility {
    var position by mutableStateOf(false)
    var locator by mutableStateOf(false)
    var dataSources by mutableStateOf(false)
    var network by mutableStateOf(false)
    var bluetooth by mutableStateOf(false)
    var radioControl by mutableStateOf(false)
    var whatsNew by mutableStateOf(false)
}

@Composable
private fun rememberDialogVisibility(): DialogVisibility {
    return rememberSaveable(saver = run {
        androidx.compose.runtime.saveable.Saver(
            save = {
                listOf(it.position, it.locator, it.dataSources, it.network, it.bluetooth, it.radioControl, it.whatsNew)
            },
            restore = {
                DialogVisibility().apply {
                    position = it[0]; locator = it[1]; dataSources = it[2]
                    network = it[3]; bluetooth = it[4]; radioControl = it[5]; whatsNew = it[6]
                }
            }
        )
    }) { DialogVisibility() }
}

// endregion

// region Permission launchers holder

@Stable
private class SettingsPermissions(
    val launchLocation: () -> Unit,
    val launchTleImport: () -> Unit,
    val launchTransceiverImport: () -> Unit,
    val launchBluetooth: () -> Unit,
    val launchNetwork: () -> Unit,
    val launchCustomSourcesPermission: () -> Unit
)

@Composable
private fun rememberSettingsPermissions(
    sendAction: (SettingsAction) -> Unit,
    onBluetoothGranted: () -> Unit,
    onNetworkGranted: () -> Unit,
    onCustomSourcesPermissionGranted: () -> Unit,
    onCustomSourcesPermissionDenied: () -> Unit
): SettingsPermissions {
    val locationError = stringResource(R.string.prefs_loc_gps_error)
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) sendAction(SettingsAction.SetGpsPosition)
        else sendAction(SettingsAction.ShowToast(locationError))
    }

    val satellitesImportError = stringResource(R.string.prefs_data_import_satellites_error)
    val transceiversImportError = stringResource(R.string.prefs_data_import_transceivers_error)

    val tleRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTLEFromFile(it.toString(), satellitesImportError)) }
    }

    val transceiversRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTransceiversFromFile(it.toString(), transceiversImportError)) }
    }

    val bluetoothError = stringResource(R.string.prefs_bt_perm_error)
    val bluetoothPerm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH_CONNECT
    val bluetoothRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onBluetoothGranted()
        else sendAction(SettingsAction.ShowToast(bluetoothError))
    }

    val networkError = stringResource(R.string.prefs_net_perm_error)
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onNetworkGranted()
        else sendAction(SettingsAction.ShowToast(networkError))
    }

    val customSourcesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onCustomSourcesPermissionGranted()
        } else {
            onCustomSourcesPermissionDenied()
            sendAction(SettingsAction.ShowToast(satellitesImportError))
        }
    }

    return remember {
        SettingsPermissions(
            launchLocation = {
                locationRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchTleImport = { tleRequest.launch("*/*") },
            launchTransceiverImport = { transceiversRequest.launch("*/*") },
            launchBluetooth = { bluetoothRequest.launch(bluetoothPerm) },
            launchNetwork = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    networkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onNetworkGranted()
                }
            },
            launchCustomSourcesPermission = {
                val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                customSourcesRequest.launch(storagePerm)
            }
        )
    }
}

// endregion
