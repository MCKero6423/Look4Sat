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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Roaming (漫游) page: a faithful port of the QTH定位器 location panel —
 * GPS status + date/time header, DMS & decimal coordinates, the big 8-char
 * Maidenhead locator, and a 3x3 grid of neighboring squares (center column
 * and row are wider/taller, matching the reference app). Styled with the
 * Look4Sat Material 3 theme.
 */
@Composable
fun RoamingScreen(viewModel: RoamingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenColumn(
        topBar = {
            TopBar { Text(text = "漫游", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    ) {
        val spacing = LocalSpacing.current
        Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            InfoCard(state = state)
            GridCard(state = state)
        }
    }
}

@Composable
private fun InfoCard(state: RoamingState) {
    val colorScheme = MaterialTheme.colorScheme
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top bar: GPS status + date + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GPS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.gpsEnabled) colorScheme.primary else colorScheme.outline)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
            }
            // Coordinates: DMS + decimal
            CoordinateRow(label = "纬度", value = state.latitude, isLat = true)
            CoordinateRow(label = "经度", value = state.longitude, isLat = false)
            // Big locator, centered
            Text(
                text = state.qthLocator,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CoordinateRow(label: String, value: Double, isLat: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            modifier = Modifier.width(40.dp)
        )
        Text(text = formatDms(value, isLat), fontSize = 15.sp, color = colorScheme.onSurface)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${formatDecimal(value)}°",
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 3x3 grid of 4-char squares. Column widths 21.4% : 56.2% : 21.4% and row
 * heights 31.5% : 35.9% : 31.7% mirror the reference app: the center column
 * is ~2.6x wider and the center row is the tallest, so the current square
 * dominates the panel.
 */
@Composable
private fun GridCard(state: RoamingState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GridRow(state, row = 0, heightFraction = 0.315f)
            GridRow(state, row = 1, heightFraction = 0.359f)
            GridRow(state, row = 2, heightFraction = 0.317f)
        }
    }
}

@Composable
private fun GridRow(state: RoamingState, row: Int, heightFraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 0) { "----" },
            isCenter = false,
            markerX = state.markerX,
            markerY = state.markerY,
            modifier = Modifier.weight(0.214f)
        )
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 1) { "----" },
            isCenter = row == 1,
            markerX = state.markerX,
            markerY = state.markerY,
            showMarker = row == 1 && state.qthLocator != "----",
            modifier = Modifier.weight(0.562f)
        )
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 2) { "----" },
            isCenter = false,
            markerX = state.markerX,
            markerY = state.markerY,
            modifier = Modifier.weight(0.214f)
        )
    }
}

@Composable
private fun GridCell(
    label: String,
    isCenter: Boolean,
    markerX: Float,
    markerY: Float,
    showMarker: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val cellBackground = if (isCenter) colorScheme.secondaryContainer else colorScheme.surfaceVariant
    val labelColor = if (isCenter) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(84.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(cellBackground)
    ) {
        // Capture constraints at the BoxWithConstraints scope (Column scope would shadow them)
        val width = maxWidth
        val height = maxHeight
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = if (isCenter) 22.sp else 14.sp,
                fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                color = labelColor,
                textAlign = TextAlign.Center
            )
            if (showMarker) {
                Spacer(modifier = Modifier.height(4.dp))
                // Red marker at the fractional position inside the center cell
                val density = LocalDensity.current
                val offsetX = with(density) { (width * (markerX - 0.5f)).toPx().toDp() }
                val offsetY = with(density) { (height * (markerY - 0.5f)).toPx().toDp() }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(colorScheme.error)
                        .offset(x = offsetX, y = offsetY)
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
