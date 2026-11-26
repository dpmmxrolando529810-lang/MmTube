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
import recap  # noqa: E402

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
LOCAL_API = os.environ.get("TELEGRAM_LOCAL_API", "0") == "1"
_CLOUD_MAX = 48 * 1024 * 1024
_LOCAL_MAX = 2000 * 1024 * 1024  # telegram-bot-api --local cap
_default_max = _LOCAL_MAX if LOCAL_API else _CLOUD_MAX
MAX_BYTES = min(
    int(os.environ.get("TELEGRAM_MAX_BYTES", str(_default_max))),
    _LOCAL_MAX if LOCAL_API else _CLOUD_MAX,
)
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
        "• 48 MB ထက်ကြီး → website link ပို့ (၂၄ နာရီအတွင်း ရနိုင်)\n\n"
        "<b>🎬 1-Click Recap + Clips:</b> link ပို့ပြီး ⚡ Both ကိုနှိပ်ပါ "
        "သို့မဟုတ် <code>/recap &lt;link&gt;</code> သုံးပါ။\n\n"
        "<b>File 48 MB ထက်ကြီးရင် Android app ကိုသုံးပါ</b> —\n"
        "<a href=\"{0}\">MMTube app ဒေါင်းပါ</a>".format(os.environ.get("MMTube_HOME_URL", "/"))
    )


async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await cmd_start(update, context)


def _recap_keyboard(token: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("🎬 Recap", callback_data=f"rc:recap:{token}"),
         InlineKeyboardButton("✂️ Clips", callback_data=f"rc:clips:{token}")],
        [InlineKeyboardButton("⚡ Both (Recap + Clips)", callback_data=f"rc:both:{token}")],
    ])


async def cmd_recap(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """/recap <link> — 1-click recap + clips from any supported link."""
    args = " ".join(context.args or []).strip()
    if not args:
        await update.message.reply_text(
            "🎬 <b>1-Click Recap + Clip Maker</b>\n\n"
            "ဒီလိုပို့ပါ: <code>/recap https://youtube.com/watch?v=...</code>\n"
            "သို့မဟုတ် link ကို ပုံမှန်ပို့ပြီး ⚡ Both ကို နှိပ်ပါ။\n\n"
            "Recap = အနှစ်ချုပ် · Clips = 9:16 Shorts/Reels အတွက် တိုတောင်းကလစ်တွေ။",
            parse_mode=ParseMode.HTML,
        )
        return
    url = downloader.normalize_url(args)
    if not url:
        await update.message.reply_text("Link မှားနေတယ်။ YouTube / Facebook / TikTok link ပို့ပါ။")
        return
    ok, reason = _rate_ok(update.effective_user.id)
    if not ok:
        await update.message.reply_text(reason)
        return
    status = await update.message.reply_text("⏳ Link စစ်ဆေးနေတယ်…")
    try:
        info = await asyncio.to_thread(downloader.probe, url)
    except Exception:  # noqa: BLE001
        await status.edit_text("❌ စစ်လို့မရပါ။ Link ပြန်စစ်ပါ။")
        return
    token = downloader.new_job_id()
    _pending[token] = info
    _mark_used(update.effective_user.id)
    await status.edit_text(
        f"<b>{_esc(info.title)}</b> · ⏱ {_fmt_duration(info.duration)}\n\n"
        "ဘာဖန်တီးမလဲ ရွေးပါ:",
        reply_markup=_recap_keyboard(token), parse_mode=ParseMode.HTML,
    )


async def _run_recap(query, context: ContextTypes.DEFAULT_TYPE, mode: str, info) -> None:
    user_id = query.from_user.id
    async with JOB_SEMAPHORE:
        def env_on(name: str, default: bool = True) -> bool:
            return os.environ.get(name, "1" if default else "0").lower() not in ("0", "false", "off")

        job_id = recap.submit(info.url, mode, {
            "seconds": int(os.environ.get("RECAP_BOT_SECONDS", "90")),
            "clips": int(os.environ.get("RECAP_BOT_CLIPS", "3")),
            "clip_seconds": int(os.environ.get("RECAP_BOT_CLIP_SECONDS", "30")),
            "vertical": True, "captions": True, "title": info.title,
            "karaoke": env_on("RECAP_BOT_KARAOKE"),
            "clean_audio": env_on("RECAP_BOT_CLEAN_AUDIO"),
            "smart_pacing": env_on("RECAP_BOT_SMART_PACING"),
            "thumbnail": env_on("RECAP_BOT_THUMBNAIL"),
            "voiceover": env_on("RECAP_BOT_VOICEOVER", False),
            "face_focus": env_on("RECAP_BOT_FACE_FOCUS", False),
            "ai_meta": env_on("RECAP_BOT_AI_META", False),
        })
        last_stage = ""
        while True:
            await asyncio.sleep(2)
            job = recap.get_job(job_id) or {}
            stage = job.get("stage", "")
            if stage != last_stage:
                last_stage = stage
                try:
                    await query.edit_message_text(
                        "⚙️ " + (recap_stage_text(stage)) +
                        f" · {int((job.get('progress') or 0) * 100)}%"
                    )
                except Exception:
                    pass
            if job.get("status") in ("done", "failed"):
                break
        if job.get("status") != "done":
            await query.edit_message_text("❌ " + (job.get("error") or "လုပ်ဆောင်မှု မအောင်မြင်ပါ။")[:300])
            return

    outputs = job.get("outputs", [])
    base = os.environ.get("MMTube_PUBLIC_BASE", "").rstrip("/")
    await query.edit_message_text(f"✅ ရပြီ — ဖိုင် {len(outputs)} ခု ပို့နေတယ်…")
    sent = 0
    for item in outputs:
        name = item["url"].rsplit("/", 1)[-1]
        path = recap.OUT_DIR / name
        if not path.exists():
            continue
        if path.stat().st_size <= MAX_BYTES:
            try:
                with path.open("rb") as handle:
                    kind = item.get("kind")
                    caption = f"{item['label']} — {_esc(info.title)[:60]}"
                    if kind == "thumbnail":
                        await context.bot.send_photo(
                            user_id, handle, caption=caption,
                            write_timeout=300, read_timeout=300,
                        )
                    elif kind == "metadata":
                        await context.bot.send_document(
                            user_id, handle, caption=caption,
                            write_timeout=300, read_timeout=300,
                        )
                    else:
                        await context.bot.send_video(
                            user_id, handle, caption=caption,
                            write_timeout=300, read_timeout=300,
                        )
                sent += 1
                continue
            except Exception as exc:  # noqa: BLE001
                logger.warning("recap send failed: %s", exc)
        link = base + item["url"] if base else item["url"]
        await context.bot.send_message(
            user_id,
            f"<b>{item['label']}</b> · {_fmt_bytes(item['size'])}\n"
            f"ဖိုင် 48 MB ထက်ကြီးလို့ link နဲ့ ပို့ပေးလိုက်ပါတယ် (၂၄ နာရီ):\n{_esc(link)}",
            parse_mode=ParseMode.HTML,
        )
    if sent == len(outputs) and outputs:
        await query.edit_message_text("✅ ပြီးပြီ — ဖိုင်အားလုံး ပို့ပြီးပါတယ်။")


def recap_stage_text(stage: str) -> str:
    return {
        "queued": "တန်းစီနေတယ်", "download": "ဗီဒီယို ရယူနေတယ်",
        "analyze": "Scene + အသံ ခွဲခြမ်းနေတယ်", "render-recap": "Recap တည်ဆောက်နေတယ်",
        "render-clips": "Clips တည်ဆောက်နေတယ်", "done": "ပြီးပါပြီ", "failed": "မအောင်မြင်ပါ",
    }.get(stage, stage or "လုပ်ဆောင်နေတယ်")


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
    platform = getattr(info, "platform", "YOUTUBE")
    est = _fmt_bytes(info.estimated_bytes) if info.estimated_bytes else "မမှန်းနိုင်"
    keyboard = InlineKeyboardMarkup(
        [
            [InlineKeyboardButton(f"⬇ {quality} Download", callback_data=f"dl:video:{token}")],
            [InlineKeyboardButton("🎵 MP3", callback_data=f"dl:mp3:{token}")],
            [InlineKeyboardButton("🎬 Recap", callback_data=f"rc:recap:{token}"),
             InlineKeyboardButton("✂️ Clips", callback_data=f"rc:clips:{token}")],
            [InlineKeyboardButton("⚡ Both — 1 Click", callback_data=f"rc:both:{token}")],
        ]
    )
    await status.edit_text(
        f"<b>{_esc(info.title)}</b>\n"
        f"👤 {_esc(info.uploader)} · ⏱ {_fmt_duration(info.duration)} · 🌐 {platform}\n"
        f"📺 အမြင့်ဆုံး: <b>{quality}</b> · ~{est}\n"
        + ("⚠️ ဒီဗီဒီယိုမှာ watermark ပါတဲ့ format တွေပဲ ရှိပါတယ်\n" if getattr(info, "watermark_only", False) else "")
        + "\nရွေးချယ်ပါ:",
        reply_markup=keyboard,
        parse_mode=ParseMode.HTML,
    )


async def handle_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    await query.answer()
    try:
        prefix, mode, token = (query.data or "").split(":", 2)
    except ValueError:
        return
    info = _pending.get(token)
    if info is None:
        await query.edit_message_text("⌛ Session ကုန်သွားပြီ။ Link ပြန်ပို့ပေးပါ။")
        return
    if prefix == "rc":
        await _run_recap(query, context, mode, info)
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
            media = f"file://{path.resolve()}" if LOCAL_API else path.open("rb")
            try:
                if mode == "mp3":
                    await context.bot.send_audio(
                        user_id,
                        media,
                        title=info.title[:60],
                        performer=info.uploader or None,
                        caption=f"🎵 {info.title}",
                        write_timeout=600,
                        read_timeout=600,
                    )
                else:
                    await context.bot.send_video(
                        user_id,
                        media,
                        caption=f"📺 {info.title} — {info.max_height}p",
                        supports_streaming=True,
                        write_timeout=600,
                        read_timeout=600,
                    )
            finally:
                if not LOCAL_API:
                    media.close()
            path.unlink(missing_ok=True)
            await query.edit_message_text("✅ ပြီးပြီ")
            return
        except Exception as exc:  # noqa: BLE001
            logger.warning("send failed (%s), falling back to link", exc)

    # Fallback / large file: keep it on the site for 24 h when a public URL exists.
    base = os.environ.get("MMTube_PUBLIC_BASE", "").rstrip("/")
    if not base:
        await query.edit_message_text(
            f"✅ ရပြီး ({_fmt_bytes(size)})။ ဒါပေမယ့် cloud bot က "
            f"{_fmt_bytes(MAX_BYTES)} အထိပဲ တိုက်ရိုက်ပို့နိုင်ပါတယ်။\n"
            "Public website link မသတ်မှတ်ထားလို့ ဒီဖိုင်ကို ဆက်ပို့လို့ မရပါဘူး။"
        )
        return
    link = downloader.PUBLIC_URL_PREFIX + "/" + path.name
    full_link = base + link
    await query.edit_message_text(
        f"✅ ရပြီး ({_fmt_bytes(size)})\n\n"
        f"{_fmt_bytes(MAX_BYTES)} ထက်ကြီးလို့ ဖိုင်ပို့မရ — ဒီ link ကနေ ဒေါင်းပါ (၂၄ နာရီ ကြာ ရနိုင်):\n"
        f"{_esc(full_link)}",
        parse_mode=ParseMode.HTML,
    )


def build_application():
    """Shared builder used by the systemd service and the Actions worker."""
    if not TOKEN:
        raise SystemExit("BOT_TOKEN environment variable is required")
    downloader.make_job_dir()
    _sweep()
    builder = Application.builder().token(TOKEN).concurrent_updates(False)
    builder.media_write_timeout(600)
    if LOCAL_API:
        base = os.environ.get("TELEGRAM_API_BASE", "http://127.0.0.1:8081").rstrip("/")
        builder.base_url(base + "/bot")
        builder.base_file_url(base + "/file/bot")
        builder.local_mode(True)
        get_updates = getattr(builder, "get_updates_base_url", None)
        if callable(get_updates):
            get_updates(base + "/bot")
        logger.info("Using LOCAL Bot API server at %s (upload cap %s)", base, _fmt_bytes(MAX_BYTES))
    app = builder.build()
    app.add_handler(CommandHandler(["start", "help"], cmd_start))
    app.add_handler(CommandHandler("recap", cmd_recap))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))
    app.add_handler(CallbackQueryHandler(handle_callback))
    return app


def main() -> None:
    app = build_application()
    logger.info("MMTube bot starting (long polling)…")
    app.run_polling(allowed_updates=["message", "callback_query"])


if __name__ == "__main__":
    main()
