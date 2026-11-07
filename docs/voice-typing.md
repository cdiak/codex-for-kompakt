# Local voice typing

The 0.3 build includes Whisper tiny.en Q5_1, running entirely on the Kompakt's CPU through whisper.cpp. It supports **English** and recordings up to **30 seconds**. It works without the Mac connection; submitting a Codex task still needs that connection.

## Dictate a Codex task

Tap the microphone beside the send arrow. Allow microphone access when Android asks. Speak, tap **Stop recording**, wait for the transcript, then tap **Use text**. The transcript is inserted into the composer so you can correct it before sending. Nothing is submitted automatically.

The recorder keeps the screen awake while recording or transcribing. Leaving its screen or choosing Cancel stops recording and discards pending results. Audio remains in memory and is discarded after processing. There are no audio uploads, saved recordings, or transcript logs. The shared model unloads after 60 seconds without another transcription.

## Dictate in Mudita Messages

The same APK includes **SMS microphone**, an optional Android accessibility service that places a single **OpenAI blossom button** just above the SMS draft field. It keeps the stock Mudita keyboard selected. There is no replacement keyboard or extra keyboard panel. It does not modify the Messages APK or request SMS/contact permissions.

1. In Codex's chat drawer, choose **SMS microphone**.
2. Open Accessibility settings, find **SMS microphone** under installed/downloaded apps, and review Android's permission warning before enabling the service itself. Leave its separate **shortcut** disabled: that shortcut is a movable blossom that toggles the service, not the dictation microphone.
3. Open an SMS draft and tap its message field. The small blossom appears just above the composer.
4. Tap the blossom to record. Allow microphone access if requested. Tap **Stop recording**, review the transcript, then **Use text**.
5. The recorder returns to the original SMS draft and inserts the text at the saved selection. Use the normal keyboard for corrections, then send the message yourself.

Accessibility is a powerful Android permission: the platform grants screen-reading and control capability. The service's code restricts retrieval and changes to the focused `com.mudita.messages:id/compose_message_text` field. It does not read message history, recipients, or keystrokes, and it never sends messages. It remembers the original draft and selection only in memory while dictation is active. Before insertion it verifies the same accessibility node/window and unchanged draft text; otherwise it refuses insertion. Leaving the recorder cancels the operation. Disable **SMS microphone** in Accessibility settings to remove the button.

The integration is specific to the Mudita Messages version tested on MuditaOS K 1.1.1. Other apps and future Messages layouts may need separate adapters. Stock Mudita keyboards do not currently expose a speech-input hook; see [Mudita's speech-to-text support note](https://support.mudita.com/en/support/solutions/articles/77000577364-does-kompakt-support-speech-to-text-).

The working microphone sits just above the composer. In the installed SMS app's `kompakt_compose_message_view` layout, the text field is constrained directly to the 54 dp send-button container. Moving an overlay into that row would cover part of the text field. A true inline microphone needs reserved layout space and integration in a custom SMS build (or a supported Mudita extension), rather than overlay positioning alone.

## Measured on this Kompakt

Four Cortex-A53 cores, API 31, charging over USB, initial battery temperature 28.2 °C. Four inference threads; CPU only. These are short spot checks, not battery or sustained-performance claims.

| Model | Audio duration | Transcription wall time | Peak RSS |
| --- | ---: | ---: | ---: |
| tiny.en F16 | 4.55 s | 6.01 s | 145.4 MiB |
| tiny.en F16 | 11.00 s | 7.10 s | 146.0 MiB |
| tiny.en Q5_1 | 4.55 s | 5.71 s | 100.2 MiB |
| tiny.en Q5_1 | 11.00 s | 5.74 s | 100.7 MiB |

The app's JNI engine also transcribed the 4.55-second coding sample in **4.89 seconds**, including model preparation for that run. The quantized model is 32,166,155 bytes. See [`whisper-benchmark.json`](whisper-benchmark.json) for recorded measurements.

Both versions understood the coding sentence but rendered the proper noun “Kompakt” as “Compact.” Expect corrections for names, punctuation, code identifiers, and noisy recordings. Silence detection includes an energy gate; always review the result before inserting or sending it. This is record-and-transcribe dictation, not live streaming.

## Build dependencies

Use CMake 3.16+ and Android NDK r30, alongside the Java/Android build tools described in the main README. NDK downloads and checksums are in `toolchain.lock.json`.

```sh
python3 tools/fetch_voice.py
python3 build.py \
  --toolchain work/toolchain \
  --work work/android-build \
  --output dist/Codex.apk \
  --ndk work/toolchain/ndk/android-ndk-r30 \
  --whisper-source work/voice/whisper.cpp-1.9.5 \
  --model work/voice/ggml-tiny.en-q5_1.bin
```

If CMake is not on PATH, add `--cmake /path/to/cmake`. The build targets ARM64 without dot-product or FP16-vector instructions, which this Kompakt's Cortex-A53 cores do not support. Do not use a build requiring ARMv8.2 FP16.

The source repository excludes model weights and native binaries. The build packages the verified model and compiled native library in the APK. On first use, the app copies the model into private storage, so storage includes both copies. Native and model license notices are included in the APK.

## Native smoke checks

After building and installing the app, create a synthetic test sentence on macOS and run the separate instrumentation package:

```sh
say -v Samantha -o work/coding-task.wav --data-format=LEI16@16000 --channels=1 \
  'Create a Python script that prints hello from Kompakt, then run the tests.'
python3 tools/test_voice.py \
  --toolchain work/toolchain --app-build work/android-build \
  --work work/voice-tests --sample work/coding-task.wav \
  --adb work/toolchain/android/platform-tools/adb
```

These checks transcribe the supplied sample, verify that silence produces no text, cancel an active decode, and confirm that the engine works afterward. They do not open the microphone. Android instrumentation restarts the app process, so finish any phone UI work first. The temporary test package is removed afterward.
