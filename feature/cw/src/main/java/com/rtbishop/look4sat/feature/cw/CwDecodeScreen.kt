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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R as CoreR
import kotlinx.coroutines.launch

/**
 * Full-page CW decoder backed by DeepCW.
 *
 * Layout follows the DeepCW reference app: waterfall on top, the live line
 * under it, then the scrolling history. Pause/clear controls and the microphone
 * permission flow carry over from the previous page.
 *
 * There is no settings dialog any more: the model analyses a fixed
 * 400-1200 Hz window and tracks speed on its own, so there is nothing to tune.
 */
@Composable
fun CwDecodeScreen(navigateUp: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IContainerProvider).getMainContainer() }
    val decoder = remember { container.provideCwDecoder() }
    val audioCapture = remember { container.provideAudioCapture() }
    val waterfall = remember { CwWaterfallState() }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }

    val decodedText by decoder.decodedText.collectAsState()
    val estimatedPitch by decoder.estimatedPitch.collectAsState()
    val signalStrength by decoder.signalStrength.collectAsState()
    val inferenceMs by decoder.lastInferenceMs.collectAsState()
    val errorMessage by decoder.errorMessage.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        val activity = context as? Activity
        permanentlyDenied = !granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (granted) isListening = true
    }

    // Capture runs only while listening; cancelling the effect stops the mic.
    LaunchedEffect(isListening, permissionGranted) {
        if (!isListening || !permissionGranted) return@LaunchedEffect
        launch {
            audioCapture.audioFlow().collect { chunk ->
                decoder.processBuffer(chunk, audioCapture.sampleRate)
                waterfall.pushSamples(chunk, audioCapture.sampleRate)
            }
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) isListening = true
    }

    DisposableEffect(Unit) {
        onDispose {
            // OrtSession holds native memory and must be released explicitly.
            decoder.close()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { isListening = false; navigateUp() }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_back),
                    contentDescription = stringResource(R.string.cw_back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(CoreR.string.nav_cw),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (estimatedPitch != null) {
                        stringResource(R.string.cw_status_tone, estimatedPitch!!.toInt(), inferenceMs)
                    } else {
                        stringResource(R.string.cw_status_listening)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { isListening = !isListening }) {
                Icon(
                    painter = painterResource(
                        if (isListening) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                    ),
                    contentDescription = stringResource(
                        if (isListening) R.string.cw_pause else R.string.cw_resume
                    )
                )
            }
            IconButton(onClick = { decoder.reset(); waterfall.clear() }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_delete),
                    contentDescription = stringResource(R.string.cw_clear)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            CwWaterfallView(state = waterfall, signalStrength = signalStrength)
        }

        Text(
            text = decodedText.takeLast(64).ifEmpty { "…" },
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Text(
                text = decodedText,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            errorMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (!permissionGranted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cw_mic_permission),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Text(stringResource(R.string.cw_grant_permission))
                        }
                        if (permanentlyDenied) {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }) {
                                Text(stringResource(R.string.cw_open_settings))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}
