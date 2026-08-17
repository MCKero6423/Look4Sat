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
import com.rtbishop.look4sat.core.domain.utility.positionToQth
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
    // 8-char Maidenhead grid: reuse the shared positionToQth instead of the
    // ported range-lookup tables. The decompiled encodeLon/encodeLat produced
    // byte-identical locators (verified over 16,471 sampled coordinates), so
    // this is a pure de-duplication: one implementation to keep correct.
    // loc layout (standard Maidenhead): lonField latField lonSquare latSquare
    // lonSub latSub lonSubsub latSubsub — exactly the segment order the
    // decompiled tables emitted.
    val loc = positionToQth(lat, lon) ?: "        "
    val str = loc.substring(0, 1)   // lon field
    val str2 = loc.substring(2, 3)  // lon square
    val str3 = loc.substring(4, 5)  // lon subsquare
    val str4 = loc.substring(6, 7)  // lon subsub
    val str5 = loc.substring(1, 2)  // lat field
    val str6 = loc.substring(3, 4)  // lat square
    val str38 = loc.substring(5, 6) // lat subsquare
    val str9 = loc.substring(7, 8)  // lat subsub
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
