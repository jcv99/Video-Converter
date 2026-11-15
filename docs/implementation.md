# Android conversion tests

Use an Android 10 (API 29) device or emulator for the minimum-version check and a recent ARM64 device for regression testing. Use the debug signing key of any installed
copy of Video Converter. Run from the project directory with FFmpeg, FFprobe,
Python 3, and ADB available.

```bash
set -euo pipefail
run=matrix-$(date +%Y%m%d-%H%M%S)
local_run="/tmp/video-converter-$run"
phone_run="/sdcard/Download/VideoConverterTest/$run"
python3 scripts/conversion-matrix.py generate "$local_run"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell mkdir -p "$phone_run"
adb push "$local_run/inputs" "$local_run/manifest.json" "$phone_run/"
adb shell "cd $phone_run && sha256sum manifest.json inputs/*" > "$local_run/phone.sha256"
(
    cd "$local_run"
    sha256sum -c phone.sha256
)
adb shell run-as org.videoconverter.app mkdir -p files/conversion-matrix
adb shell run-as org.videoconverter.app rm -rf files/conversion-matrix/inputs
fixture_uid=$(adb shell run-as org.videoconverter.app id -u)
fixture_gid=$(adb shell run-as org.videoconverter.app id -g)
tar --owner="$fixture_uid" --group="$fixture_gid" --numeric-owner -C "$local_run" -cf - inputs midstream.aac |
    adb shell -T 'run-as org.videoconverter.app tar -xf - -C files/conversion-matrix'
adb shell "echo $run | run-as org.videoconverter.app tee files/conversion-matrix/run-id"
adb shell am instrument -w org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
adb shell am instrument -w -e recoverVideo true org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
adb shell am instrument -w -e recovery true org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
adb shell am instrument -w -e midstream true org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
adb shell am instrument -w -e trimSilence true org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
adb pull "$phone_run" "$local_run/exports"
python3 scripts/conversion-matrix.py verify "$local_run/manifest.json" "$local_run/exports" |
    tee "$local_run/report.txt"
```

Keep all test inputs and exports under `Download/VideoConverterTest/<run-id>/`.
For UI conversions, use **Choose folder** to select a dedicated output folder
inside that run. After recording the verification result, delete the converted
audio from the phone and host. Keep the inputs, manifest, and report.

```bash
for output in MP3 AAC ALAC OPUS VORBIS FLAC WAV checks ui-exports; do
    adb shell "rm -rf '$phone_run/$output'"
    rm -rf -- "$local_run/exports/$output"
done
```

If the host FFmpeg lacks the VisualOn AMR-WB encoder, pass
`--amrwb-source /path/to/1.2-second-tone.amr` to remux a short AMR-WB tone into
each compatible fixture container. The app uses its bundled decoder for these tests.

The instrumentation run reports saved and failed cases from the production
service. It also checks cancellation, retry, filename collision, and silent-video
rejection. The host verifier checks every generated input against every target,
including the first-track tone, and lists cases blocked by fixture generation.

The recovery run loads a legacy MP4 batch. It checks that the saved MP4
remains available and the unfinished export cannot be retried.

The export recovery run covers the durable output states. It checks that an
allocated, a partial, a complete, a ready, and a published output are all
reconciled without deleting a finished export, that a stalled provider pipe
stops when the work is cancelled, and that a selection larger than the binder
limit survives a failed journal write and enrolls exactly once.

The midstream run decodes a fixture whose sample rate and channel layout change
in the middle of the file. It checks the decoded duration and the tone in both
segments with trim and loudness enabled and disabled.

The silence trim run checks the trimmed length, the fade-out, and the retry that
keeps the submitted settings.

For the UI smoke check, open **Pick input folder**, select the run's `inputs`
folder, and wait for the supported-file count. Choose **FLAC** under **Output
format** and check that the conversion button shows **Convert to FLAC**.

Also pick a video through **Pick files** and share a video into the app from
Files. Convert each to MP3 and tap **Open** on a saved result.

## Folder, preset, and loudness checks

Run the workflow checks against the installed debug and instrumentation APKs.
They exercise parallel folder exports to Downloads, flat exports, filename
collisions, retry settings, preset storage, and all seven encoders with
normalization enabled.

```bash
adb shell am instrument -w -e workflow true org.videoconverter.app.test/org.videoconverter.app.ConversionMatrixTest
loudness_run=$(mktemp -d)
adb exec-out run-as org.videoconverter.app tar -cf - -C files/workflow-verification . |
    tar -xf - -C "$loudness_run"
for output in target-14.wav target-16.wav target-23.wav trimmed.wav peak.wav tail-peak.wav; do
    ffmpeg -hide_banner -i "$loudness_run/$output" \
        -af ebur128=peak=sample+true:framelog=verbose -f null -
done
```

Expected integrated levels for the target fixtures are within 1 LU of their
selected levels. The trimmed stereo fixture should measure within 1 LU of
−16 LUFS. The larger of sample and true peak should be at most −1 dBFS for
the peak fixtures. The short clip and silence fixtures should match their
unnormalized exports.

For a chosen output folder, import a tree with two sibling folders that each
contain a file named `same.wav`. Choose a separate export tree and verify the
two paths and the flat output when folder preservation is off. Place a file
where an output subfolder would be created and check that the affected input
reports an error. Save a preset with that destination, relaunch the app, and
verify apply, update, rename, and delete. Move the saved folder out of the way
and confirm the preset requires choosing a new destination.
