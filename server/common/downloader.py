"""Shared yt-dlp download logic for the MMTube bot and the web API.

Both entry points probe a link, then run a download. Everything runs on the
VM; the MAX mode uses the same bitrate-first selection as the Android app.
"""
import json
import logging
import os
import random
import re
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

YTDLP_RAW = os.environ.get("YTDLP_BIN", "yt-dlp")


def _ytdlp_cmd() -> list[str]:
    """yt-dlp may be a real binary (pip install) or a non-executable zipapp
    (e.g. a copy bundled in the Android APK) — then run it with this interpreter."""
    if os.path.exists(YTDLP_RAW) and not os.access(YTDLP_RAW, os.X_OK):
        import sys

        return [sys.executable, YTDLP_RAW]
    return [YTDLP_RAW]

ALLOWED_HOSTS = (
    "youtube.com",
    "youtu.be",
    "music.youtube.com",
    "facebook.com",
    "fb.watch",
    "tiktok.com",
    "www.tiktok.com",
)

MAX_FILE_BYTES = int(os.environ.get("MAX_FILE_BYTES", str(2 * 1024 * 1024 * 1024)))  # 2 GB
JOB_TTL_SECONDS = 24 * 3600
JOB_DIR = Path(os.environ.get("MMTube_JOB_DIR", "/var/lib/mmtube/jobs"))
PUBLIC_DIR = Path(os.environ.get("MMTube_PUBLIC_DIR", "/var/www/mmtube/files"))
PUBLIC_URL_PREFIX = "/d"  # nginx maps /d/ -> PUBLIC_DIR
HOME_PAGE_URL = os.environ.get("MMTube_HOME_URL", "/")

_JOB_ID_RE = re.compile(r"[a-z0-9]{10,16}")


def normalize_url(raw: str) -> str | None:
    raw = raw.strip()
    match = re.search(r"(https?://[^\s]+)", raw)
    if not match:
        return None
    url = match.group(1)
    from urllib.parse import urlparse

    host = (urlparse(url).hostname or "").lower()
    host = host.removeprefix("www.")
    if host not in {h.removeprefix("www.") for h in ALLOWED_HOSTS}:
        return None
    return url


@dataclass
class ProbeResult:
    url: str
    title: str
    uploader: str
    duration: int
    max_height: int
    estimated_bytes: int
    audio_only: bool
    extractor: str


def probe(url: str, timeout: int = 45) -> ProbeResult:
    cmd = _ytdlp_cmd() + [
        "--dump-json",
        "--no-playlist",
        "--no-warnings",
        "--no-update",
        "--no-js-runtimes",
        "--no-check-formats",
        "--socket-timeout", "15",
        "--retries", "2",
        "--extractor-retries", "2",
        url,
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        raise RuntimeError("Could not read media information: " + (proc.stderr or "unknown")[:300])
    try:
        info = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Bad metadata returned") from exc

    formats = info.get("formats", []) or []
    duration = int(info.get("duration") or 0)

    def size_of(fmt):
        """Best possible size estimate for one format (bytes)."""
        size = fmt.get("filesize") or fmt.get("filesize_approx")
        if size:
            return int(size)
        # Fall back to bitrate x duration when size is unknown.
        is_video = (fmt.get("vcodec") or "none") != "none"
        rate_kbps = (fmt.get("tbr") if is_video else fmt.get("abr")) or fmt.get("tbr") or fmt.get("abr") or 0
        if rate_kbps:
            return int(rate_kbps * 1000 / 8 * max(1, duration))
        return 0

    video_formats = [f for f in formats
                     if (f.get("vcodec") or "none") != "none" and (f.get("height") or 0) > 0]
    audio_formats = [f for f in formats
                     if (f.get("acodec") or "none") != "none" and (f.get("vcodec") or "none") == "none"]

    best_video = None
    for f in video_formats:
        key = (f.get("height") or 0, f.get("fps") or 0, f.get("tbr") or 0)
        if best_video is None or key > (best_video.get("height") or 0, best_video.get("fps") or 0, best_video.get("tbr") or 0):
            best_video = f
    best_audio = max(audio_formats, key=lambda f: (f.get("abr") or 0, f.get("tbr") or 0), default=None)

    best_height = video_formats and max(f.get("height") or 0 for f in video_formats) or 0
    estimated = (size_of(best_video) if best_video else 0) + (size_of(best_audio) if best_audio else 0)
    if not estimated:
        estimated = int(info.get("filesize") or info.get("filesize_approx") or 0)

    return ProbeResult(
        url=url,
        title=(info.get("title") or "Untitled")[:120],
        uploader=(info.get("uploader") or info.get("uploader_id") or "")[:60],
        duration=duration,
        max_height=best_height,
        estimated_bytes=int(estimated),
        audio_only=best_height <= 0,
        extractor=(info.get("extractor_key") or info.get("extractor") or ""),
    )


def make_job_dir() -> Path:
    JOB_DIR.mkdir(parents=True, exist_ok=True)
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    return JOB_DIR


def new_job_id() -> str:
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return "".join(random.choice(alphabet) for _ in range(12))


def download_once(probe_info: ProbeResult, mode: str, job_id: str) -> Path:
    """Download to the public dir; returns the resulting filename."""
    out_template = str(PUBLIC_DIR / f"{job_id}.%(ext)s")
    cmd = _ytdlp_cmd() + [
        "--no-playlist",
        "--no-warnings",
        "--no-update",
        "--no-part",
        "--newline",
        "--concurrent-fragments", "24",
        "-N", "24",
        "--socket-timeout", "25",
        "--fragment-retries", "10",
        "--retries", "10",
        "-o", out_template,
    ]
    if mode == "mp3":
        cmd += ["-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "0"]
    else:
        cmd += ["-f", "bestvideo+bestaudio/best", "-S", "res:desc,fps:desc,tbr:desc",
                "--merge-output-format", "mp4"]
    cmd.append(probe_info.url)

    logging.info("download start %s mode=%s", job_id, mode)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if proc.returncode != 0:
        raise RuntimeError("Download failed: " + (proc.stderr or "unknown")[-400:])

    matches = sorted(PUBLIC_DIR.glob(f"{job_id}.*"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not matches:
        raise RuntimeError("Download finished but no output file found")
    path = matches[0]
    if path.stat().st_size == 0:
        path.unlink(missing_ok=True)
        raise RuntimeError("Downloaded file is empty")
    return path


def sweep_expired() -> int:
    removed = 0
    now = time.time()
    for directory in (PUBLIC_DIR, JOB_DIR):
        if not directory.is_dir():
            continue
        for path in directory.iterdir():
            try:
                if path.is_file() and now - path.stat().st_mtime > JOB_TTL_SECONDS:
                    path.unlink()
                    removed += 1
            except OSError:
                continue
    return removed


def public_link(filename: str) -> str:
    return f"{PUBLIC_URL_PREFIX}/{filename}"
