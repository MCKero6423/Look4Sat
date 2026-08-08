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
package com.rtbishop.look4sat.feature.cw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * CW decoder settings (fldigi engine).
 *
 * Options ported from fldigi progdefaults: speed (WPM), SOM codebook
 * decoding on/off, filter bandwidth. Persisted in SharedPreferences.
 */
@Composable
fun CwSettingsDialog(
    onDismiss: () -> Unit,
    currentWpm: Int,
    onSpeedChange: (Int) -> Unit = {},
    onSomChange: (Boolean) -> Unit = {},
    onBandwidthChange: (Int) -> Unit = {}
) {
    var wpm by remember { mutableStateOf(currentWpm.coerceIn(5, 50)) }
    var useSom by remember { mutableStateOf(true) }
    var bandwidth by remember { mutableStateOf(150) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CW Settings") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                Text("Speed (WPM)", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = wpm.toFloat(),
                        onValueChange = { wpm = it.roundToInt() },
                        valueRange = 5f..50f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$wpm",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Filter bandwidth (Hz)", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = bandwidth.toFloat(),
                        onValueChange = { bandwidth = it.roundToInt() },
                        valueRange = 50f..500f,
                        steps = 8,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$bandwidth",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("SOM codebook decoding", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { useSom = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = useSom, onClick = { useSom = true })
                    Text("On (recommended, higher accuracy)")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { useSom = false }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !useSom, onClick = { useSom = false })
                    Text("Off (plain table lookup)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSpeedChange(wpm)
                onSomChange(useSom)
                onBandwidthChange(bandwidth)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
