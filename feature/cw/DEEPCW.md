# DeepCW Decoder — module documentation

The `feature:cw` module decodes Morse code (CW) with the DeepCW neural model
instead of a classical DSP detector. This document records the architecture,
the model provenance, the pitfalls we hit on real devices, and the verification
results. It exists so the next person does not have to re-derive any of it.

## Architecture

```
microphone (44.1 kHz PCM float)
        │  AudioCapture.audioFlow()  → ~100 ms chunks
        ▼
CwDecodeScreen (feature:cw, pure Compose)
        │  decoder.processBuffer(chunk)      waterfall.pushSamples(chunk)
        ▼                                    ▼
CwDeepDecoder (core:data)             CwWaterfallState (feature:cw)
   resample → 3200 Hz                    resample → 3200 Hz
   CwDeepBuffer (20 s rolling)           CwDeepSpectrogram.compute
   CwDeepSpectrogram.compute             → rolling spectrogram history
   ONNX Runtime session.run             → Canvas waterfall
   CwCtcDecoder.greedy                  (400–1200 Hz band, same magnitudes
   → decodedText StateFlow               the model sees)
```

**Why the split.** `core:domain` must stay pure Kotlin/JVM (KMP migration
headroom, per `AGENTS.md`), so the ONNX Runtime dependency — which ships native
libraries — lives in `core:data` behind the `ICwDecoder` interface. The
spectrogram front-end (`CwDeepSpectrogram`) and CTC collapse (`CwCtcDecoder`)
are pure Kotlin and live in `core:domain`, where they are unit-tested against
the Python reference with golden vectors.

**Streaming model, not incremental.** DeepCW is a whole-utterance CTC model. It
rewrites earlier characters as more context arrives, so incremental appending is
wrong. The decoder keeps a rolling buffer capped at 20 s and re-runs the whole
window every 1.5 s, replacing the displayed text outright. 20 s is the smallest
window that hits 0 % CER on the reference clip *and* the largest that keeps
inference comfortably faster than real time (measured ~14× headroom on a server
CPU; timed per-window on device and reported via `lastInferenceMs`).

## Model

| | fp32 (original) | int8 (shipped) |
|---|---|---|
| Size | 15,139,839 bytes | 4,248,808 bytes |
| Derivation | — | `quantize_dynamic` (weights → QUInt8, activations float32) |
| Input | `spectrogram` [1,1,T,65] float32 | unchanged |
| Output | `log_probs` [1,T,42] float32 | unchanged |
| In APK | no (available as a release asset) | yes (`assets/deepcw/model.onnx`) |

The int8 model ships inside the APK: it is ~4× smaller and measurably identical
to fp32 on synthetic CW at SNR ≥ −4 dB (both degrade together below that). The
fp32 model is published as a separate release asset for anyone who wants the
highest-fidelity reference. Both are AGPL-3.0-only — see
[`licenses/NOTICE.md`](licenses/NOTICE.md) for provenance, commit SHA and hashes.

Audio must be packaged **uncompressed** (`noCompress += "onnx"` in the app
module): ONNX Runtime mmap's assets and refuses compressed ones.

## Pitfalls fixed on real devices (release-only)

1. **R8 minification breaks the JNI binding.** `isMinifyEnabled = true` in the
   convention plugin, but `onnxruntime-android` ships **no** consumer ProGuard
   rules, so `ai.onnxruntime.*` got renamed and native code crashed with no Java
   stack trace. Fixed with
   `-keep class ai.onnxruntime.** { *; }` in `app/proguard-rules.pro`.
   Symptom was the exact "flash to home screen, empty crash log" we chased.
2. **Model load in `init{}`** took down the whole composable on failure; moved
   to lazy `ensureLoaded()` with an `errorMessage` path.
3. **Default thread count** saturated all cores and starved audio capture/UI;
   capped `setIntraOpNumThreads` to `cores−1` (max 4).
4. **Compose snapshot state written from the audio thread** crashes at runtime
   with no log from business-class instrumentation; the waterfall now uses a
   `MutableStateFlow` revision counter, and both the row deque and the pending
   buffer are lock-guarded.
5. **`LaunchedEffect { launch { collect() } }`** leaked a second `AudioRecord`
   when toggling; collection now runs directly in the effect body keyed on
   `(isListening, permissionGranted)`.

Diagnostics (no-adb): `MainApplication` already writes uncaught exceptions to
`files/crash_log.txt`. The decoder additionally persists load/inference failures
to `files/deepcw_load_error.txt` / `files/deepcw_infer_error.txt` and step
markers to `files/probe_cw.txt` (`load_begin → load_session_ok → infer_begin →
infer_done`).

## Verification

- **Golden vectors**: `CwDeepSpectrogramTest` + `CwDeepGoldenVectorTest` pin the
  Kotlin spectrogram to the Python reference across 5005 values (tolerance
  1e-4). 79 domain tests pass.
- **End-to-end on server**: real 20 s noisy CW audio → resample → spectrogram →
  ONNX → greedy CTC reproduces `CQ CQ DE BG7NTA BG7NTA K 5NN TU 73`
  character-for-character (~1 s inference, fp32).
- **On device (release APK)**: decodes 40 WPM CW at high accuracy. Some UI
  latency is expected on the first decode cycle (model load + first inference);
  sustained decoding stays real-time.
