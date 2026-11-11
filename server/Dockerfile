# MMTube browser downloader — container image for Render / Docker hosts.
# Build from the repository root:  docker build -f server/Dockerfile -t mmtube-web .
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

COPY server/ server/

ENV PYTHONUNBUFFERED=1 \
    MMTube_JOB_DIR=/tmp/mmtube/jobs \
    MMTube_PUBLIC_DIR=/tmp/mmtube/files \
    MMTube_SITE_DIR=/app/server/web \
    MAX_CONCURRENT_JOBS=1

EXPOSE 8000

CMD ["sh", "-c", "cd /app/server/web && gunicorn --workers 1 --threads 8 --timeout 900 --graceful-timeout 30 -b 0.0.0.0:${PORT:-8000} app:app"]
