# Third-party notices

## CommonMark Java

`android/libs/commonmark-0.22.0.jar` is from [commonmark-java](https://github.com/commonmark/commonmark-java), distributed under the BSD 2-Clause license. The full license is included at `android/assets/licenses/commonmark-LICENSE.txt` and packaged in the APK. Its download URL and checksum are in `dependencies.lock.json`.

## OpenAI artwork and names

The OpenAI blossom launcher artwork in `android/res/drawable-nodpi/icon.png` was extracted from the locally installed ChatGPT application's `icon-chatgpt.icns`. The artwork and OpenAI product names belong to their respective rights holders; this repository does not grant rights to those marks or artwork. Their use does not indicate sponsorship or endorsement. See [OpenAI's brand guidelines](https://openai.com/brand/).

## Whisper speech recognition

The local speech engine uses [whisper.cpp v1.9.5](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5), including its ggml dependency, under the MIT license. The license is packaged at `android/assets/licenses/whisper-cpp-LICENSE.txt`.

Whisper tiny.en model weights are from OpenAI Whisper, converted and quantized in [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp). The OpenAI MIT license is packaged at `android/assets/licenses/whisper-model-LICENSE.txt`. Download URLs and exact checksums are in `dependencies.lock.json`.

This repository contains an independent Android client and companion. It does not contain the Codex desktop app, Codex executable, OpenAI credentials, or model weights. The speech build downloads verified weights and packages them in the generated APK. Codex must be installed and authenticated separately for remote coding tasks.
