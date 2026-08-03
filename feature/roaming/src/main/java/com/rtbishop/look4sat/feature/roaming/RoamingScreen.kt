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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthNeighbors
import com.rtbishop.look4sat.core.domain.utility.qthToSquare
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
 * Live GPS: while the screen is shown (and the "漫游位置实时更新" setting is on)
 * a LocationManager listener requests gps+network fixes every 10s / 10m,
 * exactly like the reference app; each fix is pushed through the shared
 * settingsRepo.stationPosition StateFlow, so the Settings page and every
 * other consumer update in lockstep. The red marker is placed at the
 * fractional position derived from the 3rd character pair of the locator
 * (ported from the QTH定位器 app), scaled to the actual cell size, so it
 * stays accurate on any screen (phones, tablets, wide/narrow).
 */
@SuppressLint("MissingPermission")
@Composable
fun RoamingScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val settingsRepo = container.settingsRepo
    val stationPos by settingsRepo.stationPosition.collectAsStateWithLifecycle()
    val otherSettings by settingsRepo.otherSettings.collectAsStateWithLifecycle()

    val state = remember(stationPos, otherSettings.stateOfRoamingLive) {
        RoamingState.fromPosition(stationPos)
    }

    // Real-time location updates, ported from the reference app's onResume/onPause:
    // register gps+network listeners while visible, remove them when leaving.
    var gpsAvailable by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val locationManager = context.getSystemService(LocationManager::class.java)
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        gpsAvailable = hasPermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Accept only real gps/network fixes, like the reference app
                if (location.provider != LocationManager.GPS_PROVIDER &&
                    location.provider != LocationManager.NETWORK_PROVIDER
                ) return
                settingsRepo.setStationPosition(location.latitude, location.longitude, location.altitude)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) gpsAvailable = true
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) gpsAvailable = false
            }
        }
        if (hasPermission) {
            // Listen continuously while the page is visible (10s / 10m, like the reference)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 10_000L, 10f, listener
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 10_000L, 10f, listener
            )
        }
        onDispose {
            if (hasPermission) locationManager.removeUpdates(listener)
        }
    }

    // Periodic refresh when live updates are enabled: re-request a fix every 30s
    // so the page also recovers after the GPS chip falls idle.
    LaunchedEffect(otherSettings.stateOfRoamingLive) {
        while (otherSettings.stateOfRoamingLive) {
            settingsRepo.setStationPosition()
            kotlinx.coroutines.delay(30_000L)
        }
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
            InfoCard(state = state, gpsAvailable = gpsAvailable)
            GridCard(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoCard(state: RoamingState, gpsAvailable: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
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
                        .background(
                            when {
                                state.gpsEnabled -> colorScheme.primary
                                gpsAvailable -> colorScheme.error
                                else -> colorScheme.outline
                            }
                        )
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
            // GPS disabled hint, ported from the reference app's btnLocationSettings:
            // opens the system location settings so the user can re-enable GPS.
            if (!gpsAvailable) {
                Text(
                    text = "定位未开启,点击前往系统设置",
                    fontSize = 13.sp,
                    color = colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                        .padding(vertical = 6.dp)
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
private fun GridCard(state: RoamingState, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GridRow(state, row = 0, weight = 0.315f)
            GridRow(state, row = 1, weight = 0.359f)
            GridRow(state, row = 2, weight = 0.317f)
        }
    }
}

@Composable
private fun ColumnScope.GridRow(state: RoamingState, row: Int, weight: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        GridCell(
            label = state.gridSquares.getOrElse(row * 3 + 0) { "----" },
            isCenter = false,
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
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val cellBackground = if (isCenter) colorScheme.secondaryContainer else colorScheme.surfaceVariant
    val labelColor = if (isCenter) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(cellBackground)
    ) {
        // Capture constraints at the BoxWithConstraints scope (Column scope would shadow them)
        val width = maxWidth
        val height = maxHeight
        Text(
            text = label,
            fontSize = if (isCenter) 22.sp else 13.sp,
            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
            color = labelColor,
            textAlign = TextAlign.Center
        )
        if (showMarker) {
            // Red marker: absolute position from the top-left of the cell.
            // markerX/markerY are 0..1 fractions (from the reference app's
            // 3rd-pair mapping), so offset = fraction * cell size, minus half
            // the marker size to center the 12dp square on the point.
            val density = LocalDensity.current
            val dotSize = 12.dp
            val offsetX = with(density) { (width * markerX).toPx().toDp() - dotSize / 2 }
            val offsetY = with(density) { (height * markerY).toPx().toDp() - dotSize / 2 }
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(colorScheme.error)
                    .offset(x = offsetX, y = offsetY)
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
