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
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.cw.CwFldigiDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * CW decode page — pure Compose, fldigi decoding engine.
 *
 * Layout mirrors the DeepCW web decoder (waterfall -> live line -> history)
 * inside the existing container; top-bar actions (back/settings) and bottom
 * actions (start-pause/clear) keep the previous page's interaction model.
 * The old Morse Expert (ve3nea) controller and layouts are fully removed.
 */
@Composable
fun CwDecodeScreen(navigateUp: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoder = remember { CwFldigiDecoder(initialWpm = 18) }
    val waterfall = remember { CwWaterfallState() }

    var permissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    val decodedText by decoder.decodedTextFlow.collectAsState()
    val signalStrength by decoder.signalStrength.collectAsState()
    val estimatedSpeed by decoder.estimatedSpeed.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permanentlyDenied = !granted && (
            (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(
                Manifest.permission.RECORD_AUDIO
            ) ?: false
        ).not()
    }

    fun startCapture() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        captureJob?.cancel()
        captureJob = scope.launch(Dispatchers.IO) {
            val capture = CwMicCapture()
            capture.audioFlow().collect { chunk ->
                if (!isActive) return@collect
                decoder.processBuffer(chunk)
                waterfall.pushSamples(chunk)
            }
        }
        isListening = true
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        isListening = false
    }

    DisposableEffect(Unit) {
        onDispose { stopCapture() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { stopCapture(); navigateUp() }) {
                Icon(painter = androidx.compose.ui.res.painterResource(com.rtbishop.look4sat.core.presentation.R.drawable.ic_back), contentDescription = "Back")
            }
            Text(
                text = "CW Decoder",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(painter = androidx.compose.ui.res.painterResource(com.rtbishop.look4sat.core.presentation.R.drawable.ic_cw), contentDescription = "Settings")
            }
        }

        // Waterfall
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            CwWaterfallView(state = waterfall)
        }

        // Live decode line
        Text(
            text = decodedText.takeLast(80),
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // History
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
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
        }

        // Status cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard("DECODER", if (isListening) "ACTIVE" else "IDLE",
                if (isListening) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                Modifier.weight(1f))
            StatusCard("SPEED", if (estimatedSpeed != null) "${estimatedSpeed?.roundToInt()} WPM" else "--",
                MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatusCard("LEVEL", "${(signalStrength * 100).roundToInt()}%",
                MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }

        // Bottom actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { if (isListening) stopCapture() else startCapture() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = if (isListening) androidx.compose.ui.res.painterResource(com.rtbishop.look4sat.core.presentation.R.drawable.ic_close)
                    else androidx.compose.ui.res.painterResource(com.rtbishop.look4sat.core.presentation.R.drawable.ic_arrow),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text(if (isListening) "Pause" else "Start")
            }
            Button(
                onClick = { decoder.resetDecoder(); waterfall.reset() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(painter = androidx.compose.ui.res.painterResource(com.rtbishop.look4sat.core.presentation.R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Clear")
            }
        }
    }

    if (showSettings) {
        CwSettingsDialog(
            onDismiss = { showSettings = false },
            currentWpm = estimatedSpeed?.roundToInt() ?: 18
        )
    }

    if (!permissionGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Microphone permission is required for CW decoding",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant permission")
                }
                if (permanentlyDenied) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }) {
                        Text("Open app settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

/**
 * Microphone capture at 8000 Hz mono PCM float (fldigi sample rate).
 */
private class CwMicCapture {
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

    fun audioFlow(): kotlinx.coroutines.flow.Flow<FloatArray> =
        kotlinx.coroutines.flow.flow {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(sampleRate / 5)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 4
            )
            try {
                recorder.startRecording()
                val chunkSize = sampleRate / 10 // ~100ms
                val buffer = FloatArray(chunkSize)
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val read = recorder.read(buffer, 0, chunkSize, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        emit(if (read == chunkSize) buffer.copyOf() else buffer.copyOfRange(0, read))
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }.flowOn(Dispatchers.IO)
}

// --- Waterfall (lightweight sliding spectrogram) ---

class CwWaterfallState {
    private val columns = 160
    private val rows = 64
    private val data = FloatArray(columns * rows)
    private var colCount = 0
    private val pending = ArrayList<Float>(4096)
    private val fftWindow = 512

    /** Monotonic frame counter — reading this in composition triggers redraw. */
    private val _version = androidx.compose.runtime.mutableIntStateOf(0)
    val version: androidx.compose.runtime.State<Int> = _version

    @Synchronized
    fun pushSamples(samples: FloatArray) {
        pending.addAll(samples.toList())
        var frames = 0
        while (pending.size >= fftWindow) {
            val window = FloatArray(fftWindow) { pending[it] }
            repeat(fftWindow) { pending.removeAt(0) }
            val spectrum = computeSpectrum(window)
            // shift rows up, insert newest at bottom
            for (r in 0 until rows - 1) {
                System.arraycopy(data, (r + 1) * columns, data, r * columns, columns)
            }
            for (c in 0 until columns) {
                val bin = c * spectrum.size / columns
                data[(rows - 1) * columns + c] = spectrum[bin.coerceAtMost(spectrum.size - 1)]
            }
            if (colCount < rows) colCount++
            frames++
        }
        if (frames > 0) _version.intValue++
    }

    /** Magnitude spectrum via radix-2 FFT on a Hann-windowed frame. */
    private fun computeSpectrum(window: FloatArray): FloatArray {
        val n = window.size
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))
            re[i] = window[i] * w.toFloat()
        }
        // iterative radix-2 FFT (n = 512)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    val nIm = curRe * wIm + curIm * wRe
                    curRe = nRe; curIm = nIm
                }
                i += len
            }
            len = len shl 1
        }
        val bins = 48
        val out = FloatArray(bins)
        for (k in 0 until bins) {
            // bin k maps to k * 8000/512 ≈ 15.6 Hz steps; show 0..750 Hz region
            out[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) / n
        }
        return out
    }

    @Synchronized
    fun getColumn(c: Int): FloatArray {
        val col = FloatArray(rows)
        for (r in 0 until rows) {
            col[r] = data[r * columns + c.coerceIn(0, columns - 1)]
        }
        return col
    }

    @Synchronized
    fun reset() {
        data.fill(0f)
        pending.clear()
        colCount = 0
    }
}

@Composable
private fun CwWaterfallView(state: CwWaterfallState) {
    val columns = 160
    val rows = 64
    // Read the frame counter so the canvas redraws as new spectra arrive.
    state.version.value
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
            .clip(RoundedCornerShape(8.dp))
    ) {
        val cellW = size.width / columns
        val cellH = size.height / rows
        for (c in 0 until columns) {
            val col = state.getColumn(c)
            for (r in 0 until rows) {
                val v = col[r]
                // log-ish scaling: quiet bins stay dark, strong CW tones pop
                val intensity = (kotlin.math.ln1p(v * 600f) * 55f).coerceIn(0f, 255f)
                if (intensity > 4f) {
                    val green = (intensity * 1.25f).coerceAtMost(255f)
                    drawRect(
                        color = Color(0f, green / 255f, 0f, 1f),
                        topLeft = Offset(c * cellW, r * cellH),
                        size = Size(cellW + 0.5f, cellH + 0.5f)
                    )
                }
            }
        }
    }
}
