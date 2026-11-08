# FFmpeg 9.0.2 "Lei" bundle for MMTube

MMTube ships **FFmpeg 9.0.2 "Lei"** (latest stable, released 2026-09-18),
repacked from [Termux](https://packages.termux.dev/apt/termux-main/pool/main/f/ffmpeg/)
into the `libffmpeg.zip.so` layout consumed by the vendored `com.yausername.ffmpeg.FFmpeg`
(extracted to `noBackupFilesDir` on first launch).

The bundle is **not committed to git** — it would add ~330 MB. It is fetched
and repacked on demand:

```bash
bash tools/ffmpeg/fetch-termux-ffmpeg.sh
```

This:

1. Downloads Termux's `ffmpeg_9.0.2-1` plus its full runtime library closure
   for `aarch64`, `arm`, `i686` and `x86_64` (via a throwaway apt config with
   an empty dpkg status database),
2. Extracts each `.deb` and computes the **NEEDED shared-library closure**
   (starting from `ffmpeg`/`ffprobe`, excluding only bionic's libc/libm/libdl/libandroid),
3. Repacks the closure as `app/src/main/jniLibs/<abi>/libffmpeg.zip.so`
   (with symlinks preserved) plus `libffmpeg.so` and `libffprobe.so`.

CI (`.github/workflows/build.yml`) runs the fetch step automatically before
`./gradlew :app:assembleRelease`.

The Mesa software-Vulkan ICD and LLVM (~140 MB) are **deliberately excluded**:
`libplacebo` hard-requires only `libvulkan.so` (the 600 KB loader, kept) and
`libglslang.so` (kept); the LLVM-based ICD is only needed to *execute* Vulkan,
which MMTube never does.

To update FFmpeg later, bump the version in `fetch-termux-ffmpeg.sh` /
`repack.py` comments and re-run — no app code changes needed.

## Layout (identical to upstream youtubedl-android)

```
usr/lib/<libraries + SONAME symlinks>
libffmpeg.so     (the ffmpeg binary, loaded via --ffmpeg-location)
libffprobe.so    (probes, unused by yt-dlp but kept for parity)
```

## License

Termux builds FFmpeg with GPL components (e.g. libx264, libx265). Like the
other engine components, the FFmpeg bundle is GPL-licensed; MMTube remains
GPL-3.0 and its source is public. See `LICENSE` and `README.md`.
