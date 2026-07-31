# CW Morse Code Decoder — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add a built-in Morse Code (CW) decoder to the Look4Sat linear satellite transceiver interface. Captures audio from the microphone, decodes CW in real-time, and displays the decoded text inline without blocking existing features.

**Architecture:** Domain layer DSP (bandpass filter + envelope detection + timing logic) mirrors the existing `SstvDecoder` pattern. State managed in `RadarState` / `RadarViewModel`. UI is a compact collapsible panel inside the `TransceiverItem` expanded card, below the Doppler calculator, using the same `IAudioCapture` abstraction for microphone access.

**Tech Stack:** Kotlin, Jetpack Compose, Android AudioRecord (via existing `IAudioCapture`), pure Kotlin DSP (no NDK), `kotlinx.coroutines.flow`.

---

## Current Context

The app already has:
- **`IAudioCapture`** (`core/domain/.../usecase/IAudioCapture.kt`) — platform abstraction for microphone audio capture, emits `Flow<FloatArray>` at configurable sample rate
- **`SstvDecoder`** (`core/domain/.../sstv/SstvDecoder.kt`) — full audio→image decoder for SSTV, the architectural pattern to follow
- **`SstvDsp`** (`core/domain/.../sstv/SstvDsp.kt`) — DSP utilities (FFT, window functions, filters) — reusable for CW
- **`RadarViewModel`** — manages SSTV lifecycle (init decoder, start/stop recording, handle permission)
- **`TransceiversPage.kt`** — expanded transceiver card with Doppler calculator already added
- **`RadarState.kt`** — `SstvSubState` pattern to follow for CW substate

**CW decoding pipeline (audio → text):**
```
Audio buffer → Bandpass filter (~600-800 Hz) → Envelope detection → Threshold → 
Timing (dit/dash/symbol/word gaps) → Morse character lookup → Live text output
```

**Key frequencies:**
- CW tone: typically 600-800 Hz (user-selectable)
- Sampling rate: 8000 Hz (reuse from SSTV, adequate for ~1 kHz bandwidth)
- Dit timing: 30-60 ms at typical 20-30 WPM (auto-baud rate detection)

---

## Step-by-step Plan

### Task 1: Create CW decoder DSP (domain layer)

**Objective:** Implement the core CW signal processing: bandpass filter, envelope detection, and timing logic to convert audio samples to dit/dash symbols.

**Files:**
- Create: `core/domain/src/main/java/com/rtbishop/look4sat/core/domain/cw/CwDsp.kt`
- Create: `core/domain/src/main/java/com/rtbishop/look4sat/core/domain/cw/CwDecoder.kt`
- Modify: `core/domain/build.gradle.kts` (no changes needed — pure Kotlin)

**Step 1: Create `CwDsp.kt` — DSP utilities for CW decoding**

```kotlin
package com.rtbishop.look4sat.core.domain.cw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DSP utilities for CW (Morse code) decoding.
 * Pure Kotlin, no NDK required.
 */
internal object CwDsp {

    /**
     * Design a simple bandpass FIR filter coefficients using windowed sinc method.
     * @param lowCutoff  lower cutoff frequency (Hz) as fraction of sampleRate
     * @param highCutoff upper cutoff frequency (Hz) as fraction of sampleRate
     * @param taps       filter length (must be odd)
     */
    fun bandpassFir(lowCutoff: Double, highCutoff: Double, taps: Int): FloatArray {
        val n = if (taps % 2 == 0) taps + 1 else taps
        val half = n / 2
        val coeffs = FloatArray(n)
        for (i in 0 until n) {
            val idx = i - half
            if (idx == 0) {
                coeffs[i] = (2.0 * (highCutoff - lowCutoff)).toFloat()
            } else {
                val x = PI * idx
                coeffs[i] = ((sin(2 * highCutoff * x) - sin(2 * lowCutoff * x)) / x).toFloat()
            }
            // Hamming window
            coeffs[i] = (coeffs[i] * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))).toFloat()
        }
        // Normalize
        val sum = coeffs.sum()
        if (sum != 0f) for (i in 0 until n) coeffs[i] /= sum
        return coeffs
    }

    /** Apply FIR filter to a buffer. */
    fun applyFir(buffer: FloatArray, coeffs: FloatArray): FloatArray {
        val out = FloatArray(buffer.size)
        for (i in buffer.indices) {
            var sum = 0f
            for (j in coeffs.indices) {
                val idx = i - j
                if (idx >= 0) sum += buffer[idx] * coeffs[j]
            }
            out[i] = sum
        }
        return out
    }

    /** Simple envelope detector: abs + low-pass smoothing. */
    fun envelope(signal: FloatArray, alpha: Float = 0.1f): FloatArray {
        val env = FloatArray(signal.size)
        var s = 0f
        for (i in signal.indices) {
            s = alpha * kotlin.math.abs(signal[i]) + (1 - alpha) * s
            env[i] = s
        }
        return env
    }

    /** Estimate noise floor (median of envelope). */
    fun noiseFloor(env: FloatArray, fraction: Float = 0.3f): Float {
        val sorted = env.sortedArray()
        val median = sorted[sorted.size / 2]
        return median + (sorted[sorted.size * 9 / 10] - median) * fraction
    }

    /** Simple Goertzel to detect a specific tone frequency. */
    fun goertzel(buffer: FloatArray, targetFreq: Float, sampleRate: Int): Float {
        val omega = 2.0 * PI * targetFreq / sampleRate
        val coeff = 2.0 * cos(omega)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (sample in buffer) {
            s0 = sample.toDouble() + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        val power = s2 * s2 + s1 * s1 - coeff * s1 * s2
        return sqrt(kotlin.math.abs(power)).toFloat()
    }
}
```

**Step 2: Create `CwDecoder.kt` — Morse character table + timing logic**

```kotlin
package com.rtbishop.look4sat.core.domain.cw

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real-time CW (Morse code) decoder.
 * Processes audio buffers and emits decoded text characters.
 *
 * Morse timing (paris method):
 *   Dit = 1 unit
 *   Dash = 3 units
 *   Intra-char gap = 1 unit
 *   Inter-char gap = 3 units
 *   Word gap = 7 units
 */
class CwDecoder(
    val sampleRate: Int = 8000,
    val cwToneFreq: Float = 700f,
    val filterWidth: Float = 200f
) {
    // Filter coefficients (pre-computed)
    private val firCoeffs = CwDsp.bandpassFir(
        lowCutoff = ((cwToneFreq - filterWidth / 2) / sampleRate).toDouble(),
        highCutoff = ((cwToneFreq + filterWidth / 2) / sampleRate).toDouble(),
        taps = 127
    )

    // Decoder state
    private var filterState = FloatArray(0)
    private var envelopeState = 0f
    private var previousEnvelope = 0f
    private var isSignalPresent = false
    private var signalOnTime = 0  // samples since signal started
    private var signalOffTime = 0 // samples since signal ended
    private var decodedText = StringBuilder()
    private var currentSymbol = StringBuilder()

    private val _decodedTextFlow = MutableStateFlow("")
    val decodedTextFlow: StateFlow<String> = _decodedTextFlow

    /** Process a buffer of audio samples. */
    fun processBuffer(buffer: FloatArray) {
        // 1. Bandpass filter
        val filtered = CwDsp.applyFir(buffer, firCoeffs)

        // 2. Envelope detection
        val env = CwDsp.envelope(filtered, 0.1f)

        // 3. Adaptive threshold
        val floor = CwDsp.noiseFloor(env)
        val threshold = floor * 1.5f

        // 4. Timing analysis
        for (sample in env) {
            if (sample > threshold) {
                // Signal ON
                if (!isSignalPresent) {
                    // Rising edge — end of silence
                    if (signalOffTime > 0) {
                        processSilence(signalOffTime)
                    }
                    signalOffTime = 0
                    isSignalPresent = true
                }
                signalOnTime++
            } else {
                // Signal OFF
                if (isSignalPresent) {
                    // Falling edge — end of tone
                    processTone(signalOnTime)
                    signalOnTime = 0
                    isSignalPresent = false
                }
                signalOffTime++
            }
        }

        _decodedTextFlow.value = decodedText.toString()
    }

    private fun processTone(duration: Int) {
        val unit = estimateUnit(duration)
        if (unit == 0) return

        val ratio = duration.toFloat() / unit
        if (ratio < 1.5f) {
            currentSymbol.append('.')  // Dit
        } else if (ratio < 5f) {
            currentSymbol.append('-')  // Dash
        }
    }

    private fun processSilence(duration: Int) {
        // If we have accumulated symbol characters, it's an inter-char gap
        if (currentSymbol.isNotEmpty()) {
            val char = morseToChar(currentSymbol.toString())
            if (char != null) {
                decodedText.append(char)
            }
            currentSymbol.clear()
        } else {
            // Word gap (7+ units)
            val unit = estimateUnitFromSilence(duration)
            val ratio = if (unit > 0) duration.toFloat() / unit else 0f
            if (ratio >= 7f) {
                decodedText.append(' ')
            }
        }
    }

    /** Estimate the timing unit based on recent dits. */
    private fun estimateUnit(duration: Int): Int {
        // For first detection, estimate based on typical 20 WPM = 60ms dit
        // 8000 Hz * 0.06s = 480 samples
        return if (duration < 800) sampleRate / 20 else sampleRate / 15
    }

    private fun estimateUnitFromSilence(duration: Int): Int {
        return sampleRate / 20
    }

    fun resetDecoder() {
        isSignalPresent = false
        signalOnTime = 0
        signalOffTime = 0
        decodedText.clear()
        currentSymbol.clear()
        _decodedTextFlow.value = ""
    }

    companion object {
        private val MORSE_TABLE = mapOf(
            ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E',
            "..-." to 'F', "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J',
            "-.-" to 'K', ".-.." to 'L', "--" to 'M', "-." to 'N', "---" to 'O',
            ".--." to 'P', "--.-" to 'Q', ".-." to 'R', "..." to 'S', "-" to 'T',
            "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X', "-.--" to 'Y',
            "--.." to 'Z', ".----" to '1', "..---" to '2', "...--" to '3',
            "....-" to '4', "....." to '5', "-...." to '6', "--..." to '7',
            "---.." to '8', "----." to '9', "-----" to '0',
            ".-.-.-" to '.', "--..--" to ',', "..--.." to '?', ".----." to '\'',
            "-.-.--" to '!', "-..-." to '/', "-.--." to '(', "-.--.-" to ')',
            ".-..." to '&', "---..." to ':', "-.-.-." to ';', "-...-" to '=',
            ".-.-." to '+', "-....-" to '-', "..--.-" to '_', ".-..-." to '"',
            "...-..-" to '$', ".--.-." to '@'
        )

        fun morseToChar(morse: String): Char? = MORSE_TABLE[morse]
    }
}
```

**Step 3: Verify compilation**

Run: `cd /mnt/e/look4sat-work && ./gradlew :core:domain:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add core/domain/src/main/java/com/rtbishop/look4sat/core/domain/cw/
git commit -m "feat(cw): add CW decoder with DSP and Morse timing logic"
```

---

### Task 2: Add CW state to RadarState + RadarAction

**Objective:** Define the CW substate and action types so the ViewModel and UI can communicate.

**Files:**
- Modify: `feature/radar/src/main/java/.../RadarState.kt`

**Step 1: Add CW substate and actions**

Add to `RadarState.kt` after the SSTV section:

```kotlin
// --- CW Decoder ---

enum class CwStatus { Idle, Listening }

data class CwSubState(
    val status: CwStatus = CwStatus.Idle,
    val hasPermission: Boolean = false,
    val decodedText: String = "",
    val cwToneFreq: Float = 700f,
    val isExpanded: Boolean = false,
    val signalStrength: Float = 0f
)
```

Add to `RadarState` data class after `sstv`:

```kotlin
val cw: CwSubState = CwSubState()
```

Add to `RadarAction` sealed interface:

```kotlin
    // CW actions
    data object CwStartListening : RadarAction
    data object CwStopListening : RadarAction
    data object CwReset : RadarAction
    data class CwSetToneFreq(val freq: Float) : RadarAction
    data class CwToggleExpanded(val expanded: Boolean) : RadarAction
    data class CwPermissionResult(val granted: Boolean) : RadarAction
```

**Step 2: Commit**

```bash
git add feature/radar/src/main/java/.../RadarState.kt
git commit -m "feat(cw): add CW decoder state and actions"
```

---

### Task 3: Wire CW decoder into RadarViewModel

**Objective:** Initialize the CW decoder from audio capture, process buffers, and update state.

**Files:**
- Modify: `feature/radar/src/main/java/.../RadarViewModel.kt`

**Step 1: Add CW decoder member variables**

```kotlin
private var cwDecoder: CwDecoder? = null
private var cwListeningJob: Job? = null
```

**Step 2: Add CW permission handling**

In the `RadarAction.SstvPermissionResult` block, also grant CW permission:

```kotlin
is RadarAction.CwPermissionResult -> {
    _uiState.update { it.copy(cw = it.cw.copy(hasPermission = action.granted)) }
    if (action.granted) initCwDecoder()
}
```

**Step 3: Add CW start/stop/reset handlers**

```kotlin
private fun initCwDecoder() {
    if (cwDecoder == null) {
        cwDecoder = CwDecoder(
            sampleRate = audioCapture.sampleRate,
            cwToneFreq = _uiState.value.cw.cwToneFreq
        )
    }
}

private fun startCwListening() {
    val decoder = cwDecoder ?: return
    decoder.resetDecoder()
    // Collect decoded text flow
    cwListeningJob = viewModelScope.launch {
        decoder.decodedTextFlow.collect { text ->
            _uiState.update { it.copy(cw = it.cw.copy(decodedText = text)) }
        }
    }
    // Start audio capture
    cwListeningJob = viewModelScope.launch {
        audioCapture.audioFlow().collect { buffer ->
            decoder.processBuffer(buffer)
        }
    }
    _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Listening)) }
}

private fun stopCwListening() {
    cwListeningJob?.cancel()
    cwListeningJob = null
    _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Idle)) }
}
```

**Step 4: Add CW action dispatch**

In the `when (action)` block, add:

```kotlin
is RadarAction.CwStartListening -> {
    if (!_uiState.value.cw.hasPermission) {
        requestMicPermission()
        _uiState.update { it.copy(cw = it.cw.copy(status = CwStatus.Listening)) }
    } else {
        startCwListening()
    }
}
RadarAction.CwStopListening -> stopCwListening()
RadarAction.CwReset -> {
    cwDecoder?.resetDecoder()
    _uiState.update { it.copy(cw = it.cw.copy(decodedText = "")) }
}
is RadarAction.CwSetToneFreq -> {
    _uiState.update { it.copy(cw = it.cw.copy(cwToneFreq = action.freq)) }
    cwDecoder = CwDecoder(sampleRate = audioCapture.sampleRate, cwToneFreq = action.freq)
}
is RadarAction.CwToggleExpanded -> {
    _uiState.update { it.copy(cw = it.cw.copy(isExpanded = action.expanded)) }
}
is RadarAction.CwPermissionResult -> {
    _uiState.update { it.copy(cw = it.cw.copy(hasPermission = action.granted)) }
    if (action.granted) initCwDecoder()
}
```

**Step 5: Commit**

```bash
git add feature/radar/src/main/java/.../RadarViewModel.kt
git commit -m "feat(cw): wire CW decoder into RadarViewModel"
```

---

### Task 4: Add CW UI to TransceiversPage

**Objective:** Add a compact, collapsible CW decoder panel to the transceiver expanded card, below the Doppler calculator. Does not block other features.

**Files:**
- Modify: `feature/radar/src/main/java/.../TransceiversPage.kt`
- Modify: `core/presentation/src/main/res/values/strings.xml`

**Step 1: Add string resources**

```xml
<string name="radar_cw_decoder">CW Decoder</string>
<string name="radar_cw_start">Start Listening</string>
<string name="radar_cw_stop">Stop</string>
<string name="radar_cw_reset">Clear</string>
<string name="radar_cw_tone">Tone (Hz)</string>
<string name="radar_cw_expand">CW Decoder</string>
```

**Step 2: Add CW decoder composable**

Add a new composable `CwDecoderPanel` at the end of `TransceiversPage.kt`:

```kotlin
@Composable
private fun CwDecoderPanel(
    cw: CwSubState,
    onAction: (RadarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header row: expand/collapse toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAction(RadarAction.CwToggleExpanded(!cw.isExpanded)) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.radar_cw_decoder),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                painter = painterResource(id = if (cw.isExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(visible = cw.isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Control buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (cw.status == CwStatus.Idle) {
                        Button(onClick = { onAction(RadarAction.CwStartListening) }) {
                            Text(stringResource(R.string.radar_cw_start))
                        }
                    } else {
                        Button(onClick = { onAction(RadarAction.CwStopListening) }) {
                            Text(stringResource(R.string.radar_cw_stop))
                        }
                    }
                    OutlinedButton(onClick = { onAction(RadarAction.CwReset) }) {
                        Text(stringResource(R.string.radar_cw_reset))
                    }
                }

                // Tone frequency field
                OutlinedTextField(
                    value = cw.cwToneFreq.toInt().toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { freq ->
                            onAction(RadarAction.CwSetToneFreq(freq.toFloat()))
                        }
                    },
                    label = { Text(stringResource(R.string.radar_cw_tone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // Decoded text output
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Decoded:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = cw.decodedText.ifEmpty() { "Waiting for CW signal..." },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
```

**Step 3: Wire into ExpandedRadioControl**

In `ExpandedRadioControl`, after the Doppler calculator and before the control buttons, add:

```kotlin
// CW decoder panel
CwDecoderPanel(cw = cw, onAction = onAction)
```

**Step 4: Pass cw state to TransceiverItem and ExpandedRadioControl**

Add `cw: CwSubState` parameter to `TransceiverItem` and `ExpandedRadioControl`:

```kotlin
// TransceiverItem signature
cw: CwSubState,

// ExpandedRadioControl signature
cw: CwSubState,
```

**Step 5: Pass cw from TransceiversPage**

```kotlin
fun TransceiversPage(
    cw: CwSubState,
    ...
)
```

**Step 6: Wire from RadarScreen**

```kotlin
RadarPage.Transceivers -> TransceiversPage(
    cw = uiState.cw,
    ...
)
```

**Step 7: Commit**

```bash
git add feature/radar/src/main/java/.../TransceiversPage.kt core/presentation/.../strings.xml
git commit -m "feat(cw): add CW decoder UI panel to transceivers page"
```

---

### Task 5: Add CW decoder unit tests

**Objective:** Test the Morse lookup table, DSP filter, and basic timing logic.

**Files:**
- Create: `core/domain/src/test/java/com/rtbishop/look4sat/core/domain/cw/CwDecoderTest.kt`

**Step 1: Write tests**

```kotlin
package com.rtbishop.look4sat.core.domain.cw

import org.junit.Assert.*
import org.junit.Test

class CwDecoderTest {

    @Test
    fun morseToChar_basicLetters() {
        assertEquals('A', CwDecoder.morseToChar(".-"))
        assertEquals('S', CwDecoder.morseToChar("..."))
        assertEquals('O', CwDecoder.morseToChar("---"))
    }

    @Test
    fun morseToChar_numbers() {
        assertEquals('1', CwDecoder.morseToChar(".----"))
        assertEquals('0', CwDecoder.morseToChar("-----"))
    }

    @Test
    fun morseToChar_unknown_returnsNull() {
        assertNull(CwDecoder.morseToChar("....."))
    }

    @Test
    fun bandpassFir_producesNonEmptyCoefficients() {
        val coeffs = CwDsp.bandpassFir(0.075, 0.125, 127)
        assertTrue(coeffs.isNotEmpty())
        assertEquals(127, coeffs.size)
        // Sum should be approximately 1.0
        val sum = coeffs.sum()
        assertTrue("Sum should be ~1.0, got $sum", sum > 0.9 && sum < 1.1)
    }

    @Test
    fun applyFir_preservesLength() {
        val coeffs = CwDsp.bandpassFir(0.075, 0.125, 31)
        val input = FloatArray(100) { kotlin.math.sin(it * 0.1f) }
        val output = CwDsp.applyFir(input, coeffs)
        assertEquals(input.size, output.size)
    }

    @Test
    fun envelope_isNonNegative() {
        val input = FloatArray(50) { if (it % 2 == 0) 0.5f else -0.3f }
        val env = CwDsp.envelope(input, 0.2f)
        for (v in env) assertTrue("Envelope should be >= 0, got $v", v >= 0f)
    }

    @Test
    fun goertzel_detectsPresentTone() {
        val sampleRate = 8000
        val targetFreq = 700f
        // Generate a 700 Hz tone
        val buffer = FloatArray(sampleRate) { kotlin.math.sin(2 * kotlin.math.PI * targetFreq * it / sampleRate).toFloat() }
        val power = CwDsp.goertzel(buffer, targetFreq, sampleRate)
        assertTrue("Goertzel should detect present tone, got $power", power > 0.1f)
    }

    @Test
    fun goertzel_rejectsAbsentTone() {
        val sampleRate = 8000
        val targetFreq = 700f
        // Generate a 2000 Hz tone (no match)
        val buffer = FloatArray(sampleRate) { kotlin.math.sin(2 * kotlin.math.PI * 2000f * it / sampleRate).toFloat() }
        val power = CwDsp.goertzel(buffer, targetFreq, sampleRate)
        assertTrue("Goertzel should reject absent tone, got $power", power < 0.1f)
    }

    @Test
    fun resetDecoder_clearsText() {
        val decoder = CwDecoder()
        decoder.resetDecoder()
        assertEquals("", decoder.decodedTextFlow.value)
    }
}
```

**Step 2: Run tests**

Run: `./gradlew :core:domain:test --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass

**Step 3: Commit**

```bash
git add core/domain/src/test/java/.../CwDecoderTest.kt
git commit -m "test(cw): add CW decoder unit tests"
```

---

### Task 6: Final build verification

**Objective:** Ensure the full app compiles and all tests pass.

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

**Step 3: Send APK**

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Share with the user for testing.

---

## Files likely to change

| File | Action |
|------|--------|
| `core/domain/src/main/java/.../cw/CwDsp.kt` | Create |
| `core/domain/src/main/java/.../cw/CwDecoder.kt` | Create |
| `core/domain/src/test/java/.../cw/CwDecoderTest.kt` | Create |
| `feature/radar/src/main/java/.../RadarState.kt` | Modify (add CwSubState, actions) |
| `feature/radar/src/main/java/.../RadarViewModel.kt` | Modify (wire decoder) |
| `feature/radar/src/main/java/.../TransceiversPage.kt` | Modify (add CW panel) |
| `feature/radar/src/main/java/.../RadarScreen.kt` | Modify (pass cw state) |
| `core/presentation/src/main/res/values/strings.xml` | Modify (add CW strings) |

## Risks / tradeoffs

- **Audio conflict:** CW decoder uses the same `IAudioCapture` as SSTV. Only one can listen at a time. The ViewModel should stop SSTV when CW starts and vice versa.
- **Performance:** FIR filter with 127 taps per audio buffer is lightweight (~1ms per 1024-sample buffer at 8kHz). Pure Kotlin is fast enough.
- **Accuracy:** The simple timing-based decoder works well for clean CW signals (~20-30 WPM). Noisy signals or extreme speeds (>40 WPM) will degrade accuracy. The Goertzel tone detector helps suppress false triggers from non-CW signals.
- **UI layout:** The CW panel is collapsible by default (controlled by `isExpanded`). It appears below the Doppler calculator and above the radio control buttons, fitting in the existing scrollable area without blocking other features.
- **Permission:** `RECORD_AUDIO` permission is already requested for SSTV. The CW decoder can reuse the same permission flow.
- **No NDK:** Pure Kotlin DSP is sufficient for the narrow bandwidth of CW (200-300 Hz). No need for FFT-based spectrogram analysis for v1.