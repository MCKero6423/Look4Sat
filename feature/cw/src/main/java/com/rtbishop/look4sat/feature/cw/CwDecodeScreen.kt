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
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.cw.CwToneShifter
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R as CoreR
import kotlin.math.roundToInt

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
fun CwDecodeScreen() {
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
    val scope = rememberCoroutineScope()
    // Hoisted so the toolbar button can reach it: the record below does not follow
    // the decode, so jumping to the newest text has to be an explicit action.
    val transcriptScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val showToast = remember { container.provideShowToast() }
    val copiedMessage = stringResource(R.string.cw_copied)
    val emptyRecordMessage = stringResource(R.string.cw_record_empty)
    // Archiving the tail on the way out has to outlive this composable's own scope.
    val appScope = container.appScope

    val decodedText by decoder.decodedText.collectAsState()
    val historyText by decoder.historyText.collectAsState()
    val signalStrength by decoder.signalStrength.collectAsState()
    val detectedToneHz by decoder.detectedToneHz.collectAsState()
    val activeShiftHz by decoder.activeShiftHz.collectAsState()
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

    // Capture runs only while listening. Keyed on both listening state and
    // permission so granting the permission mid-flow restarts collection
    // (isListening may already be true when the launcher returns).
    LaunchedEffect(isListening, permissionGranted) {
        if (!isListening) {
            // Pausing must not throw away the tail. The live window plus the batch waiting to
            // be archived are both still holding audio at this point, and nothing else ever
            // decodes either of them, so the end of the transmission would be lost outright.
            decoder.flush()
            return@LaunchedEffect
        }
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        audioCapture.audioFlow().collect { chunk ->
            decoder.processBuffer(chunk, audioCapture.sampleRate)
            waterfall.pushSamples(chunk, audioCapture.sampleRate)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Archive the tail before tearing down, on a scope that outlives this composable:
            // the screen's own scope is cancelled as it leaves, which would abort the decode.
            // close() waits for it, because the inference needs the session it is closing.
            appScope.launch {
                runCatching { decoder.flush() }
                // OrtSession holds native memory and must be released explicitly.
                decoder.close()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(CoreR.string.nav_cw),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            // Copy everything decoded so far. The decoder is built fresh every time this screen
            // is opened, so leaving loses the transcript, and until now there was no way at all
            // to get the text off it: an operator who had just copied a call sign by ear had to
            // transcribe it a second time by hand.
            //
            // Includes the live window, not just the record. The record only holds archived text,
            // which needs the window to fill and then a batch to accumulate, so for the opening
            // half-minute it is empty - and gating the button on it left the one control that
            // rescues the text greyed out over exactly the short exchange most likely to be lost.
            val copyable = (historyText + decodedText).trim()
            IconButton(
                onClick = {
                    if (copyable.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(copyable))
                        showToast(copiedMessage)
                    }
                },
                enabled = copyable.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_save),
                    contentDescription = stringResource(R.string.cw_copy)
                )
            }
            // Jump to the newest text. The record below deliberately does not follow the decode,
            // so this is how you get back to the bottom after reading earlier traffic.
            IconButton(onClick = { scope.launch { transcriptScroll.animateScrollTo(transcriptScroll.maxValue) } }) {
                Icon(
                    // The shared arrow points right; rotated to point down. Adding a second
                    // drawable for the same shape is how icon sets start to drift.
                    painter = painterResource(CoreR.drawable.ic_arrow),
                    contentDescription = stringResource(R.string.cw_scroll_newest),
                    modifier = Modifier.rotate(90f)
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
            CwWaterfallView(
                state = waterfall,
                signalStrength = signalStrength,
                detectedToneHz = detectedToneHz,
                toneShiftHz = activeShiftHz
            )
        }

        // What the markers cannot say on their own. The waterfall covers only the model's
        // 400-1200 Hz window, so a tone outside it is missing from the picture entirely -
        // and with tone shift off there is nothing to mark either. One line of text is
        // what turns "nothing is happening" into a reason and a remedy.
        val toneHz = detectedToneHz
        val hint = when {
            toneHz == null -> null
            activeShiftHz != 0f -> stringResource(R.string.cw_tone_shifted_hint, toneHz.roundToInt())
            CwToneShifter.isInsideWindow(toneHz) -> null
            else -> stringResource(R.string.cw_tone_outside_hint, toneHz.roundToInt())
        }
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, end = 12.dp)
            )
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

        // The pane below was an unlabelled grey box showing a bare ellipsis, which reads as a
        // disabled text field rather than a transcript. Naming it is what makes the split
        // between the live line above and the record below legible at all.
        Text(
            text = stringResource(R.string.cw_record_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            // The record shows settled text only. decodedText is the live 20-second window,
            // which the model rewrites from scratch every 1.5 seconds as more context arrives -
            // right for the line above, where the newest guess is what you want, but it meant the
            // bottom of the record kept changing and sometimes got shorter. That reads as the
            // decoder deleting what it just wrote, which for something whose whole purpose is to
            // be read back is the one thing it must not do.
            val transcript = historyText.ifEmpty { emptyRecordMessage }
            // This pane does not follow the decode. The single line above it is where new
            // characters appear; this is the record, and a record that scrolls itself is worse
            // than paper - you cannot read back over what just arrived because it keeps moving.
            //
            // It used to auto-scroll to the bottom on every decode, dropping out of follow mode
            // only once the operator had already fought it by scrolling away. Now it stays where
            // it was put, and the button below jumps to the newest text on request.
            Text(
                text = transcript,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(transcriptScroll)
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
