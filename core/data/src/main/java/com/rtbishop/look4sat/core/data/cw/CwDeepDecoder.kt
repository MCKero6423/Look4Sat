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
package com.rtbishop.look4sat.core.data.cw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.rtbishop.look4sat.core.domain.cw.CwCtcDecoder
import com.rtbishop.look4sat.core.domain.cw.CwDeepBuffer
import com.rtbishop.look4sat.core.domain.cw.CwDeepSpectrogram
import com.rtbishop.look4sat.core.domain.cw.ICwDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.FloatBuffer

/**
 * CW decoder backed by the DeepCW neural network (AGPL-3.0, see
 * `feature/cw/licenses/NOTICE.md`).
 *
 * The model classifies a whole audio segment at once rather than streaming
 * sample by sample, and it revises earlier characters once more context
 * arrives. Incremental stitching therefore produces duplicated callsigns —
 * measured character error rates of 67-294% against 0% for whole-segment
 * decoding. Instead a [CwDeepBuffer] holds the last 20 seconds and the whole
 * window is re-decoded every 1.5 seconds, replacing [decodedText] outright.
 *
 * The model's fixed 400-1200 Hz analysis window means pitch detection is built
 * in; no spectral peak tracking or squelch gating is needed.
 */
class CwDeepDecoder(context: Context) : ICwDecoder {

    private companion object {
        const val TAG = "CwDeepDecoder"
        const val MODEL_ASSET = "deepcw/model.onnx"
        const val METADATA_ASSET = "deepcw/model.onnx.json"

        /** Evicted audio is decoded into permanent history once this much accumulates. */
        const val ARCHIVE_SECONDS = 15.0
        val ARCHIVE_THRESHOLD: Int = (CwDeepSpectrogram.SAMPLE_RATE * ARCHIVE_SECONDS).toInt()
    }

    private val _decodedText = MutableStateFlow("")
    override val decodedText: StateFlow<String> = _decodedText.asStateFlow()

    private val _historyText = MutableStateFlow("")
    override val historyText: StateFlow<String> = _historyText.asStateFlow()

    private val _estimatedPitch = MutableStateFlow<Float?>(null)
    override val estimatedPitch: StateFlow<Float?> = _estimatedPitch.asStateFlow()

    private val _signalStrength = MutableStateFlow(0f)
    override val signalStrength: StateFlow<Float> = _signalStrength.asStateFlow()

    private val _lastInferenceMs = MutableStateFlow(0)
    override val lastInferenceMs: StateFlow<Int> = _lastInferenceMs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val buffer = CwDeepBuffer()

    /**
     * Evicted audio accumulates here until it reaches [ARCHIVE_SECONDS], then
     * is decoded once and appended to [historyText]. Archiving in ~15 s chunks
     * keeps the extra inference cheap (short window) while long enough to be
     * decoded accurately — the content has already been through the 20 s window
     * many times, so a slightly shorter archive decode loses almost nothing.
     */
    private val archiveBuffer = FloatArray(CwDeepBuffer.DEFAULT_MAX_SECONDS.toInt() * CwDeepSpectrogram.SAMPLE_RATE)
    private var archiveSize = 0

    /** Held while inference runs so slow devices skip work instead of queuing it. */
    private val inferenceLock = Mutex()

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var chars: List<String> = emptyList()
    private var blankIndex = 41
    private var inputName = "spectrogram"
    private var outputName = "log_probs"

    private val appContext = context.applicationContext
    private var loadAttempted = false

    init {
        CwProbe.init(appContext)
        CwProbe.step("decoder_constructed")
    }

    /**
     * Loads metadata and the ONNX session on first use.
     *
     * Deliberately not done in `init`: loading pulls in ONNX Runtime's native
     * library, and a failure there surfaces as [UnsatisfiedLinkError]. Thrown
     * from a constructor it would take down the whole composable that created
     * the decoder, so the work happens here where it can be reported through
     * [errorMessage] instead.
     *
     * @return true when the session is ready to run.
     */
    private fun ensureLoaded(): Boolean {
        if (session != null) return true
        if (loadAttempted) return false
        loadAttempted = true
        CwProbe.step("load_begin")
        try {
            val metadata = JSONObject(
                appContext.assets.open(METADATA_ASSET).bufferedReader().use { it.readText() }
            )
            val charArray = metadata.getJSONArray("chars")
            chars = List(charArray.length()) { charArray.getString(it) }
            blankIndex = metadata.getInt("blank_index")
            inputName = metadata.getString("onnx_input_name")
            outputName = metadata.getString("onnx_output_name")

            val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            environment = env
            val options = OrtSession.SessionOptions().apply {
                // Keep a core free for audio capture and the UI; the default
                // would spread inference across every core on the device.
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
                setIntraOpNumThreads(threads)
            }
            session = env.createSession(modelBytes, options)
            CwProbe.step("load_session_ok")
            Log.i(TAG, "DeepCW ready: ${modelBytes.size} bytes, ${chars.size} classes")
            return true
        } catch (t: Throwable) {
            // Catches UnsatisfiedLinkError (missing/mismatched .so) as well as
            // asset and session failures.
            CwProbe.step("load_failed:${t.javaClass.simpleName}")
            Log.e(TAG, "DeepCW model failed to load", t)
            _errorMessage.value =
                "CW model failed to load: ${t.message ?: t.javaClass.simpleName}"
            // Persist the failure for devices without logcat access.
            runCatching {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(appContext.filesDir, "deepcw_load_error.txt")
                    .writeText("${t.javaClass.name}: ${t.message}\n${sw}\n")
            }
            return false
        }
    }

    override suspend fun processBuffer(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        if (!ensureLoaded()) return

        val resampled = CwDeepSpectrogram.resampleLinear(
            samples, sampleRate, CwDeepSpectrogram.SAMPLE_RATE
        )
        val shouldRedecode = buffer.append(resampled)

        // Archive audio that scrolled out of the live window. It is decoded once
        // when a full archive chunk has accumulated, so old text does not vanish.
        val overflow = buffer.drainOverflow()
        if (overflow.isNotEmpty()) {
            for (v in overflow) {
                if (archiveSize < archiveBuffer.size) archiveBuffer[archiveSize++] = v
            }
            if (archiveSize >= ARCHIVE_THRESHOLD) {
                val audio = archiveBuffer.copyOf(archiveSize)
                archiveSize = 0
                try {
                    archiveDecode(audio)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "archive decode failed", t)
                }
            }
        }

        if (!shouldRedecode || !buffer.hasEnoughAudio) return

        // Drop this cycle rather than queue when the previous run is still going.
        if (!inferenceLock.tryLock()) {
            Log.d(TAG, "inference still running, skipping this interval")
            return
        }
        try {
            decodeWindow(buffer.snapshot())
        } catch (t: Throwable) {
            // Cancellation is normal when the user pauses: the capture coroutine
            // is cancelled while an inference is in flight. Never swallow it as
            // a decode error — rethrow so the coroutine machinery works, and do
            // not flash a spurious "decode failed" banner.
            if (t is CancellationException) throw t
            Log.e(TAG, "inference failed", t)
            _errorMessage.value = "CW decode failed: ${t.message ?: t.javaClass.simpleName}"
            runCatching {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(appContext.filesDir, "deepcw_infer_error.txt")
                    .writeText("${t.javaClass.name}: ${t.message}\n${sw}\n")
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    private suspend fun decodeWindow(window: FloatArray) = withContext(Dispatchers.Default) {
        val activeSession = session ?: return@withContext
        val activeEnvironment = environment ?: return@withContext
        CwProbe.step("infer_begin frames=${window.size}")

        val spectrogram = CwDeepSpectrogram.compute(window)
        val text = runInference(activeSession, activeEnvironment, spectrogram)

        // Replace, never append: the model rewrites earlier characters as more
        // context arrives, so appending would leave stale guesses on screen.
        _decodedText.value = text

        updateSignalMetrics(spectrogram)
    }

    /**
     * Decode a chunk of audio that has scrolled out of the live window and
     * append it to [historyText]. Unlike the live window this never replaces —
     * the archived audio is final, so its text is permanent.
     */
    private suspend fun archiveDecode(audio: FloatArray) = withContext(Dispatchers.Default) {
        val activeSession = session ?: return@withContext
        val activeEnvironment = environment ?: return@withContext
        if (audio.size < CwDeepSpectrogram.FFT_LENGTH) return@withContext
        val spectrogram = CwDeepSpectrogram.compute(audio)
        val text = runInference(activeSession, activeEnvironment, spectrogram)
        if (text.isNotEmpty()) {
            _historyText.value += text
        }
    }

    /** Run the ONNX model over a pre-computed spectrogram and return the decoded text. */
    private fun runInference(
        activeSession: OrtSession,
        activeEnvironment: OrtEnvironment,
        spectrogram: Array<FloatArray>
    ): String {
        val frames = spectrogram.size
        val bins = CwDeepSpectrogram.FREQUENCY_BINS

        val flat = FloatBuffer.allocate(frames * bins)
        for (frame in spectrogram) flat.put(frame)
        flat.rewind()

        val shape = longArrayOf(1, 1, frames.toLong(), bins.toLong())
        val startedAt = System.currentTimeMillis()
        val text: String
        OnnxTensor.createTensor(activeEnvironment, flat, shape).use { input ->
            activeSession.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result[outputName].get().value as Array<Array<FloatArray>>
                text = CwCtcDecoder.greedy(logits, chars, blankIndex)
            }
        }
        _lastInferenceMs.value = (System.currentTimeMillis() - startedAt).toInt()
        CwProbe.step("infer_done ms=${_lastInferenceMs.value}")
        return text
    }

    /**
     * Report the loudest bin as the tone pitch and its prominence over the
     * window mean as a 0..1 strength, purely for the UI readout.
     */
    private fun updateSignalMetrics(spectrogram: Array<FloatArray>) {
        if (spectrogram.isEmpty()) return
        var bestBin = 0
        var bestValue = 0f
        var total = 0f
        var count = 0
        for (frame in spectrogram) {
            for (bin in frame.indices) {
                val value = frame[bin]
                total += value
                count++
                if (value > bestValue) {
                    bestValue = value
                    bestBin = bin
                }
            }
        }
        if (count == 0 || bestValue <= 0f) return

        val binHz = CwDeepSpectrogram.SAMPLE_RATE.toDouble() / CwDeepSpectrogram.FFT_LENGTH
        // Relative bin 0 is 400 Hz; absolute bin index is 32 + bestBin.
        val absoluteBin = 32 + bestBin
        _estimatedPitch.value = (absoluteBin * binHz).toFloat()

        val mean = total / count
        _signalStrength.value = ((bestValue - mean) / bestValue).coerceIn(0f, 1f)
    }

    override fun reset() {
        buffer.reset()
        _decodedText.value = ""
        _historyText.value = ""
        archiveSize = 0
        _estimatedPitch.value = null
        _signalStrength.value = 0f
        _lastInferenceMs.value = 0
    }

    override fun close() {
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "session close failed", t)
        }
        session = null
        environment = null
    }
}
