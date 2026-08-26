package com.rtbishop.look4sat.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import com.rtbishop.look4sat.core.domain.aprs.AprsSymbols
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.data.aprs.AprsConfig
import com.rtbishop.look4sat.core.data.aprs.AprsStore
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.R

/** APRS card: sits between the satellite-data and data-output containers; settings live in a gear dialog to save space */
/**
 * Where an operator obtains an APRS-IS passcode.
 *
 * The app links here rather than deriving one: the passcode is a licence check, and APRS-IS
 * states that supplying it is the software author's responsibility.
 */
private const val PASSCODE_REQUEST_URL = "https://apps.magicbug.co.uk/passcode/"

@Composable
fun AprsCard() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AprsStore.loadConfig(context)) }
    var showDialog by remember { mutableStateOf(false) }
    var lastReport by remember { mutableStateOf(AprsStore.loadLastReport(context)) }
    var reportPing by remember { mutableStateOf(0) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Title row: title + gear key (dialog, space-saving)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.prefs_aprs_title),
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = { showDialog = true }) {
                    Text(
                        text = "\u2699", // ⚙
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp
                    )
                }
            }
            // Enable switch
            AprsSwitchRow(R.string.prefs_aprs_enable, config.enabled) { enabled ->
                // The service refuses to run without a callsign (it toasts and
                // calls stopSelf), but it never writes enabled=false back. Saving
                // enabled=true here would leave the switch stuck on while nothing
                // is reporting, so send the user to the settings dialog instead.
                if (enabled && config.callsign.isBlank()) {
                    showDialog = true
                    return@AprsSwitchRow
                }
                config = config.copy(enabled = enabled)
                AprsStore.saveConfig(context, config)
                if (enabled) requestNotifPermissionIfNeeded()
                val intent = Intent()
                    .setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                    .setAction(if (enabled) AprsStore.ACTION_START else AprsStore.ACTION_STOP)
                if (enabled) context.startForegroundService(intent) else context.startService(intent)
            }
            // Compact info row (not crowded)
            Text(
                text = stringResource(
                    id = R.string.prefs_aprs_summary,
                    config.server, config.port,
                    if (config.callsign.isBlank()) "-" else config.callsign
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Last-report status row
            val lr = lastReport
            if (lr.time > 0L) {
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(lr.time))
                Text(
                    text = stringResource(
                        id = R.string.prefs_aprs_last_report,
                        timeStr,
                        if (lr.ok) stringResource(R.string.prefs_aprs_last_ok)
                        else stringResource(R.string.prefs_aprs_last_fail, lr.detail)
                    ),
                    fontSize = 13.sp,
                    color = if (lr.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            // Manual report button
            CardButton(
                onClick = {
                    val intent = Intent()
                        .setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                        .setAction(AprsStore.ACTION_REPORT_NOW)
                    context.startForegroundService(intent)
                    reportPing++
                },
                text = stringResource(id = R.string.prefs_aprs_report_now),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    // Refresh the status row after reporting (wait for the result to persist)
    androidx.compose.runtime.LaunchedEffect(reportPing) {
        if (reportPing > 0) {
            kotlinx.coroutines.delay(6000)
            lastReport = AprsStore.loadLastReport(context)
        }
    }
    if (showDialog) {
        AprsSettingsDialog(
            config = config,
            onDismiss = { showDialog = false },
            onSave = { newConfig ->
                config = newConfig
                AprsStore.saveConfig(context, newConfig)
                // Apply new config when enabled (restart the service)
                if (newConfig.enabled) {
                    context.startService(
                        Intent().setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                            .setAction(AprsStore.ACTION_STOP)
                    )
                    context.startForegroundService(
                        Intent().setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                            .setAction(AprsStore.ACTION_START)
                    )
                }
                showDialog = false
            }
        )
    }
}

/** APRS settings dialog (plenty of room, full config) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AprsSettingsDialog(
    config: AprsConfig,
    onDismiss: () -> Unit,
    onSave: (AprsConfig) -> Unit
) {
    var server by remember { mutableStateOf(config.server) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var callsign by remember { mutableStateOf(config.callsign) }
    var ssid by remember { mutableStateOf(config.ssid) }
    var passcode by remember { mutableStateOf(config.passcode) }
    var interval by remember { mutableStateOf(config.intervalMin.toString()) }
    var status by remember { mutableStateOf(config.statusText) }
    var symbolTable by remember { mutableStateOf(config.symbolTable) }
    var symbolCode by remember { mutableStateOf(config.symbolCode) }
    var symbolMenuExpanded by remember { mutableStateOf(false) }
    val textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.prefs_aprs_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(id = R.string.prefs_aprs_server)) },
                    singleLine = true,
                    textStyle = textStyle
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(id = R.string.prefs_aprs_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = textStyle
                )
                OutlinedTextField(
                    value = callsign,
                    onValueChange = { callsign = it },
                    label = { Text(stringResource(id = R.string.prefs_aprs_callsign)) },
                    singleLine = true,
                    textStyle = textStyle
                )
                // Links out instead of computing it. APRS-IS treats the passcode as a licence
                // check and says supplying it to a user is the software author's job; APRSdroid
                // carries the same algorithm and deliberately does not use it here for that
                // reason. Filling the field in claims a check that nobody performed.
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(PASSCODE_REQUEST_URL))
                            )
                        }
                    }
                ) { Text(stringResource(id = R.string.prefs_aprs_request_passcode)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_ssid)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = textStyle
                    )
                    OutlinedTextField(
                        value = passcode,
                        onValueChange = { passcode = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_passcode)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = textStyle
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_interval)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = textStyle
                    )
                    OutlinedTextField(
                        value = status,
                        onValueChange = { status = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_status)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = textStyle
                    )
                }
                // A list rather than two free-text fields. The fields accepted any string and used
                // only the first character, so typing "satellite" persisted the word and beaconed
                // as `/`. The pair that makes this worth doing: \S is Satellite but /S is SHUTTLE.
                val selected = AprsSymbols.find(symbolTable, symbolCode)
                val customLabel = stringResource(
                    id = R.string.prefs_aprs_symbol_custom,
                    symbolTable.take(1),
                    symbolCode.take(1)
                )
                val selectedLabel = selected?.let { symbolLabel(it.descriptionKey) } ?: customLabel
                ExposedDropdownMenuBox(
                    expanded = symbolMenuExpanded,
                    onExpandedChange = { symbolMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.prefs_aprs_symbol)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = symbolMenuExpanded)
                        },
                        textStyle = textStyle,
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = symbolMenuExpanded,
                        onDismissRequest = { symbolMenuExpanded = false }
                    ) {
                        // An existing setting that is not on the list appears first and stays
                        // selectable, so opening the menu cannot silently change it.
                        if (selected == null) {
                            DropdownMenuItem(
                                text = { Text(text = customLabel, maxLines = 1) },
                                onClick = { symbolMenuExpanded = false }
                            )
                        }
                        AprsSymbols.curated.forEach { symbol ->
                            DropdownMenuItem(
                                text = { Text(text = symbolLabel(symbol.descriptionKey), maxLines = 1) },
                                onClick = {
                                    symbolTable = symbol.table.toString()
                                    symbolCode = symbol.code.toString()
                                    symbolMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(id = R.string.prefs_aprs_symbol_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(id = R.string.prefs_aprs_passcode_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    config.copy(
                        // A configuration with no callsign cannot be enabled:
                        // AprsForegroundService rejects it immediately. Persisting
                        // enabled=true here would recreate the same "switch on,
                        // service stopped" state when an existing callsign is erased.
                        enabled = config.enabled && callsign.trim().isNotBlank(),
                        server = server.ifBlank { "euro.aprs2.net" },
                        port = port.toIntOrNull() ?: 14580,
                        callsign = callsign.trim().uppercase(),
                        ssid = ssid.trim(),
                        passcode = passcode.trim(),
                        intervalMin = interval.toIntOrNull() ?: 5,
                        statusText = status,
                        symbolTable = symbolTable.ifBlank { "/" },
                        symbolCode = symbolCode.ifBlank { "-" }
                    )
                )
            }) { Text(stringResource(id = R.string.prefs_aprs_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.prefs_aprs_cancel)) }
        }
    )
}

/** Switch row local to this file (same as SettingsScreen's private SwitchRow) */
@Composable
private fun AprsSwitchRow(labelResId: Int, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Resolve a symbol's description key to its localised text.
 *
 * AprsSymbols names a resource rather than holding text, because core:domain cannot reach resources
 * and hardcoding English there would put wording outside the locale files. The mapping has to live
 * on this side, and an unknown key falls back to the key itself rather than crashing - a missing
 * translation should not take the settings screen down.
 */
@Composable
private fun symbolLabel(descriptionKey: String): String = when (descriptionKey) {
    "aprs_symbol_house" -> stringResource(id = R.string.aprs_symbol_house)
    "aprs_symbol_house_alt" -> stringResource(id = R.string.aprs_symbol_house_alt)
    "aprs_symbol_person" -> stringResource(id = R.string.aprs_symbol_person)
    "aprs_symbol_yagi" -> stringResource(id = R.string.aprs_symbol_yagi)
    "aprs_symbol_satellite" -> stringResource(id = R.string.aprs_symbol_satellite)
    "aprs_symbol_portable" -> stringResource(id = R.string.aprs_symbol_portable)
    "aprs_symbol_phone" -> stringResource(id = R.string.aprs_symbol_phone)
    "aprs_symbol_tcpip" -> stringResource(id = R.string.aprs_symbol_tcpip)
    "aprs_symbol_ht" -> stringResource(id = R.string.aprs_symbol_ht)
    "aprs_symbol_car" -> stringResource(id = R.string.aprs_symbol_car)
    "aprs_symbol_truck" -> stringResource(id = R.string.aprs_symbol_truck)
    "aprs_symbol_van" -> stringResource(id = R.string.aprs_symbol_van)
    "aprs_symbol_rv" -> stringResource(id = R.string.aprs_symbol_rv)
    "aprs_symbol_bike" -> stringResource(id = R.string.aprs_symbol_bike)
    else -> descriptionKey
}
