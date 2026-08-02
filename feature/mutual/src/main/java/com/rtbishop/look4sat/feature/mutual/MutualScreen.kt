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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MutualScreen(
    viewModel: MutualViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title
        item {
            Text(
                text = "对台过境查询",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "查看双方同时过境的卫星，然后相约 QSO 吧！",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Error message
        val errorMsg = state.errorMessage
        if (errorMsg != null) {
            item {
                Text(
                    text = errorMsg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Station A input
        item {
            Text("你的位置", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.stationALat,
                    onValueChange = viewModel::onStationALat,
                    label = { Text("纬度") },
                    placeholder = { Text("例如: 39.9042") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.stationALon,
                    onValueChange = viewModel::onStationALon,
                    label = { Text("经度") },
                    placeholder = { Text("例如: 116.4074") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("最小仰角：${state.stationAMinElev.toInt()}°", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = state.stationAMinElev.toFloat(),
                onValueChange = { viewModel.onStationAMinElev(it.toDouble()) },
                valueRange = 0f..90f,
                steps = 17,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Station B input
        item {
            Text("友台位置", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.stationBLat,
                    onValueChange = viewModel::onStationBLat,
                    label = { Text("纬度") },
                    placeholder = { Text("例如: 34.0522") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.stationBLon,
                    onValueChange = viewModel::onStationBLon,
                    label = { Text("经度") },
                    placeholder = { Text("例如: -118.2437") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("最小仰角：${state.stationBMinElev.toInt()}°", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = state.stationBMinElev.toFloat(),
                onValueChange = { viewModel.onStationBMinElev(it.toDouble()) },
                valueRange = 0f..90f,
                steps = 17,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Time range
        item {
            Text("时间范围", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(6, 12, 24, 48, 72).forEach { hours ->
                    FilterChip(
                        selected = state.hoursAhead == hours,
                        onClick = { viewModel.onHoursAhead(hours) },
                        label = { Text("${hours}h") }
                    )
                }
            }
        }

        // Query button
        item {
            Button(
                onClick = { viewModel.queryMutualPasses() },
                enabled = !state.isCalculating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isCalculating) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isCalculating) "计算中..." else "查询过境")
            }
        }

        // Results
        if (state.mutualPasses.isNotEmpty()) {
            item {
                Text(
                    text = "找到 ${state.mutualPasses.size} 个共同过境",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            itemsIndexed(state.mutualPasses) { index, pass ->
                MutualPassCard(
                    pass = pass,
                    isExpanded = state.selectedPassIndex == index,
                    timeFormat = timeFormat,
                    onToggle = { viewModel.onSelectPass(if (state.selectedPassIndex == index) -1 else index) }
                )
            }
        }
    }
}

@Composable
private fun MutualPassCard(
    pass: MutualPass,
    isExpanded: Boolean,
    timeFormat: SimpleDateFormat,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = pass.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "${timeFormat.format(Date(pass.startTime))} - ${timeFormat.format(Date(pass.endTime))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "你: ${pass.maxElevationA}°  友台: ${pass.maxElevationB}°",
                style = MaterialTheme.typography.bodySmall
            )

            if (isExpanded && pass.elevationSamples.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ElevationCurveChart(
                    samples = pass.elevationSamples,
                    startTime = pass.startTime,
                    endTime = pass.endTime,
                    maxElev = maxOf(pass.maxElevationA, pass.maxElevationB, 10.0)
                )
            }
        }
    }
}