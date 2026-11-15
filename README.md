# Video Converter

Offline batch audio extraction and conversion for **Android 11 and newer (API 30+)**. No network, no account, no telemetry: every file is decoded and re-encoded on device by a bundled FFmpeg build.

Pick files with **Pick files**, share them into the app from any Android share menu, or use **Pick input folder** to scan a folder tree. Review the selection, remove what you do not want, and tap **Convert to…**.

## Features

- **Batch conversion** — decode the first audio track of each input and write the result to the destination folder.
- **Input formats** — WebM, Ogg Vorbis, Ogg Opus, MP4, MKV, MOV, WAV, FLAC and M4A.
- **Output formats** — MP3 (128 / 192 / 320 kbps), AAC or ALAC in M4A, Opus or Vorbis in Ogg, FLAC, and 16-bit PCM WAV.
- **Folder import** — scan a folder and its subfolders; the subfolder layout is mirrored in the output unless **Preserve folder structure** is turned off.
- **Loudness normalisation** — measure the audio *after* any silence trim and apply a target of −14, −16 or −23 LUFS, with gain limited so the pre-encode true peak stays at or below −1 dBTP.
- **Silence trimming** — remove leading and trailing silence with a fade-out on the trimmed tail.
- **Presets** — save the format, quality, loudness, trimming, folder-structure and destination settings for reuse in later batches.
- **Foreground service** — conversions run in a foreground service, so progress and cancellation stay available from the notification drawer.
- **Durable output journal** — every export is reconciled on recovery, so an interrupted batch never deletes a finished file.
- **Retry** — failed items can be retried with the settings they were submitted with.

The default destination is **Downloads/VideoConverter**. Use **Choose folder** to pick a different one and **Remember this folder** to keep it for future batches.

## Requirements

| Component | Version |
| --- | --- |
| JDK | 17 or 21 |
| Android SDK Platform | 37 (compile platform) |
| Android Build Tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| Gradle | 9.6.0 (via the wrapper) |
| Host tools | Bash, GNU Make, binutils, curl, tar, xz, pkg-config, Python 3 |

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK. The app runs on Android 11 (API 30) and newer.

## Build

```bash
rtk run ./gradlew :app:assembleDebug
```

The debug-signed APK lands in `app/build/outputs/apk/debug/app-debug.apk` and contains the `arm64-v8a` and `x86_64` native libraries.

The first build fetches the checksum-pinned native sources (FFmpeg 8.1.3 and LAME 3.100) and compiles them. To build without network access, place the five source archives named by the native build script in `app/build/native/` first; the exact upstream URLs and SHA-256 hashes are in `scripts/build-native.sh`.

## Test

Unit tests:

```bash
rtk run ./gradlew :app:testDebugUnitTest
```

End-to-end conversion tests run on a connected device. Generate a short FFmpeg fixture corpus with:

```bash
rtk run python3 scripts/conversion-matrix.py generate /tmp/video-converter-matrix
```

The generated manifest lists compatible files, unsupported cases and input checksums. Verify the pulled exports with:

```bash
rtk run python3 scripts/conversion-matrix.py verify MANIFEST EXPORT_DIRECTORY
```

The full ADB procedure — instrumented runs, recovery checks, midstream and silence-trim scenarios, and the UI smoke test — is documented in [docs/implementation.md](docs/implementation.md).

## Project layout

```
app/                       Android application module (Kotlin, Jetpack Compose)
  src/main/java/           UI, batch orchestration, foreground service, presets, storage
  src/main/assets/         Licence texts for the bundled native libraries
  src/test/                JVM unit tests
  src/androidTest/         Instrumented conversion matrix tests
native/converter.c         JNI bridge to FFmpeg and LAME (decode, loudness, encode)
scripts/build-native.sh    Downloads, verifies and builds the native libraries
scripts/conversion-matrix.py  Fixture generation and verification
docs/implementation.md     Device test procedure
```

## Licences

The project code is licensed under **GPL-3.0-or-later** — see [LICENSE](LICENSE).

The app ships shared libraries built from **FFmpeg 8.1.3** and **LAME 3.100**, licensed under **LGPL-2.1-or-later** — see [docs/LGPL-2.1.txt](docs/LGPL-2.1.txt). Their complete source archives and build settings are identified by `scripts/build-native.sh`, which also documents the installed library name changes required for Android. You may rebuild and install the app with a compatible replacement library using that same script.

AndroidX and Compose dependencies are used under Apache 2.0. Third-party licence texts are bundled in `app/src/main/assets/`.
