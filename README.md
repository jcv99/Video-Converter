# Video Converter

Extract audio from videos and convert audio files in batches on Android 11 or newer. Video Converter uses bundled FFmpeg and LAME libraries to convert files entirely on your device. It works offline and requires no account or telemetry.

## Convert files

1. Tap **Pick files**, send files to Video Converter through Android's **Share** menu, or tap **Pick input folder** to scan a folder and its subfolders.
2. Select an **Output format**. For MP3, choose 128, 192, or 320 kbps.
3. Leave the destination at **Downloads/VideoConverter** or tap **Choose folder**. Folder imports preserve the subfolder layout by default. Turn off **Preserve folder structure** to export everything into one folder.
4. Tap **Convert to …**. Follow progress in the app or in the foreground service notification. Tap **Open** next to a saved file to play it in another app.

## Formats

Input examples include MP4, MKV, MOV, WebM, WAV, FLAC, M4A, and Ogg. Each input needs a decodable audio track. When a file contains multiple audio tracks, the app uses the first.

| Export format | File extension |
| --- | --- |
| MP3 | `.mp3` |
| AAC or ALAC | `.m4a` |
| Opus or Vorbis | `.ogg` |
| FLAC | `.flac` |
| 16-bit PCM WAV | `.wav` |

## Batch settings

- **Trim silence** removes leading and trailing silence and fades out the trimmed tail.
- **Normalize loudness** targets −14, −16, or −23 LUFS after silence trimming. Gain is limited to keep the pre-encode true peak at or below −1 dBTP.
- **Presets** save the format, MP3 quality, trimming, loudness, folder layout, and destination. Use **Remember this folder** to keep a custom destination available for later batches.
- **Options → Parallel files** sets the maximum number of simultaneous conversions for the next batch, from 1 to 50. The default is 20.
- **Retry unfinished files** reuses the submitted conversion settings. The app uses an output journal to preserve completed exports when recovering an interrupted batch.

## Build and install

The native build script expects an **x86-64 Linux** host. Install the following before building:

- A JDK to launch the Gradle wrapper. The project selects **Java 25** for the Gradle daemon and targets Java 17 bytecode.
- Android SDK Platform **37**, Build Tools **36.0.0**, and NDK **28.2.13676358**. Set `JAVA_HOME` to your launcher JDK and `ANDROID_HOME` to the SDK directory.
- Bash, GNU Make, binutils, curl, tar, xz, pkg-config, and Python 3. Install ADB to deploy to a device or emulator.

From the repository root, build a debug APK with the Gradle 9.6.0 wrapper:

```bash
./gradlew :app:assembleDebug
```

The APK at `app/build/outputs/apk/debug/app-debug.apk` includes native libraries for `arm64-v8a` and `x86_64`. Install it on a connected Android 11+ device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build runs `:app:buildNative` automatically. It downloads and verifies the pinned FFmpeg, LAME, libogg, libvorbis, and Opus archives before compiling them. To skip those downloads, place the five archives in `app/build/native/` using the filenames and SHA-256 hashes in [the native build script](scripts/build-native.sh).

## Releases

Push a `v*` tag and publish a GitHub release. The release workflow builds the signed release APK and attaches it to the release automatically.

```bash
rtk run git tag -a v0.2.0 -m "Video Converter 0.2.0"
rtk run git push origin v0.2.0
rtk run gh release create v0.2.0 --generate-notes --title "Video Converter 0.2.0"
```

The tag carries the version: `v0.2.0` builds version `0.2.0` with version code `200`. See [the release guide](docs/releases.md) for the signing secrets, the version code rules, and how to retry a failed build.

## Tests

Run JVM tests and Android lint:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The [device test guide](docs/implementation.md) covers generating fixtures with FFmpeg, installing the instrumentation APK, running the conversion matrix and recovery checks, and verifying exported files with FFprobe.

## Source and licences

- [Android app](app/src/main/java/org/videoconverter/app) contains the Kotlin and Jetpack Compose UI, foreground service, presets, and storage code.
- [Native bridge](native/converter.c) handles decoding, silence trimming, loudness normalization, and encoding.
- [Conversion matrix script](scripts/conversion-matrix.py) generates fixtures and verifies exports.

The app is licensed under [GPL-3.0-or-later](LICENSE). It dynamically links FFmpeg 8.1.3 and LAME 3.100 under [LGPL-2.1-or-later](docs/LGPL-2.1.txt). FFmpeg includes libogg, libvorbis, and Opus under BSD-style licences. The APK bundles [third-party notices and licence texts](app/src/main/assets). The [native build script](scripts/build-native.sh) records upstream sources and build flags and can rebuild compatible shared libraries for the APK.
