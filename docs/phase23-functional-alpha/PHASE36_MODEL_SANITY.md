# Phase 3.6 Model Sanity

- Model exact name: `sherpa-onnx-streaming-paraformer-bilingual-zh-en`
- Source: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2`
- Archive size: `1,047,319,737` bytes
- Archive SHA256: `5462A1FCE42693DEAE572AF1E8C4687124B12AA85FE61FF4D3168BB5280E205F`
- Extracted file size: `1,103,215,837` bytes
- Encoder: `encoder.int8.onnx` (also `encoder.onnx` is present)
- Decoder: `decoder.int8.onnx` (also `decoder.onnx` is present)
- Tokens: `tokens.txt`
- Test WAV count: 5 (`0.wav`, `1.wav`, `2.wav`, `3.wav`, `8k.wav`)

## Host decode

`HOST_RTF_ONLY`: official `sherpa-onnx==1.13.7` Python package, CPU, real ONNX
files, and official `0.wav` plus `1.wav`.

- Total audio duration: `15.153` seconds
- Decode elapsed: `3.049` seconds
- Host RTF: `0.2012`
- Sample 0 transcript: `\u6628\u5929\u662f monday today is li \u73ed\u4e8c the day after tomorrow \u662f\u661f\u671f\u671f`
- Sample 1 transcript: `\u55ef\u8fd9\u662f\u7b2c\u4e00\u79cd\u7b2c\u4e8c\u79cd\u53eb\u5443\u4e0e always o s \u4ec0\u4e48\u610f\u601d\u554a`

Host RTF is not a target-device performance result. Official checksum metadata
was not available; the SHA256 above is the locally computed archive hash.
