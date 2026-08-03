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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.presentation.CardButton

private val InfoCyan = Color(0xFF0CCFE2)
private val GridBlue = Color(0xFF12ACE0)
private val LabelBlue = Color(0xFF135678)
private val PositionRed = Color(0xFFFF0000)
private val DividerWhite = Color(0xFFF7F7F7)

@Composable
fun RoamingScreen(viewModel: RoamingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        InfoHeader(state = state, onGpsClick = viewModel::onGpsClick)
        GridPanel(state = state)
    }
}

@Composable
private fun InfoHeader(state: RoamingState, onGpsClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InfoCyan)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Top row: GPS status + big locator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GPS",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (state.qthLocator != "----") Color(0xFF3BAF52) else Color.Gray)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = state.qthLocator,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.Black
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Lat / Lon rows with DMS + decimal
        CoordinateRow(label = "Lat", value = state.latitude, isLat = true)
        Spacer(modifier = Modifier.height(4.dp))
        CoordinateRow(label = "Lon", value = state.longitude, isLat = false)
        Spacer(modifier = Modifier.height(10.dp))
        CardButton(
            onClick = onGpsClick,
            text = "GPS 定位",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CoordinateRow(label: String, value: Double, isLat: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black,
            modifier = Modifier.width(44.dp)
        )
        Text(
            text = formatDms(value, isLat),
            fontSize = 15.sp,
            color = Color.Black
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${formatDecimal(value)}°",
            fontSize = 14.sp,
            color = LabelBlue
        )
    }
}

@Composable
private fun GridPanel(state: RoamingState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GridBlue)
            .padding(4.dp)
    ) {
        // 3x3 grid: row 0 = north
        for (row in 0..2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0..2) {
                    val index = row * 3 + col
                    val isCenter = index == 4
                    GridCell(
                        label = state.gridSquares.getOrElse(index) { "----" },
                        isCenter = isCenter,
                        showMarker = isCenter && state.qthLocator != "----",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (row < 2) Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun GridCell(
    label: String,
    isCenter: Boolean,
    showMarker: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .background(if (isCenter) InfoCyan else GridBlue)
            .border(1.dp, DividerWhite)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = if (isCenter) 26.sp else 16.sp,
                fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                color = LabelBlue,
                textAlign = TextAlign.Center
            )
            if (showMarker) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(PositionRed)
                        .border(2.dp, DividerWhite)
                )
            }
        }
    }
}

private fun formatDms(value: Double, isLat: Boolean): String {
    val abs = kotlin.math.abs(value)
    val deg = abs.toInt()
    val minFull = (abs - deg) * 60
    val min = minFull.toInt()
    val sec = ((minFull - min) * 60).toInt()
    val hemi = if (value >= 0) (if (isLat) "N" else "E") else (if (isLat) "S" else "W")
    return "$deg° $min' $sec\" $hemi"
}

private fun formatDecimal(value: Double): String = String.format("%.5f", value)
