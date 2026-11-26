"""GitHub Actions worker: keep the Telegram bot alive in ~6 h chunks.

Public repositories get unlimited free standard GitHub-hosted runner minutes.
A single job is capped at 6 hours, so this worker polls for ~5 h 45 m, saves the
getUpdates offset into the Actions cache, then the workflow dispatches the next
run. There is a short gap (runner boot + pip install) between chunks.
"""
import asyncio
import logging
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bot"))
sys.path.insert(0, str(ROOT / "common"))

import bot  # noqa: E402

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("mmtube-actions")

STATE_DIR = Path(os.environ.get("MMTube_STATE_DIR", ROOT / ".state"))
OFFSET_FILE = STATE_DIR / "offset.txt"
WORK_SECONDS = int(os.environ.get("MMTube_WORK_SECONDS", str(5 * 3600 + 45 * 60)))
ALLOWED = ["message", "callback_query"]


def read_offset() -> int:
    try:
        return int(OFFSET_FILE.read_text().strip() or "0")
    except (OSError, ValueError):
        return 0


def save_offset(offset: int) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    OFFSET_FILE.write_text(str(offset), encoding="ascii")


async def run() -> None:
    application = bot.build_application()
    deadline = time.monotonic() + WORK_SECONDS
    offset = read_offset()
    logger.info("Actions worker start: offset=%s, work=%ss", offset, WORK_SECONDS)

    await application.initialize()
    await application.start()
    try:
        while time.monotonic() < deadline:
            try:
                updates = await application.bot.get_updates(
                    offset=offset,
                    timeout=25,
                    allowed_updates=ALLOWED,
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("getUpdates failed: %s", exc)
                await asyncio.sleep(10)
                continue
            if not updates:
                continue
            for update in updates:
                await application.update_queue.put(update)
                offset = update.update_id + 1
                save_offset(offset)
    finally:
        await application.stop()
        await application.shutdown()
    logger.info("Actions worker finished: offset=%s", offset)


if __name__ == "__main__":
    asyncio.run(run())
