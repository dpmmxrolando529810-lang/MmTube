#!/bin/bash
# Zero-cost phone mode: run the MMTube Telegram bot on the phone / Termux.
# No server, no domain, no card. Long polling needs no public IP.
# Limits: cloud Bot API only (50 MB upload), and the process must stay alive.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -f "$ROOT/server/.env" ]; then
  set -a
  . "$ROOT/server/.env"
  set +a
fi

if [ -z "${BOT_TOKEN:-}" ]; then
  echo "BOT_TOKEN missing — put it in server/.env (BOT_TOKEN=...)" >&2
  exit 1
fi

export TELEGRAM_LOCAL_API=0
export MMTube_JOB_DIR="${MMTube_JOB_DIR:-$ROOT/server/.jobs}"
export MMTube_PUBLIC_DIR="${MMTube_PUBLIC_DIR:-$ROOT/server/.files}"
export PYTHONUNBUFFERED=1

mkdir -p "$MMTube_JOB_DIR" "$MMTube_PUBLIC_DIR"

python3 -m pip install --quiet -r "$ROOT/server/requirements.txt" || true
exec python3 "$ROOT/server/bot/bot.py"
