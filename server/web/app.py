"""MMTube web API — analyze + download jobs from the browser.

Exposed through nginx at /api/. Jobs run in background threads; results are
served from /d/<file> for 24 h.
"""
import logging
import os
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "common"))

import downloader  # noqa: E402

from flask import Flask, jsonify, request  # noqa: E402

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("mmtube-web")

MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT_JOBS", "2"))
SEMAPHORE = threading.Semaphore(MAX_CONCURRENT)
IP_LIMIT = int(os.environ.get("IP_DOWNLOADS_PER_HOUR", "10"))
_ip_window: dict[str, list[float]] = {}

import json
import uuid
from concurrent.futures import ThreadPoolExecutor

_EXECUTOR = ThreadPoolExecutor(max_workers=MAX_CONCURRENT + 1)

_jobs: dict[str, dict] = {}
_jobs_lock = threading.Lock()

app = Flask(__name__)


def _ip_allowed(ip: str) -> bool:
    now = time.time()
    window = [t for t in _ip_window.get(ip, []) if now - t < 3600]
    if len(window) >= IP_LIMIT:
        _ip_window[ip] = window
        return False
    window.append(now)
    _ip_window[ip] = window
    return True


def _sweep_files() -> None:
    removed = downloader.sweep_expired()
    if removed:
        logger.info("swept %d expired file(s)", removed)


def _run_job(job_id: str, info: downloader.ProbeResult, mode: str) -> None:
    with SEMAPHORE:
        try:
            path = downloader.download_once(info, mode, job_id)
            link = downloader.PUBLIC_URL_PREFIX + "/" + path.name
            with _jobs_lock:
                job = _jobs.get(job_id)
                if job:
                    job.update(status="done", link=link,
                               size=path.stat().st_size, name=path.name)
            logger.info("job %s done -> %s", job_id, path.name)
        except Exception as exc:  # noqa: BLE001
            logger.warning("job %s failed: %s", job_id, exc)
            with _jobs_lock:
                job = _jobs.get(job_id)
                if job:
                    job.update(status="failed", error="ဒေါင်းလို့မရပါ — နောက်မှ ထပ်စမ်းပါ။")


@app.after_request
def add_headers(response):
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    return response


@app.get("/api/limits")
def limits():
    return jsonify(
        max_file_bytes=int(os.environ.get("MAX_FILE_BYTES", str(2 * 1024**3))),
        formats=["4K/2K/1080p/720p video", "MP3"],
        note="၂၄ ชมနီးပိုင်းသာ ရနိုင်သည်။ ပိုကြီးလျှင် video quality လျှော့ပါ သို့မဟုတ် MMTube app သုံးပါ။",
    )


@app.post("/api/analyze")
def analyze():
    body = request.get_json(silent=True) or {}
    url = downloader.normalize_url(body.get("url", ""))
    if not url:
        return jsonify(error="Link မှားနေတယ် — YouTube / Facebook / TikTok link ထည့်ပေးပါ။"), 400
    try:
        info = downloader.probe(url)
    except Exception as exc:  # noqa: BLE001
        logger.warning("analyze failed: %s", exc)
        return jsonify(error="Link စစ်လို့မရပါ — video မှန်/မဟုန် စစ်ပါ၊ သို့မဟုတ် နောက်မှ ထပ်စမ်းပါ။"), 502
    return jsonify(
        title=info.title,
        uploader=info.uploader,
        duration=info.duration,
        duration_label=f"{info.duration // 60}m {info.duration % 60}s",
        max_height=info.max_height,
        audio_only=info.audio_only,
        estimated_bytes=info.estimated_bytes,
        extractor=info.extractor,
    )


@app.post("/api/download")
def start_download():
    ip = (request.headers.get("X-Forwarded-For") or request.remote_addr or "unknown").split(",")[0].strip()
    if not _ip_allowed(ip):
        return jsonify(error="များလွန်းပြီ — တစ်နာရီ နောက်မှ ပြန်စမ်းပါ။"), 429

    body = request.get_json(silent=True) or {}
    url = downloader.normalize_url(body.get("url", ""))
    mode = body.get("mode", "video")
    if not url:
        return jsonify(error="Link မှားနေတယ်။"), 400
    if mode not in ("video", "mp3"):
        return jsonify(error="mode မှားနေတယ်။"), 400

    try:
        info = downloader.probe(url)
    except Exception as exc:  # noqa: BLE001
        logger.warning("pre-check failed: %s", exc)
        return jsonify(error="Link စစ်လို့မရပါ။"), 502

    if info.estimated_bytes and info.estimated_bytes > int(os.environ.get("MAX_FILE_BYTES", str(2 * 1024**3))):
        return jsonify(error="File အရမ်းကြီးတယ် — MMTube app သုံးပါ။"), 413

    _sweep_files()
    job_id = downloader.new_job_id()
    with _jobs_lock:
        _jobs[job_id] = {"status": "downloading", "created": time.time()}
    _EXECUTOR.submit(_run_job, job_id, info, mode)
    return jsonify(job_id=job_id, status="downloading")


@app.get("/api/status/<job_id>")
def status(job_id):
    with _jobs_lock:
        job = dict(_jobs.get(job_id) or {})
    if not job:
        return jsonify(error="မတွေ့ပါ။"), 404
    if job.get("created") and time.time() - job["created"] > 3600:
        _jobs.pop(job_id, None)
        return jsonify(error="သက်တမ်းကုန်ပြီ။"), 410
    return jsonify(job)


@app.get("/api/health")
def health():
    return jsonify(ok=True)


def main() -> None:
    downloader.make_job_dir()
    _sweep_files()
    app.run(host="127.0.0.1", port=int(os.environ.get("WEB_PORT", "8001")))


if __name__ == "__main__":
    main()
