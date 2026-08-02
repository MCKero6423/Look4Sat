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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.repository.TrackSampleData
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MutualScreen(
    viewModel: MutualViewModel,
    navigateUp: () -> Unit = {},
    navigateToRadar: (Int, Long, MutualPassData?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    ScreenColumn(
        topBar = { isVertical ->
            TopBar(
                isVerticalLayout = isVertical,
                startAction = {
                    IconCard(action = navigateUp, resId = R.drawable.ic_back)
                },
                topInfo = {
                    Text(
                        text = "对台过境",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                bottomInfo = {
                    if (state.mutualPasses.isNotEmpty()) {
                        Text(
                            text = "找到 ${state.mutualPasses.size} 个过境",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                endAction = {
                    if (state.isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(24.dp)
                                .width(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        }
    ) { isVertical ->
        MutualContent(
            state = state,
            isVertical = isVertical,
            onQuery = viewModel::queryMutualPasses,
            onSelectPass = viewModel::onSelectPass,
            onNavigateToRadar = navigateToRadar,
            onStationALat = viewModel::onStationALat,
            onStationALon = viewModel::onStationALon,
            onStationAGrid = viewModel::onStationAGrid,
            onStationAMinElev = viewModel::onStationAMinElev,
            onStationBLat = viewModel::onStationBLat,
            onStationBLon = viewModel::onStationBLon,
            onStationBGrid = viewModel::onStationBGrid,
            onStationBMinElev = viewModel::onStationBMinElev,
            onHoursAhead = viewModel::onHoursAhead,
            onClearError = viewModel::clearError
        )
    }
}

@Composable
private fun MutualContent(
    state: MutualUiState,
    isVertical: Boolean,
    onQuery: () -> Unit,
    onSelectPass: (Int) -> Unit,
    onNavigateToRadar: (Int, Long, MutualPassData?) -> Unit,
    onStationALat: (String) -> Unit,
    onStationALon: (String) -> Unit,
    onStationAGrid: (String) -> Unit,
    onStationAMinElev: (Double) -> Unit,
    onStationBLat: (String) -> Unit,
    onStationBLon: (String) -> Unit,
    onStationBGrid: (String) -> Unit,
    onStationBMinElev: (Double) -> Unit,
    onHoursAhead: (Int) -> Unit,
    onClearError: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Error message
        val errorMsg = state.errorMessage
        if (errorMsg != null) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Input form
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "你的位置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.stationALat,
                            onValueChange = onStationALat,
                            label = { Text("纬度") },
                            placeholder = { Text("39.9042") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.stationALon,
                            onValueChange = onStationALon,
                            label = { Text("经度") },
                            placeholder = { Text("116.4074") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.stationAGrid,
                        onValueChange = onStationAGrid,
                        label = { Text("网格（4/6/8位，填此可省略经纬度）") },
                        placeholder = { Text("ON79uj") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "最小仰角：${state.stationAMinElev.toInt()}°",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = state.stationAMinElev.toFloat(),
                        onValueChange = { onStationAMinElev(it.toDouble()) },
                        valueRange = 0f..90f,
                        steps = 17
                    )

                    HorizontalDivider()

                    Text(
                        text = "友台位置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.stationBLat,
                            onValueChange = onStationBLat,
                            label = { Text("纬度") },
                            placeholder = { Text("34.0522") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.stationBLon,
                            onValueChange = onStationBLon,
                            label = { Text("经度") },
                            placeholder = { Text("-118.2437") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.stationBGrid,
                        onValueChange = onStationBGrid,
                        label = { Text("网格（4/6/8位，填此可省略经纬度）") },
                        placeholder = { Text("PM01tv") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "最小仰角：${state.stationBMinElev.toInt()}°",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = state.stationBMinElev.toFloat(),
                        onValueChange = { onStationBMinElev(it.toDouble()) },
                        valueRange = 0f..90f,
                        steps = 17
                    )

                    HorizontalDivider()

                    Text(
                        text = "时间范围",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(6, 12, 24, 48, 72).forEach { hours ->
                            FilterChip(
                                selected = state.hoursAhead == hours,
                                onClick = { onHoursAhead(hours) },
                                label = { Text("${hours}h") }
                            )
                        }
                    }

                    Button(
                        onClick = onQuery,
                        enabled = !state.isCalculating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(18.dp)
                                    .width(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isCalculating) "计算中..." else "查询过境")
                    }
                }
            }
        }

        // Results
        itemsIndexed(state.mutualPasses) { index, pass ->
            val mutualData = MutualPassData(
                samples = pass.elevationSamples,
                trackSamples = pass.trackSamples.map {
                    TrackSampleData(
                        azimuthA = it.azimuthA,
                        elevationA = it.elevationA,
                        azimuthB = it.azimuthB,
                        elevationB = it.elevationB
                    )
                },
                startTime = pass.startTime,
                endTime = pass.endTime,
                maxElev = maxOf(pass.maxElevationA, pass.maxElevationB, 10.0),
                labelA = "你",
                labelB = "友台"
            )
            MutualPassCard(
                pass = pass,
                isExpanded = state.selectedPassIndex == index,
                timeFormat = timeFormat,
                onClick = { onSelectPass(if (state.selectedPassIndex == index) -1 else index) },
                onNavigateToRadar = { onNavigateToRadar(pass.catNum, pass.startTime, mutualData) }
            )
        }
    }
}

@Composable
private fun MutualPassCard(
    pass: MutualPass,
    isExpanded: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onNavigateToRadar: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pass.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${timeFormat.format(Date(pass.startTime))} - ${timeFormat.format(Date(pass.endTime))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "你: ${pass.maxElevationA}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "友台: ${pass.maxElevationB}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    // Dual-station radar track (polar plot)
                    if (pass.trackSamples.isNotEmpty()) {
                        Text(
                            text = "双方轨迹（雷达图）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MutualRadarView(
                            trackSamples = pass.trackSamples,
                            labelA = "你",
                            labelB = "友台"
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (pass.elevationSamples.isNotEmpty()) {
                        ElevationCurveChart(
                            samples = pass.elevationSamples,
                            startTime = pass.startTime,
                            endTime = pass.endTime,
                            maxElev = maxOf(pass.maxElevationA, pass.maxElevationB, 10.0)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToRadar,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_radar),
                            contentDescription = null,
                            modifier = Modifier.height(16.dp).width(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("查看雷达")
                    }
                }
            }
        }
    }
}