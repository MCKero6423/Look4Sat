package com.rtbishop.look4sat.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
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

/** APRS 卡片:卫星数据容器与数据输出容器之间;设置键弹窗收纳,不挤 */
@Composable
fun AprsCard() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(AprsStore.loadConfig(context)) }
    var showDialog by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 标题行:标题 + 设置键(弹窗,省空间)
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
            // 启用开关
            AprsSwitchRow(R.string.prefs_aprs_enable, config.enabled) { enabled ->
                config = config.copy(enabled = enabled)
                AprsStore.saveConfig(context, config)
                val intent = Intent()
                    .setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                    .setAction(if (enabled) AprsStore.ACTION_START else AprsStore.ACTION_STOP)
                context.startService(intent)
            }
            // 简洁信息行(不挤)
            Text(
                text = stringResource(
                    id = R.string.prefs_aprs_summary,
                    config.server, config.port.toString(),
                    if (config.callsign.isBlank()) "-" else config.callsign
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 手动上报按钮
            CardButton(
                onClick = {
                    val intent = Intent()
                        .setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                        .setAction(AprsStore.ACTION_REPORT_NOW)
                    context.startService(intent)
                },
                text = stringResource(id = R.string.prefs_aprs_report_now),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (showDialog) {
        AprsSettingsDialog(
            config = config,
            onDismiss = { showDialog = false },
            onSave = { newConfig ->
                config = newConfig
                AprsStore.saveConfig(context, newConfig)
                // 已启用时应用新配置(重启服务)
                if (newConfig.enabled) {
                    context.startService(
                        Intent().setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                            .setAction(AprsStore.ACTION_STOP)
                    )
                    context.startService(
                        Intent().setClassName(context.packageName, AprsStore.SERVICE_CLASS)
                            .setAction(AprsStore.ACTION_START)
                    )
                }
                showDialog = false
            }
        )
    }
}

/** APRS 设置弹窗(空间充裕,配置项齐全) */
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
    val textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = symbolTable,
                        onValueChange = { symbolTable = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_symbol_table)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = textStyle
                    )
                    OutlinedTextField(
                        value = symbolCode,
                        onValueChange = { symbolCode = it },
                        label = { Text(stringResource(id = R.string.prefs_aprs_symbol_code)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = textStyle
                    )
                }
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
                        server = server.ifBlank { "euro.aprs2.net" },
                        port = port.toIntOrNull() ?: 14580,
                        callsign = callsign.trim().uppercase(),
                        ssid = ssid.trim(),
                        passcode = passcode.trim(),
                        intervalMin = interval.toIntOrNull() ?: 5,
                        statusText = status,
                        symbolTable = symbolTable.ifBlank { "/" },
                        symbolCode = symbolCode.ifBlank { ">" }
                    )
                )
            }) { Text(stringResource(id = R.string.prefs_aprs_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.prefs_aprs_cancel)) }
        }
    )
}

/** 本文件内开关行(与 SettingsScreen 的私有 SwitchRow 一致) */
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
