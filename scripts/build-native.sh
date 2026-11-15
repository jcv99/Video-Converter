#!/usr/bin/env bash
set -euo pipefail
ndk=$(realpath "$1")
mkdir -p "$2"
out=$(realpath "$2")
api="$3"
bridge="$(dirname "$0")/../native/converter.c"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
jobs=${VIDEO_CONVERTER_JOBS:-8}

fetch() {
    local url=$1 digest=$2 destination=$3
    if [[ -f $destination ]] &&
        printf '%s  %s\n' "$digest" "$destination" | sha256sum --check --status; then
        return
    fi
    local partial="$destination.part"
    if curl --fail --location --retry 3 "$url" -o "$partial" &&
        printf '%s  %s\n' "$digest" "$partial" | sha256sum --check; then
        mv "$partial" "$destination"
    else
        rm -f "$partial"
        return 1
    fi
}

fetch https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz \
    7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3 "$out/ffmpeg.tar.xz"
fetch https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz \
    ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e "$out/lame.tar.gz"

fetch https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.xz \
    5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061 "$out/libogg-1.3.6.tar.xz"
fetch https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz \
    b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b "$out/libvorbis-1.3.7.tar.xz"
fetch https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz \
    65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1 "$out/opus-1.5.2.tar.gz"
for abi in arm64-v8a x86_64; do
    case "$abi" in
        arm64-v8a) arch=aarch64; triple=aarch64-linux-android ;;
        x86_64) arch=x86_64; triple=x86_64-linux-android ;;
    esac
    work="$out/$abi"
    build_key=$(
        {
            printf '%s\n' "$api" "$abi"
            sha256sum < "$ndk/source.properties"
            sha256sum < "$0"
            sha256sum < "$bridge"
        } | sha256sum | cut -d ' ' -f1
    )
    if [[ $(cat "$work/build-key" 2>/dev/null) != "$build_key" ]]; then
        rm -rf -- "$work" "$out/jniLibs/$abi"
    fi
    mkdir -p "$work" "$out/jniLibs/$abi"
    cc="$toolchain/${triple}${api}-clang"
    cxx="$toolchain/${triple}${api}-clang++"
    prefix="$work/install"

    if [[ ! -f "$prefix/lib/libmp3lame.so" ]]; then
        tar -xf "$out/lame.tar.gz" -C "$work"
        (
            cd "$work/lame-3.100"
            CC="$cc" CXX="$cxx" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
                CFLAGS="-O2 -Wno-error=implicit-function-declaration" LDFLAGS="-Wl,--undefined-version" \
                ./configure --host="$triple" --prefix="$prefix" --enable-shared \
                --disable-static --disable-decoder --disable-frontend --disable-gtktest --disable-nasm
            make -C libmp3lame -j"$jobs"
            make -C libmp3lame install
            make -C include install
        )
    fi

    # Build Xiph libraries as PIC archives linked into the FFmpeg shared libraries.
    if [[ ! -f "$prefix/lib/libavformat.so" ]]; then
        for library in libogg-1.3.6 libvorbis-1.3.7 opus-1.5.2; do
            case "$library" in opus-*) extension=gz ;; *) extension=xz ;; esac
            tar -xf "$out/$library.tar.$extension" -C "$work"
            (
                cd "$work/$library"
                PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" CC="$cc" CXX="$cxx" \
                    AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" CFLAGS="-O2 -fPIC" \
                    CPPFLAGS="-I$prefix/include" LDFLAGS="-L$prefix/lib" \
                    ./configure --host="$triple" --prefix="$prefix" --disable-shared --enable-static
                make -j"$jobs"
                make install
            )
        done
        rm -rf "$work/ffmpeg-build"
        tar -xf "$out/ffmpeg.tar.xz" -C "$work"
        mkdir -p "$work/ffmpeg-build"
        (
            cd "$work/ffmpeg-build"
            PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" "$work/ffmpeg-8.1.3/configure" --target-os=android --arch="$arch" \
                --cc="$cc" --cxx="$cxx" --ar="$toolchain/llvm-ar" --ranlib="$toolchain/llvm-ranlib" \
                --nm="$toolchain/llvm-nm" --strip="$toolchain/llvm-strip" --prefix="$prefix" \
                --enable-cross-compile --enable-shared --disable-static --disable-programs \
                --disable-doc --disable-debug --disable-network --disable-autodetect --disable-x86asm \
                --disable-everything --enable-avcodec --enable-avformat --enable-swresample \
                --enable-avfilter --enable-filter=ebur128,aresample --disable-avdevice --disable-swscale \
                --pkg-config-flags=--static --enable-libopus --enable-libvorbis \
                --enable-encoder=aac,alac,libopus,libvorbis,flac,pcm_s16le \
                --enable-muxer=ipod,ogg,flac,wav \
                --enable-protocol=file \
                --enable-demuxer=matroska,ogg,mov,wav,flac,mp3,aac,avi,asf,amr,mpegts,mpegps,dts,mpegvideo \
                --enable-parser=aac,aac_latm,amr,flac,mpegaudio,mpegvideo,opus,vorbis,ac3,dca \
                --enable-decoder=aac,aac_latm,opus,vorbis,flac,alac,mp3,mp2,ac3,eac3,dca,amrnb,amrwb,wmalossless,wmapro,wmav1,wmav2,adpcm_ima_wav,adpcm_ms,pcm_alaw,pcm_mulaw,pcm_s16le,pcm_s24le,pcm_s32le,pcm_u8,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_f32be,pcm_f64be
            make -j"$jobs"
            make install
        )
    fi

    libs="$out/jniLibs/$abi"
    # Android accepts only names ending in .so. Rewrite SONAMEs and dependency names
    # together after linking, using equal-length padded replacements in the ELF strings.
    "$cc" -shared -fPIC -O2 -Wall -Wextra -Werror -I"$prefix/include" \
        -L"$prefix/lib" "$bridge" \
        -lavfilter -lavformat -lavcodec -lswresample -lavutil -lmp3lame -lm -llog -o "$work/libconverter.so"
    python3 - "$prefix" "$work" "$libs" <<'PY'
import pathlib
import re
import subprocess
import sys

prefix, work, output = map(pathlib.Path, sys.argv[1:])
libraries = ["avfilter", "avformat", "avcodec", "swresample", "avutil", "mp3lame"]
files = [prefix / "lib" / f"lib{name}.so" for name in libraries] + [work / "libconverter.so"]
mapping = {}
for path in files:
    dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    soname = re.search(r"\(SONAME\).*\[([^\]]+)\]", dynamic)
    if soname and soname[1] != path.name:
        mapping[soname[1].encode()] = path.name.encode()
for path in files:
    data = path.read_bytes()
    for old, new in mapping.items():
        data = data.replace(old + b"\0", new.ljust(len(old), b"\0") + b"\0")
    (output / path.name).write_bytes(data)
PY
    printf '%s\n' "$build_key" > "$work/build-key"
done
