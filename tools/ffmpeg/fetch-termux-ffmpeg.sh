#!/bin/bash
# Downloads Termux FFmpeg 9.0.2 and its runtime library closure for all
# Android ABIs, then repacks it into the libffmpeg.zip.so layout used by
# MMTube's vendored FFmpeg init (see tools/ffmpeg/FFmpeg.kt).
#
# Requirements: bash, curl, dpkg-deb, python3 (3.8+), ~1.5 GB disk.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${1:-$SCRIPT_DIR/build}"
JNI_OUT="${2:-$SCRIPT_DIR/../../app/src/main/jniLibs}"
KEEP_WORK="${KEEP_WORK:-1}"   # set KEEP_WORK=0 to scrub afterwards

ARCHES=(aarch64 arm i686 x86_64)
declare -A ABI=( [aarch64]=arm64-v8a [arm]=armeabi-v7a [i686]=x86 [x86_64]=x86_64 )

mkdir -p "$WORK" "$JNI_OUT"

for ARCH in "${ARCHES[@]}"; do
  echo "============== $ARCH =============="
  ABI_NAME="${ABI[$ARCH]}"
  ROOTFS="$WORK/rootfs/$ARCH"
  DEBS="$WORK/debs/$ARCH"
  mkdir -p "$ROOTFS" "$DEBS"
  apt-get update -qq >/dev/null 2>&1 || true
  # per-arch scratch apt config with an empty status file so we get the full closure
  SCRATCH="$WORK/apt-$ARCH"
  mkdir -p "$SCRATCH/cache/archives/partial" "$SCRATCH/state"
  : > "$SCRATCH/status"
  printf 'deb [trusted=yes] https://packages.termux.dev/apt/termux-main stable main\n' > "$SCRATCH/sources.list"
  cat > "$SCRATCH/apt.conf" <<CFG
Dir::Etc::sourcelist "$SCRATCH/sources.list";
Dir::State::status "$SCRATCH/status";
Dir::Cache "$SCRATCH/cache";
Dir::State "$SCRATCH/state";
APT::Architecture "$ARCH";
APT::Architectures { "$ARCH"; };
APT::Get::Download-Only "true";
CFG
  apt-get -c "$SCRATCH/apt.conf" update >/dev/null 2>&1
  apt-get -c "$SCRATCH/apt.conf" -o APT::Install-Recommends=false --download-only install -y "ffmpeg:$ARCH" >/dev/null 2>&1
  mkdir -p "$DEBS"
  mv "$SCRATCH/cache/archives"/*.deb "$DEBS/"
  for deb in "$DEBS"/*.deb; do dpkg-deb -x "$deb" "$ROOTFS/"; done
  echo "$ARCH: extracted $(ls "$DEBS" | wc -l) debs"
done

python3 "$SCRIPT_DIR/repack.py" "$WORK/rootfs" "$JNI_OUT"
if [ "$KEEP_WORK" = "0" ]; then
  rm -rf "$WORK"
fi
echo "FFmpeg 9.0.2 ready in $JNI_OUT"
