#!/usr/bin/env python3
"""Generate the Android input matrix or verify a pulled set of app exports."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import tempfile
from io import BytesIO
import json
from pathlib import Path
import re
import struct
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[1]
TONE = "sine=frequency=440:sample_rate={}:duration=1.2"
# Demuxer aliases with different muxing rules get their own fixtures.
CONTAINERS = {
    "matroska": [("matroska", "mkv", "ffv1"), ("matroska", "mka", None),
                 ("webm", "webm", "libvpx-vp9")],
    "ogg": [("ogg", "ogg", "libtheora"), ("opus", "opus", None)],
    "mov": [("mp4", "mp4", "libx264"), ("mov", "mov", "libx264"),
            ("ipod", "m4a", None), ("3gp", "3gp", "h263")],
    "wav": [("wav", "wav", None)],
    "flac": [("flac", "flac", None)],
    "mp3": [("mp3", "mp3", None)],
    "aac": [("adts", "aac", None)],
    "avi": [("avi", "avi", "mpeg4")],
    "asf": [("asf", "asf", "wmv2")],
    "amr": [("amr", "amr", None)],
    "mpegts": [("mpegts", "ts", "mpeg2video")],
    "dts": [("dts", "dts", None)],
    "mpegvideo": [],  # Used to identify program-stream video. No audio tracks.
    "mpegps": [("mpeg", "mpg", "mpeg1video"), ("vob", "vob", "mpeg2video")],
}
ENCODERS = {
    "aac_latm": "aac", "opus": "libopus", "vorbis": "libvorbis",
    "mp3": "libmp3lame", "dca": "dca", "amrnb": "libopencore_amrnb",
    "amrwb": "libvo_amrwbenc",
}
PROBE_NAMES = {"amrnb": "amr_nb", "amrwb": "amr_wb", "dca": "dts"}
# These pairings are permitted by the container, but lack a host encoder.
MISSING_ENCODER_CONTAINERS = {
    "amrwb": {"amr", "mov", "3gp", "matroska", "avi", "asf", "wav"},
    "wmalossless": {"asf"},
    "wmapro": {"asf"},
}
TARGETS = {
    "MP3": ("mp3", "mp3", "mp3"),
    "AAC": ("m4a", "aac", "mov"),
    "ALAC": ("m4a", "alac", "mov"),
    "OPUS": ("ogg", "opus", "ogg"),
    "VORBIS": ("ogg", "vorbis", "ogg"),
    "FLAC": ("flac", "flac", "flac"),
    "WAV": ("wav", "pcm_s16le", "wav"),
}


def run(*args):
    return subprocess.run(list(map(str, args)), capture_output=True, check=True).stdout


def ffmpeg(*args):
    return run("ffmpeg", "-nostdin", "-v", "error", "-y", "-filter_threads", "1", *args)


def probe(path):
    return json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path))


def decode(path, *output):
    return ffmpeg("-xerror", "-i", path, "-map", "0:a:0", *output)


def error_text(error):
    if isinstance(error, subprocess.CalledProcessError):
        return re.sub(r" @ 0x[0-9a-f]+", "", error.stderr.decode(errors="replace").strip())
    return str(error)


def input_pair(destination, muxer, extension, video, codec, amrwb_source):
    kind = "video" if video else "audio"
    name = f"{kind}-{extension}-{codec}.{extension}"
    path = destination / name
    result = {"name": name, "kind": kind, "muxer": muxer, "codec": codec}
    if codec == "aac_latm" and muxer != "mpegts":
        return result | {"status": "incompatible", "detail": "LATM in MPEG-TS only"}
    rate = {"amrnb": 8000, "amrwb": 16000, "flac": 48000}.get(codec, 48000)
    args = ["-f", "lavfi", "-i", TONE.format(rate), "-map", "0:a:0", "-ac", "1",
            "-c:a", ENCODERS.get(codec, codec), "-strict", "experimental", "-threads:a", "1"]
    if codec == "aac_latm":
        args += ["-mpegts_flags", "+latm"]
    if video:
        args = ["-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1.2",
                *args[:4], "-map", "0:v:0", "-c:v", video, "-threads:v", "1",
                "-map", "1:a:0", *args[6:]]
    if codec == "amrwb" and amrwb_source:
        args = ["-i", amrwb_source, "-map", "0:a:0", "-c:a", "copy"]
        if video:
            args = ["-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1.2",
                    "-i", amrwb_source, "-map", "0:v:0", "-c:v", video,
                    "-threads:v", "1", "-map", "1:a:0", "-c:a", "copy"]
    try:
        if muxer == "wav" and codec == "aac":
            # A WAV AAC stream needs ADTS framing and its WAVE format tag.
            with tempfile.TemporaryDirectory() as temporary:
                adts = Path(temporary) / "audio.aac"
                ffmpeg(*args, "-f", "adts", adts)
                ffmpeg("-i", adts, "-c:a", "copy", "-tag:a", "0x1610", "-f", muxer, path)
        else:
            ffmpeg(*args, "-f", muxer, path)
    except subprocess.CalledProcessError as error:
        path.unlink(missing_ok=True)
        detail = error_text(error)
        missing = "Unknown encoder" in detail or "Encoder not found" in detail
        status = "blocked" if missing and muxer in MISSING_ENCODER_CONTAINERS.get(codec, ()) else "incompatible"
        return result | {"status": status, "detail": detail}
    try:
        streams = probe(path)["streams"]
        audio = [stream["codec_name"] for stream in streams if stream["codec_type"] == "audio"]
        if not audio or audio[0] != PROBE_NAMES.get(codec, codec):
            path.unlink()
            return result | {"status": "incompatible", "detail": f"Muxed first audio identifies as {audio}"}
        if video and not any(stream["codec_type"] == "video" for stream in streams):
            raise ValueError("Missing video track")
        decoded = decode(path, "-ar", "8000", "-ac", "1", "-f", "s16le", "-")
        if len(decoded) < 8000 * 2 * 0.95:
            raise ValueError(f"Host decoder produced only {len(decoded) / 16000:.3f}s of audio")
    except (subprocess.CalledProcessError, ValueError) as error:
        path.unlink(missing_ok=True)
        return result | {"status": "blocked", "detail": error_text(error)}
    return result | {"status": "ready", "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def midstream_fixture(directory):
    """Build an ADTS stream whose rate and channel layout change mid-file."""
    first = directory / "midstream-first.aac"
    second = directory / "midstream-second.aac"
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.2",
           "-ac", "1", "-c:a", "aac", "-f", "adts", first)
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=0.9",
           "-ac", "2", "-c:a", "aac", "-f", "adts", second)
    (directory / "midstream.aac").write_bytes(first.read_bytes() + second.read_bytes())
    first.unlink()
    second.unlink()


def generate(directory, amrwb_source):
    if amrwb_source and not any(stream.get("codec_name") == "amr_wb" for stream in probe(amrwb_source)["streams"]):
        raise ValueError("--amrwb-source must have an AMR-WB audio track")
    directory.mkdir(parents=True, exist_ok=False)
    inputs = directory / "inputs"
    inputs.mkdir()
    midstream_fixture(directory)
    config = (ROOT / "scripts/build-native.sh").read_text()
    demuxers = re.search(r"--enable-demuxer=([\w,]+)", config)[1].split(",")
    codecs = re.search(r"--enable-decoder=([\w,]+)", config)[1].split(",")
    jobs = [(muxer, extension, video, codec, amrwb_source) for demuxer in demuxers
            for muxer, extension, video_codec in CONTAINERS[demuxer]
            for video in ((None, video_codec) if video_codec else (None,)) for codec in codecs]
    with ThreadPoolExecutor(max_workers=6) as executor:
        rows = list(executor.map(lambda job: input_pair(inputs, *job), jobs))
    video = ["-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1.2"]
    silent = inputs / "video-no-audio.mp4"
    dual = inputs / "video-two-tracks.mp4"
    ffmpeg(*video, "-an", "-c:v", "libx264", silent)
    ffmpeg(*video, "-f", "lavfi", "-i", TONE.format(48000), "-f", "lavfi", "-i",
           TONE.format(48000).replace("440", "880"), "-map", "0:v", "-map", "1:a",
           "-map", "2:a", "-c:v", "libx264", "-c:a", "aac", dual)
    if len([s for s in probe(dual)["streams"] if s["codec_type"] == "audio"]) != 2:
        raise ValueError("First-track fixture needs two audio tracks")
    if any(s["codec_type"] == "audio" for s in probe(silent)["streams"]):
        raise ValueError("Silent fixture contains audio")
    decode(dual, "-f", "null", "-")
    for path, status in ((silent, "negative"), (dual, "ready")):
        rows.append({"name": path.name, "kind": "video", "status": status,
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (directory / "manifest.json").write_text(json.dumps(rows, indent=2) + "\n")
    for status in ("ready", "negative", "incompatible", "blocked"):
        print(f"{status}: {sum(row['status'] == status for row in rows)}")


def verify_output(job):
    target, path, codec, container = job
    try:
        info = probe(path)
        stream, = info["streams"]
        if stream["codec_type"] != "audio" or stream["codec_name"] != codec:
            raise ValueError(f"Expected {codec}, found {stream.get('codec_name')}")
        detected = info["format"]["format_name"]
        if container not in detected.split(","):
            raise ValueError(f"Expected container {container}, found {detected}")
        wav = decode(path, "-ar", "8000", "-ac", "1", "-f", "wav", "-")
        with wave.open(BytesIO(wav)) as sound:
            pcm = sound.readframes(sound.getnframes())
        duration = len(pcm) / 16000
        if not 0.95 <= duration <= 1.5:
            raise ValueError(f"Duration {duration:.3f}s outside fixture limits")
        if path.name.startswith("video-two-tracks."):
            samples = [item[0] for item in struct.iter_unpack("<h", pcm)]
            crossings = sum(a < 0 <= b for a, b in zip(samples[1600:6400], samples[1601:6401]))
            if not 250 <= crossings <= 280:
                raise ValueError(f"First track tone: {crossings} crossings in 0.6s")
    except (subprocess.CalledProcessError, ValueError, EOFError, wave.Error) as error:
        return f"{target}/{path.name}: {error_text(error)}"
    return None


def verify(manifest, exports):
    rows = json.loads(manifest.read_text())
    expected = [row["name"] for row in rows if row["status"] == "ready"]
    failures = []
    passed = 0
    jobs = []
    for target, (extension, codec, container) in TARGETS.items():
        folder = exports / target
        wanted = {f"{Path(name).stem}.{extension}" for name in expected}
        actual = {path.name for path in folder.iterdir()} if folder.is_dir() else set()
        failures.extend(f"{target}/{name}: unexpected export" for name in sorted(actual - wanted))
        failures.extend(f"{target}/{name}: missing export" for name in sorted(wanted - actual))
        jobs.extend((target, folder / name, codec, container) for name in sorted(actual & wanted))
    with ThreadPoolExecutor(max_workers=8) as executor:
        for error in executor.map(verify_output, jobs):
            if error:
                failures.append(error)
            else:
                passed += 1
    blocked = [row["name"] for row in rows if row["status"] == "blocked"]
    for name in blocked:
        print(f"BLOCKED all targets: {name}")
    for failure in failures:
        print(f"FAIL {failure}")
    print(f"Expected {len(expected) * len(TARGETS)} audio conversions, "
          f"passed {passed}, failures {len(failures)}, blocked {len(blocked) * len(TARGETS)}")
    return bool(failures)


parser = argparse.ArgumentParser(description=__doc__)
actions = parser.add_subparsers(dest="action", required=True)
generate_action = actions.add_parser("generate")
generate_action.add_argument("directory", type=Path)
generate_action.add_argument("--amrwb-source", type=Path, help="Optional 1.2-second raw AMR-WB fixture to remux")
check = actions.add_parser("verify")
check.add_argument("manifest", type=Path)
check.add_argument("exports", type=Path)
options = parser.parse_args()
if options.action == "generate":
    generate(options.directory, options.amrwb_source)
else:
    raise SystemExit(verify(options.manifest, options.exports))
