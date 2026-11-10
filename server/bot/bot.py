"""MMTube Telegram bot — download YouTube/Facebook/TikTok at max quality.

Long polling, no webhook or public port needed for the bot itself.
Files up to TELEGRAM_MAX_BYTES (50 MB) are sent as documents; larger files
are served from the website at https://<domain>/d/<file> instead.
"""
import asyncio
import logging
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "common"))

import downloader  # noqa: E402

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update  # noqa: E402
from telegram.constants import ParseMode  # noqa: E402
from telegram.ext import (  # noqa: E402
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("mmtube-bot")

TOKEN = os.environ.get("BOT_TOKEN")
MAX_BYTES = int(os.environ.get("TELEGRAM_MAX_BYTES", str(48 * 1024 * 1024)))  # 48 MB
JOB_SEMAPHORE = asyncio.Semaphore(int(os.environ.get("MAX_CONCURRENT_JOBS", "2")))
USER_COOLDOWN_SECONDS = int(os.environ.get("USER_COOLDOWN_SECONDS", "30"))
_MAX_PER_HOUR = int(os.environ.get("MAX_DOWNLOADS_PER_HOUR", "10"))

_last_used: dict[int, float] = {}
_hour_window: dict[int, list[float]] = {}
_pending: dict[str, downloader.ProbeResult] = {}


def _esc(text: str) -> str:
    return (text or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _fmt_bytes(size: int) -> str:
    if size >= 1024 * 1024 * 1024:
        return f"{size / 1024**3:.1f} GB"
    if size >= 1024 * 1024:
        return f"{size / 1024**2:.1f} MB"
    return f"{size // 1024} KB"


def _fmt_duration(seconds: int) -> str:
    m, s = divmod(max(0, seconds), 60)
    h, m = divmod(m, 60)
    return f"{h}h {m}m" if h else f"{m}m {s}s"


def _rate_ok(user_id: int) -> tuple[bool, str]:
    import time

    now = time.time()
    last = _last_used.get(user_id, 0)
    if now - last < USER_COOLDOWN_SECONDS:
        return False, "ခဏစောင့်ပါ — စက္ကန့်အနည်းငယ်ကြာမှ ထပ်စမ်းပါ။"
    window = [t for t in _hour_window.get(user_id, []) if now - t < 3600]
    if len(window) >= _MAX_PER_HOUR:
        return False, f"တနာရီအတွင်း {_MAX_PER_HOUR} ခုထက်ပို ဒေါင်းလို့မရသေးပါ။"
    _hour_window[user_id] = window
    return True, ""


def _mark_used(user_id: int) -> None:
    import time

    now = time.time()
    _last_used[user_id] = now
    _hour_window.setdefault(user_id, []).append(now)


def _sweep() -> None:
    removed = downloader.sweep_expired()
    if removed:
        logger.info("swept %d expired file(s)", removed)


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_html(
        "👋 <b>MMTube Bot</b>\n\n"
        "YouTube / Facebook / TikTok link တစ်ခု ထည့်လိုက်ပါ —\n"
        "সর্বোচ্চ quality (4K/1440p) နဲ့ ဒေါင်းပေးမယ်။\n\n"
        "• 48 MB အောက် ဖိုင် → ဒီ chat မှာ တိုက်ရိုက်ပို့\n"
        "• 48 MB ထက်ကြီး → website link ပို့ (၂၄ ชมနီးပိုင်း)\n\n"
        "<b>File 48 MB ထက်ကြီးရင် Android app ကိုသုံးပါ</b> —\n"
        "<a href=\"{0}\">MMTube app ဒေါင်းပါ</a>".format(os.environ.get("MMTube_HOME_URL", "/"))
    )


async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await cmd_start(update, context)


async def handle_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    url = downloader.normalize_url(update.message.text or "")
    if not url:
        await update.message.reply_text("Link မှားနေတယ်။ YouTube / Facebook / TikTok link ထည့်ပေးပါ။")
        return

    ok, reason = _rate_ok(update.effective_user.id)
    if not ok:
        await update.message.reply_text(reason)
        return

    _sweep()
    status = await update.message.reply_text("⏳ စစ်ဆေးနေတယ်…")
    try:
        info = await asyncio.to_thread(downloader.probe, url)
    except Exception as exc:  # noqa: BLE001
        logger.warning("probe failed: %s", exc)
        await status.edit_text("❌ စစ်လို့မရပါ။ Link မှန်/မဟုတ် ပြန်စစ်ပါ၊ ဒါမှမဟုတ် နောက်မှ ထပ်စမ်းပါ။")
        return

    token = downloader.new_job_id()
    _pending[token] = info
    _mark_used(update.effective_user.id)

    quality = f"{info.max_height}p" if info.max_height else "Audio"
    est = _fmt_bytes(info.estimated_bytes) if info.estimated_bytes else "မမှန်းနိုင်"
    keyboard = InlineKeyboardMarkup(
        [
            [InlineKeyboardButton(f"⬇ {quality} Download", callback_data=f"dl:video:{token}")],
            [InlineKeyboardButton("🎵 MP3", callback_data=f"dl:mp3:{token}")],
        ]
    )
    await status.edit_text(
        f"<b>{_esc(info.title)}</b>\n"
        f"👤 {_esc(info.uploader)} · ⏱ {_fmt_duration(info.duration)}\n"
        f"📺 အမြင့်ဆုံး: <b>{quality}</b> · ~{est}\n\n"
        "ရွေးချယ်ပါ:",
        reply_markup=keyboard,
        parse_mode=ParseMode.HTML,
    )


async def handle_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    await query.answer()
    try:
        _, mode, token = (query.data or "").split(":", 2)
    except ValueError:
        return
    info = _pending.get(token)
    if info is None:
        await query.edit_message_text("⌛ Session ကုန်သွားပြီ။ Link ပြန်ပို့ပေးပါ။")
        return

    user_id = query.from_user.id
    ok, reason = _rate_ok(user_id)
    if not ok:
        await query.edit_message_text(reason)
        return

    async with JOB_SEMAPHORE:
        await query.edit_message_text(
            f"📥 ဒေါင်းနေတယ်… ({_fmt_bytes(info.estimated_bytes) if info.estimated_bytes else '?'})\n"
            "4K ဆိုရင် မိနစ်အနည်းငယ် ကြာနိုင်ပါတယ်။"
        )
        _sweep()
        job_id = downloader.new_job_id()
        try:
            path = await asyncio.to_thread(downloader.download_once, info, mode, job_id)
        except Exception as exc:  # noqa: BLE001
            logger.warning("download failed: %s", exc)
            await query.edit_message_text("❌ ဒေါင်းလို့မရပါ။ YouTube ဗီဒီယိုတွေမှာ တစ်ခါတစ်လေ ပြဿနာရှိတတ်တယ် — ပြန်စမ်းပါ။")
            return

    size = path.stat().st_size
    _mark_used(user_id)

    if size <= MAX_BYTES:
        await query.edit_message_text("✅ ရပြီး — ဖိုင်ပို့နေတယ်…")
        try:
            with path.open("rb") as handle:
                if mode == "mp3":
                    await context.bot.send_audio(
                        user_id,
                        handle,
                        title=info.title[:60],
                        performer=info.uploader or None,
                        caption=f"🎵 {info.title}",
                        write_timeout=300,
                        read_timeout=300,
                    )
                else:
                    await context.bot.send_video(
                        user_id,
                        handle,
                        caption=f"📺 {info.title} — {info.max_height}p",
                        write_timeout=300,
                        read_timeout=300,
                    )
            path.unlink(missing_ok=True)
            await query.edit_message_text("✅ ပြီးပြီ")
            return
        except Exception as exc:  # noqa: BLE001
            logger.warning("send failed (%s), falling back to link", exc)

    # Fallback / large file: keep it on the site for 24 h.
    link = downloader.PUBLIC_URL_PREFIX + "/" + path.name
    base = os.environ.get("MMTube_PUBLIC_BASE", "").rstrip("/")
    full_link = (base + link) if base else link
    await query.edit_message_text(
        f"✅ ရပြီး ({_fmt_bytes(size)})\n\n"
        f"48 MB ထက်ကြီးလို့ ဖိုင်ပို့မရ — ဒီ link ကနေ ဒေါင်းပါ (၂၄ ชม ကြာ ရနိုင်):\n"
        f"{_esc(full_link)}",
        parse_mode=ParseMode.HTML,
    )


def main() -> None:
    if not TOKEN:
        raise SystemExit("BOT_TOKEN environment variable is required")
    downloader.make_job_dir()
    _sweep()
    app = Application.builder().token(TOKEN).concurrent_updates(False).build()
    app.add_handler(CommandHandler(["start", "help"], cmd_start))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))
    app.add_handler(CallbackQueryHandler(handle_callback))
    logger.info("MMTube bot starting (long polling)…")
    app.run_polling(allowed_updates=["message", "callback_query"])


if __name__ == "__main__":
    main()
