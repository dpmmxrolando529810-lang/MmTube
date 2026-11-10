# MMTube server (`server/`)

Oracle Cloud **Always Free** VM တစ်ခုတည်းမှာ — Telegram Bot + Website (Downloader) + APK ဒေါင်းလုဒ် — အားလုံး **အခမဲ့ · 24/7 · PC မလို · ဖုန်းထဲ Termux မလို** နဲ့ run လို့ရတဲ့ ပုံးတစ်ခုပါ။

| အပိုင်း | နည်းလမ်း |
|---|---|
| ဆာဗာ | Oracle Always Free Ampere A1 (4 CPU / 24 GB RAM / 200 GB disk) |
| Telegram Bot | long polling (webhook မလို) · 50 MB ထက်ကြီးရင် link ပို့ |
| Website | Nginx + Flask API · browser ထဲကနေ download ရတယ် |
| APK ဒေါင်းလုဒ် | `https://<domain>/MMTube.apk` |
| HTTPS | DuckDNS (free) + Let's Encrypt (free) |
| 24/7 | systemd — 24/7 restart |

---

## အဆင့် ၁ — Oracle Cloud အကောင့် (အခမဲ့)

1. [cloud.oracle.com](https://www.oracle.com/cloud/free/) → **Start for free**
2. အီးမေးလ်, ဖုန်းနံပါတ်, **credit/debit card** နဲ့ verify (**ပိုက်ဆံ မဖြတ်ပါ** — Always Free resources အမြဲအခမဲ့)
3. ⚠️ **"Upgrade to paid account" ကို လုံးဝ မနှိပ်ပါနဲ့** — ဒါမှ ပိုက်ဆံဖြတ်မှာ
4. **Create instance**:
   - Name: `mmtube`
   - **Image**: Canonical **Ubuntu 24.04** (aarch64)
   - **Shape**: Ampere `VM.Standard.A1.Flex` — **1–2 OCPU / 12–24 GB RAM** (Always Free ထဲမှာ)
   - **Networking**: public subnet + **Assign public IPv4**
   - **Boot volume**: 50–100 GB
5. **VCN Security List** မှာ port **80, 443** ဖြည့်ပါ (Ingress Rules → Add Ingress Rules → Source `0.0.0.0/0`, TCP, 80/443)
6. SSH key — `ssh-keygen` နဲ့ key ဆောက်ပြီး public key ကို instance ထဲ paste ပါ (ဒါမှမဟုတ် ဖုန်းက Termux နဲ့လည်း SSH လို့ရ)

> Capacity မရှိလို့ error ပြရင် — region ပြောင်းပါ (Singapore, Tokyo, Mumbai …)။ လူအများကြီး ဒီလို ရတတ်တယ်။

## အဆင့် ၂ — DuckDNS (အခမဲ့ domain)

1. [duckdns.org](https://www.duckdns.org) → Google/GitHub login → subdomain ယူပါ (ဥပမာ `mmtube`)
2. Server ပေါ်မှာ IP ချိတ်ပါ (VM ထဲကနေ):
   ```bash
   curl "https://www.duckdns.org/update?domains=mmtube&token=YOUR_DUCKDNS_TOKEN&ip=PUBLIC_IP"
   ```
   (IP ပြောင်းရင် ထပ်လုပ်ရမယ် — Oracle free VM public IP က static မဟုတ်နိုင်ပါ; Reserved IP လုပ်ရင် အမြဲတူနေမယ်)

## အဆင့် ၃ — Server install

VM ထဲကို SSH ဝင်ပြီး —
```bash
sudo apt-get update && sudo apt-get install -y git ffmpeg aria2 nginx
git clone https://github.com/YOUR_USERNAME/MmTube.git
sudo bash MmTube/server/deploy.sh mmtube.duckdns.org mmtubebot
```
- `mmtube.duckdns.org` — မင်း domain
- `mmtubebot` — Bot နာမည် (t.me/mmtubebot)
- Script က မေးလိမ့်မယ် **Bot token** — [@BotFather](https://t.me/BotFather) ကနေ `/newbot` လုပ်ပြီး ရတဲ့ token ကို paste (input က **နှိပ်နေတဲ့အခါ မပေါ်ဘူး** — ဒါ ပုံမှန်ပါ)

## အဆင့် ၄ — HTTPS (အခမဲ့)
```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d mmtube.duckdns.org
```
ဒါဆို `https://mmtube.duckdns.org/` secure ✅

## အဆင့် ၅ — စစ်ဆေး
```bash
systemctl status mmtube-bot mmtube-web
journalctl -u mmtube-bot -f
```

### Bot မှာ စမ်း
`@mmtubebot` ကို link တစ်ခု ပို့ပါ → title/အရည်အသွေး ပြလိမ့်မယ် → ခလုတ်နှိပ် → ဖိုင်ရမယ် (48MB အောက်)၊ ထက်ကြီးရင် `https://mmtube.duckdns.org/d/xxxx` link ပေးမယ် (၂၄ ชม ကြာ ရမယ်)

### Website မှာ စမ်း
`https://mmtube.duckdns.org/` — Link ထည့် → ဒေါင်းလုဒ်

---

## သိထားသင့်တဲ့ ကန့်သတ်ချက်များ
- **Telegram Bot API က 50 MB ပဲ ပို့လို့ရ** — 48MB ထက် ကြီးရင် link ပို့ (2GB အထိ)
- **2 GB ထက်ကြီးတဲ့ဖိုင်** — YouTube 4K က ~1GB ဝန်းကျင်မို့ ရတယ် — ဒါပေမယ့် network free quota (10 TB/month) ကုန်နိုင်တယ်
- **၂ GB ထက်ကြီး** ရင် "link too big" ဆိုပြီး ငြင်းမယ်
- Personal use အတွက်ပဲ — YouTube/Facebook/TikTok ToS + copyright ကို လိုက်နာပါ
- Bot token ကို **chat ထဲ လုံးဝ မထည့်ပါနဲ့** — server ပေါ်မှာပဲ ထည့်ပါ (`/etc/mmtube/mmtube.env`, mode 0600)

## ပြန်လည်ဖြုတ်/အသစ်ပြင်
```bash
sudo systemctl stop mmtube-bot mmtube-web
sudo rm -rf /opt/mmtube /opt/mmtube-venv
```

## ဖိုင်များ
| ဖိုင် | အဓိပ္ပါယ် |
|---|---|
| `bot/bot.py` | Telegram bot (long polling) |
| `web/app.py` | Flask API (analyze + download jobs) |
| `web/index.html` | Website (landing + browser downloader) |
| `common/downloader.py` | yt-dlp probe + download logic (shared) |
| `nginx/mmtube.conf` | Nginx config (API proxy + /d/ files) |
| `deploy.sh` | တစ်ခါတည်း တပ်ဆင် script |
| `systemd/` | Bot/Web/sweeper services |

**မမြင်မထားတာ** — ဒါက **အခမဲ့ VM** ဖြစ်တဲ့အတွက် resource ကန့်သတ်ချက် (bandwidth/CPU) ရှိတယ်။ တစ်စုံတစ်ဦးက ဗိုင်းရပ်စ် linkတွေ ပို့ရင် rate limit (၂ ချက်/user) ထည့်ပေးထားပေမယ့် ကြီးမားတဲ့ public service အတွက်တော့ မလုံလောက်ပါ။
