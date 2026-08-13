# Third-Party Notices — CW Decode Module

## DeepCW neural CW decoding model

The CW (Morse code) decoder in this module performs inference with a neural
network model obtained from the DeepCW project.

| | |
|---|---|
| **Component** | `src/main/assets/deepcw/model.onnx` and `model.onnx.json` |
| **Upstream project** | DeepCW / deepcw-engine |
| **Source repository** | https://github.com/e04/deepcw-engine |
| **Author / copyright** | e04 |
| **License** | GNU Affero General Public License v3.0 only (AGPL-3.0-only) |
| **License text** | [`DeepCW-AGPL-3.0.txt`](DeepCW-AGPL-3.0.txt) |
| **Obtained at commit** | `8e264d243bbd4467bd19f3f28292219405b47e0e` |
| **File size** | 15,139,839 bytes |
| **SHA-256** | `ef120799457bca042d4690944f0faf93268eb4654e7f50f28784ad63bdc1fe02` |
| **Modifications** | None. The full fp32 model is vendored byte-for-byte as published upstream. |

Related upstream repositories by the same author (not vendored here):

- https://github.com/e04/web-deep-cw-decoder — reference web application
- https://github.com/e04/HamNoise — neural noise reduction (not used)

### License compatibility

Look4Sat is licensed under the GNU General Public License v3.0 or later
(GPL-3.0-or-later). The DeepCW model is licensed under AGPL-3.0-only.

Section 13 of the GPL version 3 expressly permits combining GPL-3.0 covered
work with AGPL-3.0 covered work; the resulting combination may be conveyed,
with the AGPL's additional network-interaction requirement applying to the
AGPL-covered portion. Accordingly:

- The Look4Sat source code remains under GPL-3.0-or-later.
- The DeepCW model remains under AGPL-3.0-only.
- Distributions of the combined application are accompanied by complete
  corresponding source, satisfying both licenses.

### AGPL section 13 (network interaction)

Inference runs entirely on the local device via ONNX Runtime. The application
does not offer the model's functionality to users interacting with it remotely
over a network, so the additional network-source-offer requirement of AGPL-3.0
section 13 is not triggered by this usage. The complete corresponding source
for both the application and the vendored model remains publicly available at
the repository hosting this file.

### Reproducing the vendored files

```bash
SHA=8e264d243bbd4467bd19f3f28292219405b47e0e
curl -sLO https://raw.githubusercontent.com/e04/deepcw-engine/$SHA/model.onnx
curl -sLO https://raw.githubusercontent.com/e04/deepcw-engine/$SHA/model.onnx.json
curl -sL -o DeepCW-AGPL-3.0.txt \
  https://raw.githubusercontent.com/e04/deepcw-engine/$SHA/LICENSE
sha256sum model.onnx
# expected: ef120799457bca042d4690944f0faf93268eb4654e7f50f28784ad63bdc1fe02
# copy model.onnx and model.onnx.json into assets/deepcw/ unchanged
```

## ONNX Runtime

Inference engine: `com.microsoft.onnxruntime:onnxruntime-android`, licensed
under the MIT License. Consumed as a published Maven artifact; not vendored in
this repository.
