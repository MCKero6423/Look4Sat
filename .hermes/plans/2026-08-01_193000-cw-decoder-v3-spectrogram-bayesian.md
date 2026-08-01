# CW Decoder v3 — Spectrogram-based Multi-channel Bayesian Decoder

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Replace the current time-domain CW decoder with a spectrogram-based multi-channel decoder inspired by Morse Expert / CW Skimmer (VE3NEA). Uses FFT waterfall for frequency-agnostic signal detection and Bayesian probability for symbol timing.

**Architecture:** Pure Kotlin, no external dependencies. Audio → FFT → Spectrogram → Multi-channel peak detection → Per-channel energy envelope → Bayesian timing analysis → Morse character decoding. Multiple channels tracked simultaneously; the best one is selected for output.

**Tech Stack:** Kotlin, radix-2 FFT (reuse `Complex` from `SstvDsp`), waterfall spectrogram, Bayesian probability framework.

---

## Current Context

The existing v2 decoder (ggmorse port) has these limitations:
- **Single-channel**: locks one Goertzel filter to one frequency
- **Time-domain only**: signal disappears if frequency drifts
- **Hard thresholds**: dit/dash classification uses fixed ratios
- **No probability**: every decision is binary

**Morse Expert / CW Skimmer approach:**
- **Spectrogram-based**: FFT creates a 2D frequency×time matrix
- **Multi-channel**: all frequencies monitored simultaneously
- **Bayesian**: probability-based decisions, not hard thresholds
- **Frequency-agnostic**: drift just moves energy between bins, never lost

**Key insight from VE3NEA (CW Skimmer author):**
> "Instead of making a hard decision at every input sample whether the signal is present or not, compute the probability that the signal is present. Combine that probability with other probabilities using the Bayes formula and pass the new probabilities to the subsequent stages, all the way to the word recognition unit."

---

## Architecture

```
Audio buffer (128 samples @ 8 kHz)
    │
    ▼
Hanning window
    │
    ▼
FFT (256-point radix-2)
    │
    ▼
Spectrogram update (frequency bins × time columns)
    │
    ▼
Peak detection (find active frequency bins)
    │
    ▼
For each active channel:
    ├─ Energy envelope extraction (time series at that bin)
    ├─ Adaptive noise floor estimation
    ├─ Signal probability computation (Bayesian)
    └─ Timing analysis → symbol sequence → Morse character
    │
    ▼
Select best channel → Output decoded text
```

**FFT parameters:**
- FFT size: 256 points (windowed)
- Hop size: 64 samples (75% overlap)
- Frequency resolution: 8000/256 = 31.25 Hz per bin
- Time resolution: 64/8000 = 8 ms per column
- Analyzed range: bins 6-38 (187-1187 Hz, covers 200-1200 Hz CW range)

---

## Step-by-step Plan

### Task 1: Create FFT implementation

**Objective:** Write a radix-2 FFT that works on real audio data, producing magnitude spectrum.

**Files:**
- Create: `core/domain/src/main/java/.../cw/CwFFT.kt`

**Code:**
```kotlin
package com.rtbishop.look4sat.core.domain.cw

/**
 * Radix-2 FFT for real-valued input.
 * Produces magnitude spectrum for the first N/2+1 bins.
 */
internal class CwFFT(private val n: Int) {
    init {
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be power of 2, got $n" }
    }

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = -2.0 * kotlin.math.PI * i / n
            cosTable[i] = kotlin.math.cos(angle).toFloat()
            sinTable[i] = kotlin.math.sin(angle).toFloat()
        }
    }

    /** Compute magnitude spectrum for real input. Returns array of size n/2+1. */
    fun magnitudeSpectrum(input: FloatArray): FloatArray {
        require(input.size == n) { "Input size must be $n" }

        // Bit-reversal permutation
        val real = input.copyOf()
        val imag = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tmp = real[i]; real[i] = real[j]; real[j] = tmp
            }
        }

        // Radix-2 Cooley-Tukey
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until half) {
                    val tReal = real[i + k + half] * cosTable[k * step] - imag[i + k + half] * sinTable[k * step]
                    val tImag = real[i + k + half] * sinTable[k * step] + imag[i + k + half] * cosTable[k * step]
                    real[i + k + half] = real[i + k] - tReal
                    imag[i + k + half] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                }
            }
            len = len shl 1
        }

        // Magnitude spectrum (first N/2+1 bins)
        val mag = FloatArray(n / 2 + 1)
        for (i in 0..n / 2) {
            mag[i] = kotlin.math.sqrt(real[i] * real[i] + imag[i] * imag[i]) / n
        }
        return mag
    }
}
```

**Step 1: Verify compilation**

Run: `./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Commit**

```bash
git add core/domain/src/main/java/.../cw/CwFFT.kt
git commit -m "feat(cw): add radix-2 FFT for spectrogram processing"
```

---

### Task 2: Create Spectrogram engine

**Objective:** Maintain a sliding-window spectrogram (frequency×time matrix) that updates with each new audio frame.

**Files:**
- Create: `core/domain/src/main/java/.../cw/CwSpectrogram.kt`

**Key design:**
- Window size: 256 samples, hop 64 samples (75% overlap)
- Hanning window applied before FFT
- Spectrogram history: 40 columns (40 × 8ms = 320ms, enough for longest Morse dash)
- Frequency bins: 6-38 (187-1187 Hz), 33 bins total
- Energy normalization: per-bin running average

```kotlin
package com.rtbishop.look4sat.core.domain.cw

/**
 * Sliding-window spectrogram for CW decoding.
 * Maintains a time-frequency matrix updated with each audio frame.
 *
 * Parameters:
 *   fftSize = 256, hopSize = 64, sampleRate = 4000
 *   Frequency bins: 6..38 (187-1187 Hz)
 *   History: 40 columns (320 ms window)
 */
internal class CwSpectrogram(
    private val fftSize: Int = 256,
    private val hopSize: Int = 64,
    private val sampleRate: Int = 4000,
    private val minBin: Int = 6,   // 187 Hz
    private val maxBin: Int = 38,  // 1187 Hz
    private val historyCols: Int = 40
) {
    private val fft = CwFFT(fftSize)
    private val numBins = maxBin - minBin + 1
    private val hanning = FloatArray(fftSize) {
        (0.5 - 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * it / (fftSize - 1))).toFloat()
    }

    // Spectrogram data: [timeCol][freqBin]
    private val spectrogram = Array(historyCols) { FloatArray(numBins) }
    private var currentCol = 0
    private var samplesBuffered = 0
    private val buffer = FloatArray(fftSize)

    // Per-bin running energy for normalization
    private val binEnergy = FloatArray(numBins) { 1f }
    private val alpha = 0.95f

    /** Add audio samples, compute FFTs for each complete hop. */
    fun addSamples(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val needed = fftSize - samplesBuffered
            val copyLen = kotlin.math.min(needed, samples.size - offset)
            System.arraycopy(samples, offset, buffer, samplesBuffered, copyLen)
            samplesBuffered += copyLen
            offset += copyLen

            if (samplesBuffered >= fftSize) {
                processFrame()
                // Shift buffer: keep last (fftSize - hopSize) samples
                System.arraycopy(buffer, hopSize, buffer, 0, fftSize - hopSize)
                samplesBuffered = fftSize - hopSize
            }
        }
    }

    private fun processFrame() {
        // Apply Hanning window
        val windowed = FloatArray(fftSize) { buffer[it] * hanning[it] }

        // Compute FFT magnitude spectrum
        val mag = fft.magnitudeSpectrum(windowed)

        // Update spectrogram column
        val col = spectrogram[currentCol]
        for (b in 0 until numBins) {
            val binIdx = minBin + b
            val rawMag = mag[binIdx]
            // Running energy normalization
            binEnergy[b] = alpha * binEnergy[b] + (1 - alpha) * rawMag
            col[b] = if (binEnergy[b] > 1e-6f) rawMag / binEnergy[b] else 0f
        }

        currentCol = (currentCol + 1) % historyCols
    }

    /** Get the current spectrogram as a 2D array. */
    fun getSpectrogram(): Array<FloatArray> {
        // Return in chronological order
        val result = Array(historyCols) { i ->
            val srcIdx = (currentCol + i) % historyCols
            spectrogram[srcIdx].copyOf()
        }
        return result
    }

    /** Get the most recent column (current energy across all frequencies). */
    fun getCurrentColumn(): FloatArray {
        val prevCol = (currentCol - 1 + historyCols) % historyCols
        return spectrogram[prevCol].copyOf()
    }

    /** Find the frequency bin with peak energy. */
    fun findPeakBin(): Int {
        val col = getCurrentColumn()
        var maxBin = 0
        var maxVal = 0f
        for (i in col.indices) {
            if (col[i] > maxVal) {
                maxVal = col[i]
                maxBin = i
            }
        }
        return if (maxVal > 0.3f) maxBin else -1
    }

    /** Get energy at a specific bin over the last N columns. */
    fun getBinEnergy(bin: Int, numCols: Int): FloatArray {
        val result = FloatArray(kotlin.math.min(numCols, historyCols))
        for (i in result.indices) {
            val colIdx = (currentCol - 1 - i + historyCols) % historyCols
            result[result.size - 1 - i] = spectrogram[colIdx][bin]
        }
        return result
    }

    /** Get the bin index for a frequency in Hz. */
    fun freqToBin(freqHz: Float): Int {
        val bin = (freqHz * fftSize / sampleRate).toInt()
        return (bin - minBin).coerceIn(0, numBins - 1)
    }

    /** Get the center frequency for a bin. */
    fun binToFreq(bin: Int): Float {
        return (minBin + bin).toFloat() * sampleRate / fftSize
    }

    fun reset() {
        for (col in spectrogram) col.fill(0f)
        currentCol = 0
        samplesBuffered = 0
        buffer.fill(0f)
        binEnergy.fill(1f)
    }
}
```

**Step 1: Verify compilation**

Run: `./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Commit**

```bash
git add core/domain/src/main/java/.../cw/CwSpectrogram.kt
git commit -m "feat(cw): add sliding-window spectrogram for multi-channel CW detection"
```

---

### Task 3: Create Bayesian timing decoder

**Objective:** Implement a probability-based Morse timing analyzer that replaces hard thresholds with Bayesian probability distributions.

**Files:**
- Create: `core/domain/src/main/java/.../cw/CwBayesianDecoder.kt`

**Bayesian approach:**
- Instead of "is this a dit or a dash?" (hard decision)
- Compute: P(dit | duration), P(dash | duration), P(gap | duration)
- Use observed timing distributions as prior probabilities
- Layer probabilities: signal presence → symbol type → character → word

```kotlin
package com.rtbishop.look4sat.core.domain.cw

/**
 * Bayesian Morse timing decoder.
 * Replaces hard thresholds with probability-based decision making.
 *
 * Instead of:
 *   if (ratio < 1.5) → dit
 *   else if (ratio < 5.0) → dash
 *
 * We compute:
 *   P(dit | duration) = P(duration | dit) * P(dit) / P(duration)
 *   P(dash | duration) = P(duration | dash) * P(dash) / P(duration)
 *
 * And pick the most likely interpretation.
 */
internal class CwBayesianDecoder {

    // Morse timing parameters (will be learned from signal)
    private var dotDurationMs = 60f // initial 20 WPM
    private var speedWpm = 20f
    private var pitchHz = 700f

    // Symbol history for Bayesian inference
    private val recentDits = mutableListOf<Float>()
    private val recentDashes = mutableListOf<Float>()
    private val recentGaps = mutableListOf<Float>()

    // Current symbol being accumulated
    private var currentSymbol = StringBuilder()
    private var decodedText = StringBuilder()

    // Output
    private var _decodedText = ""
    val decodedText: String get() = _decodedText

    /**
     * Compute probability that a duration matches a Morse element type.
     * Uses Gaussian probability density centered on the expected duration.
     */
    private fun probabilityOf(durationMs: Float, expectedMs: Float, varianceMs: Float): Float {
        if (varianceMs <= 0f) return 0f
        val diff = durationMs - expectedMs
        return kotlin.math.exp(-(diff * diff) / (2 * varianceMs * varianceMs))
    }

    /**
     * Process a tone (signal present) duration in milliseconds.
     * Returns the most likely symbol type and its probability.
     */
    fun processTone(durationMs: Float): SymbolResult {
        val ditProb = probabilityOf(durationMs, dotDurationMs, dotDurationMs * 0.3f)
        val dashProb = probabilityOf(durationMs, dotDurationMs * 3, dotDurationMs * 0.5f)

        if (ditProb > dashProb && ditProb > 0.1f) {
            currentSymbol.append('.')
            recentDits.add(durationMs)
            updateSpeedEstimate()
            return SymbolResult('.', ditProb)
        } else if (dashProb > 0.1f) {
            currentSymbol.append('-')
            recentDashes.add(durationMs)
            return SymbolResult('-', dashProb)
        }
        return SymbolResult(null, 0f)
    }

    /**
     * Process a gap (silence) duration in milliseconds.
     * Determines if it's intra-char, inter-char, or word gap.
     * Returns the decoded character if a complete symbol was decoded.
     */
    fun processGap(durationMs: Float): Char? {
        if (currentSymbol.isEmpty()) {
            // No symbol in progress — could be a word gap
            val wordGapProb = probabilityOf(durationMs, dotDurationMs * 7, dotDurationMs * 1.0f)
            if (wordGapProb > 0.3f) {
                decodedText.append(' ')
                _decodedText = decodedText.toString()
                return ' '
            }
            return null
        }

        // Inter-char gap vs intra-char gap
        val interCharProb = probabilityOf(durationMs, dotDurationMs * 3, dotDurationMs * 0.5f)
        val intraCharProb = probabilityOf(durationMs, dotDurationMs * 1, dotDurationMs * 0.3f)
        val wordGapProb = probabilityOf(durationMs, dotDurationMs * 7, dotDurationMs * 1.0f)

        return when {
            wordGapProb > interCharProb && wordGapProb > 0.3f -> {
                val char = decodeCurrentSymbol()
                decodedText.append(' ')
                _decodedText = decodedText.toString()
                char
            }
            interCharProb > intraCharProb && interCharProb > 0.2f -> {
                val char = decodeCurrentSymbol()
                _decodedText = decodedText.toString()
                char
            }
            else -> null // intra-char gap, continue building symbol
        }
    }

    private fun decodeCurrentSymbol(): Char? {
        if (currentSymbol.isEmpty()) return null
        val morse = currentSymbol.toString()
        currentSymbol.clear()
        val char = morseToChar(morse)
        if (char != null) {
            decodedText.append(char)
        }
        return char
    }

    private fun updateSpeedEstimate() {
        if (recentDits.size < 3) return
        val sorted = recentDits.sorted()
        val median = sorted[sorted.size / 2]
        if (median > 0f) {
            dotDurationMs = dotDurationMs * 0.7f + median * 0.3f
            speedWpm = 60.0f / (50.0f * dotDurationMs / 1000.0f)
        }
    }

    fun setPitch(pitch: Float) { pitchHz = pitch }

    fun getSpeed(): Float = speedWpm

    fun reset() {
        dotDurationMs = 60f
        speedWpm = 20f
        recentDits.clear()
        recentDashes.clear()
        recentGaps.clear()
        currentSymbol.clear()
        decodedText.clear()
        _decodedText = ""
    }

    companion object {
        private val MORSE_TABLE = mapOf(
            "01" to 'A', "1000" to 'B', "1010" to 'C', "100" to 'D', "0" to 'E',
            "0010" to 'F', "110" to 'G', "0000" to 'H', "00" to 'I', "0111" to 'J',
            "101" to 'K', "0100" to 'L', "11" to 'M', "10" to 'N', "111" to 'O',
            "0110" to 'P', "1101" to 'Q', "010" to 'R', "000" to 'S', "1" to 'T',
            "001" to 'U', "0001" to 'V', "011" to 'W', "1001" to 'X', "1011" to 'Y',
            "1100" to 'Z', "01111" to '1', "00111" to '2', "00011" to '3',
            "00001" to '4', "00000" to '5', "10000" to '6', "11000" to '7',
            "11100" to '8', "11110" to '9', "11111" to '0',
            "010101" to '.', "110011" to ',', "001100" to '?', "011110" to '\'',
            "101011" to '!', "10010" to '/', "10110" to '(', "101101" to ')',
            "01000" to '&', "111000" to ':', "101010" to ';', "10001" to '=',
            "01010" to '+', "100001" to '-', "001101" to '_', "010010" to '"',
            "0001001" to '$', "011010" to '@'
        )
        fun morseToChar(morse: String): Char? = MORSE_TABLE[morse]
    }
}

data class SymbolResult(val symbol: Char?, val probability: Float)
```

**Step 1: Verify compilation**

Run: `./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Commit**

```bash
git add core/domain/src/main/java/.../cw/CwBayesianDecoder.kt
git commit -m "feat(cw): add Bayesian probability-based timing decoder"
```

---

### Task 4: Build multi-channel signal tracker

**Objective:** Create a channel tracker that monitors multiple frequency bins, extracts energy envelopes, and feeds the best one to the Bayesian decoder.

**Files:**
- Create: `core/domain/src/main/java/.../cw/CwChannelTracker.kt`

**Design:**
- Scan spectrogram for active frequency bins (energy > threshold)
- For each active bin, extract the energy envelope over time
- Track up to 3 channels simultaneously
- For each channel, compute signal presence probability
- Select the channel with highest confidence for output

```kotlin
package com.rtbishop.look4sat.core.domain.cw

/**
 * Multi-channel CW signal tracker.
 * Monitors the spectrogram for active frequency bins and extracts
 * energy envelopes for each detected signal.
 */
internal class CwChannelTracker(
    private val spectrogram: CwSpectrogram,
    private val numChannels: Int = 3
) {
    data class Channel(
        val bin: Int,
        val frequency: Float,
        var active: Boolean = false,
        var energy: Float = 0f,
        var history: MutableList<Float> = mutableListOf(),
        var confidence: Float = 0f
    )

    private val channels = Array(numChannels) { Channel(0, 0f) }
    private var activeCount = 0

    /** Scan spectrogram and update channel tracking. */
    fun update(): List<Channel> {
        val col = spectrogram.getCurrentColumn()
        val peaks = findPeaks(col, threshold = 0.3f, minDistance = 2)

        // Update existing channels
        for (ch in channels) {
            if (ch.active) {
                // Check if this bin is still active
                if (peaks.contains(ch.bin)) {
                    ch.energy = col[ch.bin]
                    ch.history.add(ch.energy)
                    if (ch.history.size > 40) ch.history.removeAt(0)
                    ch.confidence = computeConfidence(ch.history)
                } else {
                    // Signal lost — keep for a few frames then deactivate
                    ch.history.add(0f)
                    if (ch.history.size > 40) ch.history.removeAt(0)
                    ch.confidence *= 0.9f
                    if (ch.confidence < 0.1f) ch.active = false
                }
            }
        }

        // Assign new peaks to inactive channels
        var peakIdx = 0
        for (ch in channels) {
            if (!ch.active && peakIdx < peaks.size) {
                val bin = peaks[peakIdx]
                ch.bin = bin
                ch.frequency = spectrogram.binToFreq(bin)
                ch.active = true
                ch.energy = col[bin]
                ch.history.clear()
                ch.confidence = 0.5f
                peakIdx++
            }
        }

        activeCount = channels.count { it.active }
        return channels.filter { it.active }
    }

    /** Find peak bins in the current spectrum. */
    private fun findPeaks(spectrum: FloatArray, threshold: Float, minDistance: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        for (i in 1 until spectrum.size - 1) {
            if (spectrum[i] > spectrum[i - 1] && spectrum[i] > spectrum[i + 1] && spectrum[i] > threshold) {
                // Check minimum distance from existing peaks
                if (peaks.isEmpty() || i - peaks.last() >= minDistance) {
                    peaks.add(i)
                }
            }
        }
        peaks.sortByDescending { spectrum[it] }
        return peaks
    }

    /** Compute confidence score from energy history. */
    private fun computeConfidence(history: List<Float>): Float {
        if (history.size < 10) return 0.3f
        val recent = history.takeLast(10)
        val mean = recent.average().toFloat()
        val variance = recent.map { (it - mean) * (it - mean) }.average().toFloat()
        // Lower variance = more stable signal = higher confidence
        return if (mean > 0f) (mean / (mean + variance + 0.1f)).coerceIn(0f, 1f) else 0f
    }

    /** Get the best channel (highest confidence). */
    fun getBestChannel(): Channel? {
        return channels.filter { it.active }.maxByOrNull { it.confidence }
    }

    fun reset() {
        for (ch in channels) {
            ch.bin = 0; ch.frequency = 0f; ch.active = false
            ch.energy = 0f; ch.history.clear(); ch.confidence = 0f
        }
        activeCount = 0
    }
}
```

**Step 1: Verify compilation**

Run: `./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Commit**

```bash
git add core/domain/src/main/java/.../cw/CwChannelTracker.kt
git commit -m "feat(cw): add multi-channel signal tracker for spectrogram peak detection"
```

---

### Task 5: Integrate into new CwDecoder

**Objective:** Replace the existing CwDecoder with the new spectrogram-based multi-channel decoder.

**Files:**
- Modify: `core/domain/src/main/java/.../cw/CwDecoder.kt` (complete rewrite)
- Delete (optional): `CwResampler.kt`, `CwFilter.kt`, `CwGoertzel.kt`, `CwSTFFT.kt` (no longer needed)

**New CwDecoder flow:**
```
processBuffer(buffer):
  1. Feed samples to spectrogram
  2. Update channel tracker
  3. For each active channel:
     a. Extract energy envelope
     b. Detect signal presence (with Bayesian probability)
     c. Measure tone/gap durations
     d. Feed to Bayesian decoder
  4. Select best channel's output
```

```kotlin
class CwDecoder(
    val sampleRate: Int = 8000
) {
    private val spectrogram = CwSpectrogram(
        fftSize = 256, hopSize = 64,
        sampleRate = sampleRate, minBin = 6, maxBin = 38
    )
    private val channelTracker = CwChannelTracker(spectrogram)
    private val bayesianDecoder = CwBayesianDecoder()

    // Per-channel state tracking
    private data class ChannelState(
        var isSignal: Boolean = false,
        var toneSamples: Int = 0,
        var gapSamples: Int = 0,
        var lastThreshold: Float = 0f
    )
    private val channelStates = Array(3) { ChannelState() }

    // Output flows
    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow
    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength
    private val _estimatedPitch = MutableStateFlow<Float?>(null)
    val estimatedPitch: StateFlow<Float?> = _estimatedPitch
    private val _estimatedSpeed = MutableStateFlow<Float?>(null)
    val estimatedSpeed: StateFlow<Float?> = _estimatedSpeed

    // Timing: 1 sample at 4 kHz = 0.25 ms
    private val samplePeriodMs = 1000f / 4000 // 0.25 ms

    fun processBuffer(buffer: FloatArray) {
        // 1. Update spectrogram
        spectrogram.addSamples(buffer)

        // 2. Update channel tracker
        val activeChannels = channelTracker.update()

        // 3. Process each active channel
        for ((idx, channel) in activeChannels.withIndex()) {
            if (idx >= channelStates.size) break
            val state = channelStates[idx]
            val col = spectrogram.getCurrentColumn()
            val energy = if (channel.bin in col.indices) col[channel.bin] else 0f

            // Adaptive threshold for this channel
            val noiseFloor = 0.3f
            val threshold = noiseFloor + (energy - noiseFloor) * 0.3f
            state.lastThreshold = threshold

            // Signal present?
            if (energy > threshold) {
                if (!state.isSignal) {
                    // Rising edge — process gap
                    if (state.gapSamples > 0) {
                        val gapMs = state.gapSamples * samplePeriodMs
                        bayesianDecoder.processGap(gapMs)
                    }
                    state.gapSamples = 0
                    state.isSignal = true
                }
                state.toneSamples++
            } else {
                if (state.isSignal) {
                    // Falling edge — process tone
                    val toneMs = state.toneSamples * samplePeriodMs
                    bayesianDecoder.processTone(toneMs)
                    state.toneSamples = 0
                    state.isSignal = false
                }
                state.gapSamples++
            }
        }

        // 4. Update output from best channel
        val bestChannel = channelTracker.getBestChannel()
        if (bestChannel != null) {
            _estimatedPitch.value = bestChannel.frequency
            _signalStrength.value = bestChannel.confidence
            _estimatedSpeed.value = bayesianDecoder.getSpeed()
        }
        _decodedTextFlow.value = bayesianDecoder.decodedText
    }

    fun resetDecoder() {
        spectrogram.reset()
        channelTracker.reset()
        bayesianDecoder.reset()
        for (state in channelStates) {
            state.isSignal = false; state.toneSamples = 0
            state.gapSamples = 0; state.lastThreshold = 0f
        }
        _decodedTextFlow.value = ""
        _signalStrength.value = 0f
        _estimatedPitch.value = null
        _estimatedSpeed.value = null
    }
}
```

**Step 1: Verify compilation**

Run: `./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Commit**

```bash
git add core/domain/src/main/java/.../cw/CwDecoder.kt
git commit -m "feat(cw): replace with spectrogram-based multi-channel Bayesian decoder"
```

---

### Task 6: Rewrite unit tests

**Objective:** Update tests to cover the new architecture — FFT, spectrogram, Bayesian probability, multi-channel tracking.

**Files:**
- Modify: `core/domain/src/test/java/.../cw/CwDecoderTest.kt`

**Key test additions:**
- `fft_magnitudeSpectrum_detectsTone()` — generate 700 Hz tone, assert peak at correct bin
- `fft_magnitudeSpectrum_silence_isFlat()` — all-zero input, flat spectrum
- `spectrogram_addSamples_updatesEnergy()` — single tone increases energy at its bin
- `spectrogram_findPeakBin_returnsCorrectBin()` — strongest tone found
- `spectrogram_binToFreq_roundtrip()` — freq→bin→freq is consistent
- `bayesian_processTone_dit()` — short tone produces dit
- `bayesian_processTone_dash()` — 3x tone produces dash
- `bayesian_processGap_interChar()` — gap produces character
- `bayesian_processGap_wordGap()` — long gap adds space
- `channelTracker_update_createsChannels()` — single tone creates one channel
- `channelTracker_findPeaks_multipleTones()` — multiple tones create multiple channels
- `decoder_fullPipeline()` — end-to-end silence→no crash

**Step 1: Run tests**

Run: `./gradlew :core:domain:test --no-daemon`
Expected: BUILD SUCCESSFUL, 15+ tests pass

**Step 2: Commit**

```bash
git add core/domain/src/test/java/.../cw/CwDecoderTest.kt
git commit -m "test(cw): add tests for spectrogram, FFT, Bayesian decoder, channel tracker"
```

---

### Task 7: Full build verification

**Objective:** Ensure the app compiles and all tests pass.

**Step 1: Build debug APK**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL

**Step 2: Run all domain tests**

```bash
./gradlew :core:domain:test --no-daemon
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit and push**

```bash
git add -A
git commit -m "feat(cw): v3 spectrogram-based multi-channel Bayesian decoder"
git push fork main
```

---

## Files changed

| File | Action |
|------|--------|
| `core/domain/src/main/java/.../cw/CwFFT.kt` | Create |
| `core/domain/src/main/java/.../cw/CwSpectrogram.kt` | Create |
| `core/domain/src/main/java/.../cw/CwBayesianDecoder.kt` | Create |
| `core/domain/src/main/java/.../cw/CwChannelTracker.kt` | Create |
| `core/domain/src/main/java/.../cw/CwDecoder.kt` | Rewrite |
| `core/domain/src/main/java/.../cw/CwResampler.kt` | Keep (unused, can delete) |
| `core/domain/src/main/java/.../cw/CwFilter.kt` | Keep (unused, can delete) |
| `core/domain/src/main/java/.../cw/CwGoertzel.kt` | Keep (unused, can delete) |
| `core/domain/src/main/java/.../cw/CwSTFFT.kt` | Keep (unused, can delete) |
| `core/domain/src/test/java/.../cw/CwDecoderTest.kt` | Rewrite |

## Risks / tradeoffs

- **CPU cost:** FFT every 64 samples at 4 kHz = 62.5 FFTs/second. 256-point radix-2 FFT is ~2,500 float ops. On a modern phone this is negligible (< 0.1% CPU).
- **Memory:** Spectrogram = 40 cols × 33 bins × 4 bytes = ~5 KB. Trivial.
- **Frequency resolution:** 31.25 Hz per bin. Fine enough for CW (typical tone stability is ±10 Hz, but ±50 Hz is still fine).
- **Time resolution:** 8 ms per column. At 20 WPM, a dit is 60 ms = 7.5 columns. Enough for accurate timing.
- **Multi-channel complexity:** Current implementation tracks up to 3 channels. In practice, the strongest signal is usually the desired one. The channel tracker handles this by selecting highest confidence.
- **Bayesian advantage:** The probability-based approach naturally handles ambiguous timing. A tone that's between dit and dash length won't be forced into a wrong category — it'll have low probability for both, and the decoder can wait for more context.
- **Backward compatibility:** `CwDecoder` class name and public interface unchanged. UI/ViewModel work without modification.
- **Noise performance:** The normalized spectrogram (per-bin energy tracking) naturally handles varying noise floors. A signal that's 2x above the noise floor at its bin will be detected regardless of absolute level.