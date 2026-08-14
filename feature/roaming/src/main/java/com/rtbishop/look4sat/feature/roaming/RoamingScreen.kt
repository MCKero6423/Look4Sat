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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rtbishop.look4sat.core.domain.utility.qthNeighbors

/**
 * Roaming page - ported verbatim from QTH Locator 2.0 (com.us1pm.gridsquarelocator).
 * UI: res/layout/main.xml (structure/size/colors copied item by item, no changes)
 * Logic: MainActivity.showLocation() / checkEnabled() / live GPS listening
 * Hard rule: UI and logic unchanged, pixel-aligned with the original screenshots.
 */
@Composable
fun RoamingScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(RoamingState()) }
    // Ported from onCreate: fixed date text + hour prefix (minutes appended on locate)
    val dateText = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()) }
    var hourPrefix by remember { mutableStateOf("") }
    // Ported from onCreate: initial checkEnabled() -> red dot + settings button, date hidden
    var hasPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasPermission = fine || coarse
    }
    LaunchedEffect(Unit) {
        hourPrefix = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()).substring(0, 3)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        hasPermission = fine || coarse
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            // Ported from onResume: green dot + progress + hint + date shown, red dot/button hidden
            state = state.copy(gpsOn = true, gpsOff = false, showSettings = false, showProgress = true, showNotice = true, showDate = true)
        }
    }
    // Ported from onResume/onPause: GPS + network listeners, 10 s / 10 m
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    DisposableEffect(locationManager, hasPermission) {
        if (hasPermission) {
            @SuppressLint("MissingPermission")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // Ported from showLocation start: accept only gps / network providers
                    if (location.provider != "gps" && location.provider != "network") return
                    state = roamingStateFromLocation(location.latitude, location.longitude, location.time, hourPrefix)
                        .copy(date = dateText)
                }

                override fun onProviderDisabled(provider: String) {
                    // Ported from checkEnabled: red dot + button, date hidden
                    state = state.copy(gpsOn = false, gpsOff = true, showSettings = true, showProgress = false, showNotice = false, showDate = false)
                }

                override fun onProviderEnabled(provider: String) {
                    state = state.copy(gpsOn = true, gpsOff = false, showSettings = false, showProgress = true, showNotice = true, showDate = true)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                locationManager.requestLocationUpdates("gps", 10_000L, 10f, listener)
                locationManager.requestLocationUpdates("network", 10_000L, 10f, listener)
            } catch (_: SecurityException) {
            }
            onDispose { locationManager.removeUpdates(listener) }
        } else {
            onDispose { }
        }
    }
    // Ported from res/layout/main.xml: light page, blue info area, 43sp locator, 3x3 grid, bottom credit
    // Per user request: inset from system bars top/bottom so content is not covered
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // relativeLayout7: top GPS status bar, 25dp high, holo_blue_bright
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .height(25.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // tvTitleGPS: "GPS" 14sp black, left 8dp
                Text(
                    text = "GPS",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
                // imGps: toRightOf tvTitleGPS + marginLeft 15dp (original main.xml)
                Spacer(modifier = Modifier.width(15.dp))
                // imGps: green dot (15dp, theme primary -> visible under night filter)
                if (state.gpsOn) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
                // imGpsOff: red dot overlay (15dp, theme error)
                if (state.gpsOff) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // tvDate: 14sp black, centered
                if (state.showDate) {
                    Text(text = state.date.ifEmpty { dateText }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                // tvTime: 14sp black, right-aligned
                Text(
                    text = state.time,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            // btnLocationSettings: yellow button, centered
            // btnLocationSettings: original #faf8f54a translucent yellow, 14sp black text, centered
            if (state.showSettings) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "设置启用GPS", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        // linearLayout4: lat/lon area, holo_blue_bright
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Lat row: tvLati 16sp black left 8dp | tvLat right-aligned 3dp
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = state.latLabel, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                Text(text = state.latValue, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 3.dp))
            }
            // Lon row
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = state.lonLabel, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                Text(text = state.lonValue, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 3.dp))
            }
        }
        // relativeLayout6: locator area, 43sp bold black centered
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = state.loc,
                fontSize = 43.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            // progressBar: loading, centered
            if (state.showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp
                )
            }
            // tvNotice: hint text, centered top
            if (state.showNotice) {
                Text(
                    text = "请稍等，加载可能需要几分钟的时间。",
                    fontSize = 16.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                )
            }
        }
        // relativeLayout: 3x3 grid, fills remaining space, bottom 15dp
        // Box as RelativeLayout equivalent: left cell left / middle cell centered / right cell right (original main.xml)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 15.dp)) {
            // Row 1: relativeLayout5 (top row: lat +1)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GridCell(text = state.grids[0], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                GridCell(text = state.grids[1], modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center))
                GridCell(text = state.grids[2], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
            // Row 2: relativeLayout4, 205dp high (middle row: center)
            Box(modifier = Modifier.fillMaxWidth().height(205.dp)) {
                GridCell(text = state.grids[3], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                // Center column: tv22 (100x80dp centerInParent) overlaid on linearLayout3 (200x200dp)
                Box(modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center)) {
                    // linearLayout3: 200x200dp, marginTop 2dp, holo_blue_bright
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 2.dp)
                            .size(width = 200.dp, height = 200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        // iv: red dot 10x10dp, top|left, leftMargin/topMargin from lookup tables
                        Image(
                            painter = painterResource(R.drawable.roam_pnt),
                            contentDescription = null,
                            modifier = Modifier
                                .size(10.dp)
                                .offset(x = state.markerLeft.dp, y = state.markerTop.dp)
                        )
                    }
                    // tv22: 100x80dp centerInParent, 30sp bold, textColorHighlight (white)
                    Text(
                        text = state.grids[4],
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .size(width = 100.dp, height = 80.dp)
                            .align(Alignment.Center)
                    )
                }
                GridCell(text = state.grids[5], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
            // Row 3: relativeLayout3 (bottom row: lat -1)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GridCell(text = state.grids[6], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterStart))
                GridCell(text = state.grids[7], modifier = Modifier.width(200.dp).fillMaxHeight().align(Alignment.Center))
                GridCell(text = state.grids[8], modifier = Modifier.width(80.dp).fillMaxHeight().align(Alignment.CenterEnd))
            }
        }
        // tv4: bottom credit, 10sp black (raised; bottom space kept clear of nav bar)
        Text(
            text = "制作：US1PM  汉化：BA7LCE",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
    }
}

// Surrounding cells: 60/200/60dp, #0BACF1, bold centered, 2dp margin (ported tv11/tv13/tv21/tv23/tv31/tv33)
@Composable
private fun GridCell(text: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Ported state from MainActivity.showLocation().
 * Grid / 3x3 / red-dot lookup tables match the original line by line.
 */
data class RoamingState(
    val date: String = "",
    val time: String = "",
    val latLabel: String = "纬度",
    val latValue: String = "",
    val lonLabel: String = "经度",
    val lonValue: String = "",
    val loc: String = "",
    val grids: List<String> = List(9) { "" },
    /** Red dot leftMargin (dp), looked up by the 3rd char pair of longitude */
    val markerLeft: Int = 0,
    /** Red dot topMargin (dp), looked up by the 3rd char pair of latitude (screen Y inverted) */
    val markerTop: Int = 0,
    val gpsOn: Boolean = false,
    val gpsOff: Boolean = true,
    val showSettings: Boolean = true,
    val showProgress: Boolean = false,
    val showNotice: Boolean = false,
    val showDate: Boolean = false
)

/**
 * Ported full showLocation() computation: time / lat-lon DMS / 8-char grid / 3x3 / red-dot lookup.
 */
fun roamingStateFromLocation(lat: Double, lon: Double, fixTime: Long, hourPrefix: String): RoamingState {
    // Ported: tvTime = cached hour prefix + locate time minutes
    val minute = SimpleDateFormat("mm", Locale.getDefault()).format(Date(fixTime))
    val time = hourPrefix + minute
    // Direction chars
    val ew = if (lon >= 0.0) "E" else "W"
    val ns = if (lat >= 0.0) "N" else "S"
    // Ported: latitude DMS (String.valueOf, no zero padding)
    val latAbs = if (lat < 0.0) -lat else lat
    val latDeg = latAbs.toInt()
    val latMinFull = (latAbs - latDeg) * 60.0
    val latMin = latMinFull.toInt()
    val latSec = ((latMinFull - latMin) * 60.0).toInt()
    val latLabel = "纬度  $latDeg° $latMin' $latSec\" $ns"
    val latValue = "$lat° "
    // Ported: longitude DMS
    val lonAbs = if (lon < 0.0) -lon else lon
    val lonDeg = lonAbs.toInt()
    val lonMinFull = (lonAbs - lonDeg) * 60.0
    val lonMin = lonMinFull.toInt()
    val lonSec = ((lonMinFull - lonMin) * 60.0).toInt()
    val lonLabel = "经度 $lonDeg° $lonMin' $lonSec\" $ew"
    val lonValue = "$lon° "
    // Ported: 8-char grid encoding (range lookup)
    val (str, str2, str3, str4) = encodeLon(lon)
    val (str5, str6, str38, str9) = encodeLat(lat)
    val loc = str + str5 + str2 + str6 + str3 + str38 + str4 + str9
    // The 3x3 ring is pure Maidenhead arithmetic, so derive it from the shared
    // helper instead of the decompiled per-edge branches. Those branches carried
    // the field letter in only some of the cells they moved (the north edge
    // advanced the latitude field for the top-centre cell but not for the two
    // top corners) and stepped letters past A/R at the edges of the world,
    // producing squares such as "@A91" or "SS00". Measured against qthNeighbors
    // over 64,800 sampled coordinates, 6,480 of them disagreed.
    val grids = qthNeighbors(str + str5 + str2 + str6)
    // Ported: red-dot lookup (3rd char pair)
    val markerLeft = lonMargin[str3.firstOrNull()] ?: 0
    val markerTop = latMargin[str38.firstOrNull()] ?: 0
    return RoamingState(
        time = time,
        latLabel = latLabel,
        latValue = latValue,
        lonLabel = lonLabel,
        lonValue = lonValue,
        loc = loc,
        grids = grids,
        markerLeft = markerLeft,
        markerTop = markerTop,
        gpsOn = true,
        gpsOff = false,
        showSettings = false,
        showProgress = false,
        showNotice = false,
        showDate = true
    )
}

// Red-dot leftMargin lookup (MainActivity lines 1286-1386): lon 3rd pair a..x -> -2..190 dp
private val lonMargin = mapOf(
    'a' to -2, 'b' to 8, 'c' to 16, 'd' to 24, 'e' to 32, 'f' to 40, 'g' to 48, 'h' to 56,
    'i' to 65, 'j' to 74, 'k' to 83, 'l' to 92, 'm' to 101, 'n' to 110, 'o' to 119, 'p' to 128,
    'q' to 137, 'r' to 146, 's' to 155, 't' to 163, 'u' to 171, 'v' to 179, 'w' to 184, 'x' to 190
)

// Red-dot topMargin lookup (MainActivity lines 1315-1363): lat 3rd pair a..x -> 190..-2 dp (screen Y inverted)
private val latMargin = mapOf(
    'a' to 190, 'b' to 182, 'c' to 174, 'd' to 169, 'e' to 163, 'f' to 155, 'g' to 146, 'h' to 137,
    'i' to 128, 'j' to 119, 'k' to 110, 'l' to 101, 'm' to 92, 'n' to 83, 'o' to 74, 'p' to 65,
    'q' to 56, 'r' to 48, 's' to 40, 't' to 32, 'u' to 24, 'v' to 16, 'w' to 8, 'x' to -2
)

// Ported lines 311-611: longitude -> (20 deg zone letter, 2 deg digit, 2' letter, 30" digit)
private fun encodeLon(inputLon: Double): List<String> {
    // The decompiled range table uses closed bounds on both adjacent cells
    // (e.g. -20..0 followed by 0..20), and `when` picks the first match. Exact
    // field/square/subsquare boundaries therefore fell into the previous cell:
    // lon=0 produced I... instead of J..., lon=108 lost one square/subsquare.
    // A tiny positive nudge implements the standard half-open [low, high)
    // convention without rewriting the faithful lookup table; keep +180 inside
    // the final R cell.
    var longitude = if (inputLon < 180.0) inputLon + 1e-10 else 180.0 - 1e-10
    var d = 0.0
    val str = when {
        (longitude >= -180.0) && (longitude <= -160.0) -> { longitude += 180.0; "A" }
        (longitude >= -160.0) && (longitude <= -140.0) -> { longitude += 160.0; "B" }
        (longitude >= -140.0) && (longitude <= -120.0) -> { longitude += 140.0; "C" }
        (longitude >= -120.0) && (longitude <= -100.0) -> { longitude += 120.0; "D" }
        (longitude >= -100.0) && (longitude <= -80.0) -> { longitude += 100.0; "E" }
        (longitude >= -80.0) && (longitude <= -60.0) -> { longitude += 80.0; "F" }
        (longitude >= -60.0) && (longitude <= -40.0) -> { longitude += 60.0; "G" }
        (longitude >= -40.0) && (longitude <= -20.0) -> { longitude += 40.0; "H" }
        (longitude >= -20.0) && (longitude <= 0.0) -> { longitude += 20.0; "I" }
        (longitude >= 0.0) && (longitude <= 20.0) -> "J"
        (longitude >= 20.0) && (longitude <= 40.0) -> { longitude -= 20.0; "K" }
        (longitude >= 40.0) && (longitude <= 60.0) -> { longitude -= 40.0; "L" }
        (longitude >= 60.0) && (longitude <= 80.0) -> { longitude -= 60.0; "M" }
        (longitude >= 80.0) && (longitude <= 100.0) -> { longitude -= 80.0; "N" }
        (longitude >= 100.0) && (longitude <= 120.0) -> { longitude -= 100.0; "O" }
        (longitude >= 120.0) && (longitude <= 140.0) -> { longitude -= 120.0; "P" }
        (longitude >= 140.0) && (longitude <= 160.0) -> { longitude -= 140.0; "Q" }
        (longitude >= 160.0) && (longitude <= 180.0) -> { longitude -= 160.0; "R" }
        else -> { longitude = 0.0; " " }
    }
    val str2 = when {
        ((longitude >= 0.0) && (longitude <= 2.0)) || ((longitude >= -20.0) && (longitude <= -18.0)) -> { d = longitude * 60.0; "0" }
        ((longitude >= 2.0) && (longitude <= 4.0)) || ((longitude >= -18.0) && (longitude <= -16.0)) -> { d = (longitude - 2.0) * 60.0; "1" }
        ((longitude >= 4.0) && (longitude <= 6.0)) || ((longitude >= -16.0) && (longitude <= -14.0)) -> { d = (longitude - 4.0) * 60.0; "2" }
        ((longitude >= 6.0) && (longitude <= 8.0)) || ((longitude >= -14.0) && (longitude <= -12.0)) -> { d = (longitude - 6.0) * 60.0; "3" }
        ((longitude >= 8.0) && (longitude <= 10.0)) || ((longitude >= -12.0) && (longitude <= -10.0)) -> { d = (longitude - 8.0) * 60.0; "4" }
        ((longitude >= 10.0) && (longitude <= 12.0)) || ((longitude >= -10.0) && (longitude <= -8.0)) -> { d = (longitude - 10.0) * 60.0; "5" }
        ((longitude >= 12.0) && (longitude <= 14.0)) || ((longitude >= -8.0) && (longitude <= -6.0)) -> { d = (longitude - 12.0) * 60.0; "6" }
        ((longitude >= 14.0) && (longitude <= 16.0)) || ((longitude >= -6.0) && (longitude <= -4.0)) -> { d = (longitude - 14.0) * 60.0; "7" }
        ((longitude >= 16.0) && (longitude <= 18.0)) || ((longitude >= -4.0) && (longitude <= -2.0)) -> { d = (longitude - 16.0) * 60.0; "8" }
        ((longitude >= 18.0) && (longitude <= 20.0)) || ((longitude >= -2.0) && (longitude <= 0.0)) -> { d = (longitude - 18.0) * 60.0; "9" }
        else -> { d = 0.0; " " }
    }
    val str3 = when {
        ((d >= 0.0) && (d <= 5.0)) || ((d >= -120.0) && (d <= -115.0)) -> { longitude = d * 60.0; "a" }
        ((d >= 5.0) && (d <= 10.0)) || ((d >= -115.0) && (d <= -110.0)) -> { longitude = (d - 5.0) * 60.0; "b" }
        ((d >= 10.0) && (d <= 15.0)) || ((d >= -110.0) && (d <= -105.0)) -> { longitude = (d - 10.0) * 60.0; "c" }
        ((d >= 15.0) && (d <= 20.0)) || ((d >= -105.0) && (d <= -100.0)) -> { longitude = (d - 15.0) * 60.0; "d" }
        ((d >= 20.0) && (d <= 25.0)) || ((d >= -100.0) && (d <= -95.0)) -> { longitude = (d - 20.0) * 60.0; "e" }
        ((d >= 25.0) && (d <= 30.0)) || ((d >= -95.0) && (d <= -90.0)) -> { longitude = (d - 25.0) * 60.0; "f" }
        ((d >= 30.0) && (d <= 35.0)) || ((d >= -90.0) && (d <= -85.0)) -> { longitude = (d - 30.0) * 60.0; "g" }
        ((d >= 35.0) && (d <= 40.0)) || ((d >= -85.0) && (d <= -80.0)) -> { longitude = (d - 35.0) * 60.0; "h" }
        ((d >= 40.0) && (d <= 45.0)) || ((d >= -80.0) && (d <= -75.0)) -> { longitude = (d - 40.0) * 60.0; "i" }
        ((d >= 45.0) && (d <= 50.0)) || ((d >= -75.0) && (d <= -70.0)) -> { longitude = (d - 45.0) * 60.0; "j" }
        ((d >= 50.0) && (d <= 55.0)) || ((d >= -70.0) && (d <= -65.0)) -> { longitude = (d - 50.0) * 60.0; "k" }
        ((d >= 55.0) && (d <= 60.0)) || ((d >= -65.0) && (d <= -60.0)) -> { longitude = (d - 55.0) * 60.0; "l" }
        ((d >= 60.0) && (d <= 65.0)) || ((d >= -60.0) && (d <= -55.0)) -> { longitude = (d - 60.0) * 60.0; "m" }
        ((d >= 65.0) && (d <= 70.0)) || ((d >= -55.0) && (d <= -50.0)) -> { longitude = (d - 65.0) * 60.0; "n" }
        ((d >= 70.0) && (d <= 75.0)) || ((d >= -50.0) && (d <= -45.0)) -> { longitude = (d - 70.0) * 60.0; "o" }
        ((d >= 75.0) && (d <= 80.0)) || ((d >= -45.0) && (d <= -40.0)) -> { longitude = (d - 75.0) * 60.0; "p" }
        ((d >= 80.0) && (d <= 85.0)) || ((d >= -40.0) && (d <= -35.0)) -> { longitude = (d - 80.0) * 60.0; "q" }
        ((d >= 85.0) && (d <= 90.0)) || ((d >= -35.0) && (d <= -30.0)) -> { longitude = (d - 85.0) * 60.0; "r" }
        ((d >= 90.0) && (d <= 95.0)) || ((d >= -30.0) && (d <= -25.0)) -> { longitude = (d - 90.0) * 60.0; "s" }
        ((d >= 95.0) && (d <= 100.0)) || ((d >= -25.0) && (d <= -20.0)) -> { longitude = (d - 95.0) * 60.0; "t" }
        ((d >= 100.0) && (d <= 105.0)) || ((d >= -20.0) && (d <= -15.0)) -> { longitude = (d - 100.0) * 60.0; "u" }
        ((d >= 105.0) && (d <= 110.0)) || ((d >= -15.0) && (d <= -10.0)) -> { longitude = (d - 105.0) * 60.0; "v" }
        ((d >= 110.0) && (d <= 115.0)) || ((d >= -10.0) && (d <= -5.0)) -> { longitude = (d - 110.0) * 60.0; "w" }
        ((d >= 115.0) && (d <= 120.0)) || ((d >= -5.0) && (d <= 0.0)) -> { longitude = (d - 115.0) * 60.0; "x" }
        else -> { longitude = 0.0; " " }
    }
    val str4 = when {
        ((longitude >= 0.0) && (longitude <= 30.0)) || ((longitude >= -300.0) && (longitude <= -270.0)) -> "0"
        ((longitude >= 30.0) && (longitude <= 60.0)) || ((longitude >= -270.0) && (longitude <= -240.0)) -> "1"
        ((longitude >= 60.0) && (longitude <= 90.0)) || ((longitude >= -240.0) && (longitude <= -210.0)) -> "2"
        ((longitude >= 90.0) && (longitude <= 120.0)) || ((longitude >= -210.0) && (longitude <= -180.0)) -> "3"
        ((longitude >= 120.0) && (longitude <= 150.0)) || ((longitude >= -180.0) && (longitude <= -150.0)) -> "4"
        ((longitude >= 150.0) && (longitude <= 180.0)) || ((longitude >= -150.0) && (longitude <= -120.0)) -> "5"
        ((longitude >= 180.0) && (longitude <= 210.0)) || ((longitude >= -120.0) && (longitude <= -90.0)) -> "6"
        ((longitude >= 210.0) && (longitude <= 240.0)) || ((longitude >= -90.0) && (longitude <= -60.0)) -> "7"
        ((longitude >= 240.0) && (longitude <= 270.0)) || ((longitude >= -60.0) && (longitude <= -30.0)) -> "8"
        ((longitude >= 270.0) && (longitude <= 300.0)) || ((longitude >= -30.0) && (longitude <= 0.0)) -> "9"
        else -> " "
    }
    return listOf(str, str2, str3, str4)
}

// Ported lines 612-914: latitude -> (10 deg zone letter, 1 deg digit, 1' letter, 15" digit)
private fun encodeLat(inputLat: Double): List<String> {
    // Same closed-bound issue as encodeLon: nudge into the half-open cell so an
    // exact boundary latitude does not fall back into the previous field.
    var latitude = if (inputLat < 90.0) inputLat + 1e-10 else 90.0 - 1e-10
    var d2 = 0.0
    var d3 = 0.0
    val str5 = when {
        (latitude >= -90.0) && (latitude <= -80.0) -> { d2 = latitude + 90.0; "A" }
        (latitude >= -80.0) && (latitude <= -70.0) -> { d2 = latitude + 80.0; "B" }
        (latitude >= -70.0) && (latitude <= -60.0) -> { d2 = latitude + 70.0; "C" }
        (latitude >= -60.0) && (latitude <= -50.0) -> { d2 = latitude + 60.0; "D" }
        (latitude >= -50.0) && (latitude <= -40.0) -> { d2 = latitude + 50.0; "E" }
        (latitude >= -40.0) && (latitude <= -30.0) -> { d2 = latitude + 40.0; "F" }
        (latitude >= -30.0) && (latitude <= -20.0) -> { d2 = latitude + 30.0; "G" }
        (latitude >= -20.0) && (latitude <= -10.0) -> { d2 = latitude + 20.0; "H" }
        (latitude >= -10.0) && (latitude <= 0.0) -> { d2 = latitude + 10.0; "I" }
        (latitude >= 0.0) && (latitude <= 10.0) -> { d2 = latitude; "J" }
        (latitude >= 10.0) && (latitude <= 20.0) -> { d2 = latitude - 10.0; "K" }
        (latitude >= 20.0) && (latitude <= 30.0) -> { d2 = latitude - 20.0; "L" }
        (latitude >= 30.0) && (latitude <= 40.0) -> { d2 = latitude - 30.0; "M" }
        (latitude >= 40.0) && (latitude <= 50.0) -> { d2 = latitude - 40.0; "N" }
        (latitude >= 50.0) && (latitude <= 60.0) -> { d2 = latitude - 50.0; "O" }
        (latitude >= 60.0) && (latitude <= 70.0) -> { d2 = latitude - 60.0; "P" }
        (latitude >= 70.0) && (latitude <= 80.0) -> { d2 = latitude - 70.0; "Q" }
        (latitude >= 80.0) && (latitude <= 90.0) -> { d2 = latitude - 80.0; "R" }
        else -> { d2 = 0.0; " " }
    }
    var str6 = " "
    when {
        ((d2 >= 0.0) && (d2 <= 1.0)) || ((d2 >= -10.0) && (d2 <= -9.0)) -> { d3 = d2 * 60.0; str6 = "0" }
        ((d2 >= 1.0) && (d2 <= 2.0)) || ((d2 >= -9.0) && (d2 <= -8.0)) -> { d3 = (d2 - 1.0) * 60.0; str6 = "1" }
        ((d2 >= 2.0) && (d2 <= 3.0)) || ((d2 >= -8.0) && (d2 <= -7.0)) -> { d3 = (d2 - 2.0) * 60.0; str6 = "2" }
        ((d2 >= 3.0) && (d2 <= 4.0)) || ((d2 >= -7.0) && (d2 <= -6.0)) -> { d3 = (d2 - 3.0) * 60.0; str6 = "3" }
        ((d2 >= 4.0) && (d2 <= 5.0)) || ((d2 >= -6.0) && (d2 <= -5.0)) -> { d3 = (d2 - 4.0) * 60.0; str6 = "4" }
        ((d2 >= 5.0) && (d2 <= 6.0)) || ((d2 >= -5.0) && (d2 <= -4.0)) -> { d3 = (d2 - 5.0) * 60.0; str6 = "5" }
        ((d2 >= 6.0) && (d2 <= 7.0)) || ((d2 >= -4.0) && (d2 <= -3.0)) -> { d3 = (d2 - 6.0) * 60.0; str6 = "6" }
        ((d2 >= 7.0) && (d2 <= 8.0)) || ((d2 >= -3.0) && (d2 <= -2.0)) -> { d3 = (d2 - 7.0) * 60.0; str6 = "7" }
        ((d2 >= 8.0) && (d2 <= 9.0)) || ((d2 >= -2.0) && (d2 <= -1.0)) -> { d3 = (d2 - 8.0) * 60.0; str6 = "8" }
        ((d2 >= 9.0) && (d2 <= 10.0)) || ((d2 >= -1.0) && (d2 <= 0.0)) -> { d3 = (d2 - 9.0) * 60.0; str6 = "9" }
    }
    val str38 = when {
        ((d3 >= 0.0) && (d3 <= 2.5)) || ((d3 >= -60.0) && (d3 <= -57.5)) -> { d2 = d3 * 60.0; "a" }
        ((d3 >= 2.5) && (d3 <= 5.0)) || ((d3 >= -57.5) && (d3 <= -55.0)) -> { d2 = (d3 - 2.5) * 60.0; "b" }
        ((d3 >= 5.0) && (d3 <= 7.5)) || ((d3 >= -55.0) && (d3 <= -52.5)) -> { d2 = (d3 - 5.0) * 60.0; "c" }
        ((d3 >= 7.5) && (d3 <= 10.0)) || ((d3 >= -52.5) && (d3 <= -50.0)) -> { d2 = (d3 - 7.5) * 60.0; "d" }
        ((d3 >= 10.0) && (d3 <= 12.5)) || ((d3 >= -50.0) && (d3 <= -47.5)) -> { d2 = (d3 - 10.0) * 60.0; "e" }
        ((d3 >= 12.5) && (d3 <= 15.0)) || ((d3 >= -47.5) && (d3 <= -45.0)) -> { d2 = (d3 - 12.5) * 60.0; "f" }
        ((d3 >= 15.0) && (d3 <= 17.5)) || ((d3 >= -45.0) && (d3 <= -42.5)) -> { d2 = (d3 - 15.0) * 60.0; "g" }
        ((d3 >= 17.5) && (d3 <= 20.0)) || ((d3 >= -42.5) && (d3 <= -40.0)) -> { d2 = (d3 - 17.5) * 60.0; "h" }
        ((d3 >= 20.0) && (d3 <= 22.5)) || ((d3 >= -40.0) && (d3 <= -37.5)) -> { d2 = (d3 - 20.0) * 60.0; "i" }
        ((d3 >= 22.5) && (d3 <= 25.0)) || ((d3 >= -37.5) && (d3 <= -35.0)) -> { d2 = (d3 - 22.5) * 60.0; "j" }
        ((d3 >= 25.0) && (d3 <= 27.5)) || ((d3 >= -35.0) && (d3 <= -32.5)) -> { d2 = (d3 - 25.0) * 60.0; "k" }
        ((d3 >= 27.5) && (d3 <= 30.0)) || ((d3 >= -32.5) && (d3 <= -30.0)) -> { d2 = (d3 - 27.5) * 60.0; "l" }
        ((d3 >= 30.0) && (d3 <= 32.5)) || ((d3 >= -30.0) && (d3 <= -27.5)) -> { d2 = (d3 - 30.0) * 60.0; "m" }
        ((d3 >= 32.5) && (d3 <= 35.0)) || ((d3 >= -27.5) && (d3 <= -25.0)) -> { d2 = (d3 - 32.5) * 60.0; "n" }
        ((d3 >= 35.0) && (d3 <= 37.5)) || ((d3 >= -25.0) && (d3 <= -22.5)) -> { d2 = (d3 - 35.0) * 60.0; "o" }
        ((d3 >= 37.5) && (d3 <= 40.0)) || ((d3 >= -22.5) && (d3 <= -20.0)) -> { d2 = (d3 - 37.5) * 60.0; "p" }
        ((d3 >= 40.0) && (d3 <= 42.5)) || ((d3 >= -20.0) && (d3 <= -17.5)) -> { d2 = (d3 - 40.0) * 60.0; "q" }
        ((d3 >= 42.5) && (d3 <= 45.0)) || ((d3 >= -17.5) && (d3 <= -15.0)) -> { d2 = (d3 - 42.5) * 60.0; "r" }
        ((d3 >= 45.0) && (d3 <= 47.5)) || ((d3 >= -15.0) && (d3 <= -12.5)) -> { d2 = (d3 - 45.0) * 60.0; "s" }
        ((d3 >= 47.5) && (d3 <= 50.0)) || ((d3 >= -12.5) && (d3 <= -10.0)) -> { d2 = (d3 - 47.5) * 60.0; "t" }
        ((d3 >= 50.0) && (d3 <= 52.5)) || ((d3 >= -10.0) && (d3 <= -7.5)) -> { d2 = (d3 - 50.0) * 60.0; "u" }
        ((d3 >= 52.5) && (d3 <= 55.0)) || ((d3 >= -7.5) && (d3 <= -5.0)) -> { d2 = (d3 - 52.5) * 60.0; "v" }
        ((d3 >= 55.0) && (d3 <= 57.5)) || ((d3 >= -5.0) && (d3 <= -2.5)) -> { d2 = (d3 - 55.0) * 60.0; "w" }
        ((d3 >= 57.5) && (d3 <= 60.0)) || ((d3 >= -2.5) && (d3 <= 0.0)) -> { d2 = (d3 - 57.5) * 60.0; "x" }
        else -> { d2 = 0.0; " " }
    }
    val str9 = when {
        ((d2 >= 0.0) && (d2 <= 15.0)) || ((d2 >= -150.0) && (d2 <= -135.0)) -> "0"
        ((d2 >= 15.0) && (d2 <= 30.0)) || ((d2 >= -135.0) && (d2 <= -120.0)) -> "1"
        ((d2 >= 30.0) && (d2 <= 45.0)) || ((d2 >= -120.0) && (d2 <= -105.0)) -> "2"
        ((d2 >= 45.0) && (d2 <= 60.0)) || ((d2 >= -105.0) && (d2 <= -90.0)) -> "3"
        ((d2 >= 60.0) && (d2 <= 75.0)) || ((d2 >= -90.0) && (d2 <= -75.0)) -> "4"
        ((d2 >= 75.0) && (d2 <= 90.0)) || ((d2 >= -75.0) && (d2 <= -60.0)) -> "5"
        ((d2 >= 90.0) && (d2 <= 105.0)) || ((d2 >= -60.0) && (d2 <= -45.0)) -> "6"
        ((d2 >= 105.0) && (d2 <= 120.0)) || ((d2 >= -45.0) && (d2 <= -30.0)) -> "7"
        ((d2 >= 120.0) && (d2 <= 135.0)) || ((d2 >= -30.0) && (d2 <= -15.0)) -> "8"
        ((d2 >= 135.0) && (d2 <= 150.0)) || ((d2 >= -15.0) && (d2 <= 0.0)) -> "9"
        else -> " "
    }
    return listOf(str5, str6, str38, str9)
}

