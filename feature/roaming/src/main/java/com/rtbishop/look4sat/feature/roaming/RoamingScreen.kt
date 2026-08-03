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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
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
 *
 * Coordinates are read straight from the shared settingsRepo.stationPosition
 * StateFlow — the same source the Settings page uses — so the page shows
 * exactly the station position (站位), whatever the GPS says there. No
 * automatic location polling on this page.
 */
@Composable
fun RoamingScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val stationPos by container.settingsRepo.stationPosition.collectAsStateWithLifecycle()

    val state = remember(stationPos) {
        RoamingState.fromPosition(stationPos)
    }

    ScreenColumn(
        topBar = {
            TopBar { Text(text = "漫游", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    ) {
        val spacing = LocalSpacing.current
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            InfoCard(state = state)
            GridCard(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoCard(state: RoamingState) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar: GPS status + date + time
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GPS",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSecondaryContainer
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
                color = colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSecondaryContainer
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
            color = colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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
 * 3x3 grid of 4-char squares — a faithful continuous-table port: no outer
 * card, no rounded cells, no gaps. Cells are connected edge-to-edge and
 * separated by thin divider lines (crossing at right angles like a real
 * coordinate grid). Column widths 21.4% : 56.2% : 21.4%, row heights
 * 31.5% : 35.9% : 31.7% mirror the reference app: the center column is
 * ~2.6x wider and the center row is the tallest, so the current square
 * dominates the panel.
 */
@Composable
private fun GridCard(state: RoamingState, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val dividerColor = colorScheme.outline
    Column(
        modifier = modifier
            .fillMaxSize()
            .border(2.dp, dividerColor)
    ) {
        GridRow(state, row = 0, weight = 0.315f, dividerColor = dividerColor)
        Divider(color = dividerColor, thickness = 2.dp)
        GridRow(state, row = 1, weight = 0.359f, dividerColor = dividerColor)
        Divider(color = dividerColor, thickness = 2.dp)
        GridRow(state, row = 2, weight = 0.317f, dividerColor = dividerColor)
    }
}

@Composable
private fun ColumnScope.GridRow(
    state: RoamingState,
    row: Int,
    weight: Float,
    dividerColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight)
    ) {
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 0) { "----" },
            isCenter = false,
            dividerColor = dividerColor,
            modifier = Modifier.weight(0.214f)
        )
        Divider(
            color = dividerColor, thickness = 2.dp,
            modifier = Modifier.fillMaxHeight().width(2.dp)
        )
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 1) { "----" },
            isCenter = row == 1,
            markerX = state.markerX,
            markerY = state.markerY,
            showMarker = row == 1 && state.qthLocator != "----",
            dividerColor = dividerColor,
            modifier = Modifier.weight(0.562f)
        )
        Divider(
            color = dividerColor, thickness = 2.dp,
            modifier = Modifier.fillMaxHeight().width(2.dp)
        )
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 2) { "----" },
            isCenter = false,
            dividerColor = dividerColor,
            modifier = Modifier.weight(0.214f)
        )
    }
}

@Composable
private fun GridCell(
    label: String,
    isCenter: Boolean,
    markerX: Float = 0.5f,
    markerY: Float = 0.5f,
    showMarker: Boolean = false,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val cellBackground = if (isCenter) colorScheme.secondaryContainer else colorScheme.surfaceVariant
    val labelColor = if (isCenter) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(cellBackground)
    ) {
        // Capture constraints at the BoxWithConstraints scope (Column scope would shadow them)
        val width = maxWidth
        val height = maxHeight
        if (isCenter) {
            // Center cell: locator text at upper-middle, red marker below it,
            // never overlapping — exactly like the reference app.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = with(LocalDensity.current) { (-height * 0.08f).toPx().toDp() })
            ) {
                Text(
                    text = label,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                    textAlign = TextAlign.Center
                )
                if (showMarker) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val density = LocalDensity.current
                    val dotSize = 14.dp
                    val offsetX = with(density) { (width * markerX).toPx().toDp() - width * 0.5f - dotSize / 2 }
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(colorScheme.error)
                            .offset(x = offsetX)
                    )
                }
            }
        } else {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
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
