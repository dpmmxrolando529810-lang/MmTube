#!/bin/bash
# MMTube server installer — Ubuntu 22.04/24.04 (aarch64 or x86_64).
# Run from the repository root:  sudo bash server/deploy.sh yourdomain.duckdns.org mmtubebot
set -euo pipefail

DOMAIN="${1:?Usage: sudo bash server/deploy.sh <domain> [bot_username]}"
BOT_USERNAME="${2:-mmtubebot}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Installing packages"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  ffmpeg aria2 nginx python3-venv python3-pip curl git rsync

echo "==> Creating user and directories"
id mmtube >/dev/null 2>&1 || useradd -r -m -s /bin/bash mmtube
install -d -o mmtube -g mmtube /var/lib/mmtube/jobs /var/www/mmtube/files /var/www/mmtube/site /etc/mmtube
install -d -o root -g root /opt/mmtube

echo "==> Copying server code"
install -d -o root -g root /opt/mmtube
rsync -a --delete "$REPO_ROOT/server/" /opt/mmtube/server/
find /opt/mmtube -type f \( -name '*.py' -o -name '*.html' \) -exec chmod 0644 {} \;
chown -R root:root /opt/mmtube

echo "==> Installing APK into site root (if present)"
install -d -o root -g mmtube /var/www/mmtube/site
rsync -a /opt/mmtube/server/web/assets/ /var/www/mmtube/site/assets/
chmod 0755 /var/www/mmtube/site /var/www/mmtube/site/assets
if [ -f "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" ]; then
  cp "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" /var/www/mmtube/site/MMTube.apk
  chmod 0644 /var/www/mmtube/site/MMTube.apk
else
  echo "    APK not found — skip (copy it in later as /var/www/mmtube/site/MMTube.apk)"
fi

echo "==> Creating Python venv"
python3 -m venv /opt/mmtube-venv
/opt/mmtube-venv/bin/pip install --no-cache-dir -q --upgrade pip
/opt/mmtube-venv/bin/pip install --no-cache-dir -q -r /opt/mmtube/server/requirements.txt

echo "==> Writing environment file"
if [ ! -f /etc/mmtube/mmtube.env ]; then
  read -r -s -p "Telegram bot token (BotFather ကနေ): " BOT_TOKEN
  echo
  cat > /etc/mmtube/mmtube.env <<EOF
BOT_TOKEN=${BOT_TOKEN}
MMTube_PUBLIC_BASE=https://${DOMAIN}
MMTube_HOME_URL=https://${DOMAIN}/
MMTube_JOB_DIR=/var/lib/mmtube/jobs
MMTube_PUBLIC_DIR=/var/www/mmtube/files
MAX_FILE_BYTES=$((2 * 1024 * 1024 * 1024))
MAX_DOWNLOADS_PER_HOUR=10
IP_DOWNLOADS_PER_HOUR=10
EOF
  chmod 0600 /etc/mmtube/mmtube.env
  chown root:mmtube /etc/mmtube/mmtube.env
else
  echo "    /etc/mmtube/mmtube.env already exists — keeping it"
fi

echo "==> Configuring website (domain: ${DOMAIN})"
rsync -a /opt/mmtube/server/web/index.html /var/www/mmtube/site/index.html
sed -i "s/const BOT_USERNAME = '[^']*'/const BOT_USERNAME = '${BOT_USERNAME}'/" /var/www/mmtube/site/index.html
if [ -f "$REPO_ROOT/store/icon-512.png" ]; then
  cp "$REPO_ROOT/store/icon-512.png" /var/www/mmtube/site/assets/logo.png
fi

sed "s/__DOMAIN__/${DOMAIN}/g" /opt/mmtube/server/nginx/mmtube.conf > /etc/nginx/sites-available/mmtube
ln -sf /etc/nginx/sites-available/mmtube /etc/nginx/sites-enabled/mmtube
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "==> Installing systemd services"
cp /opt/mmtube/server/systemd/mmtube-*.service /opt/mmtube/server/systemd/mmtube-*.timer /etc/systemd/system/ 2>/dev/null || \
  cp /opt/mmtube/server/systemd/mmtube-*.service /etc/systemd/system/
cp /opt/mmtube/server/systemd/mmtube-sweep.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now mmtube-bot mmtube-web mmtube-sweep.timer

echo
echo "==> Done!"
echo "    Site:  http://${DOMAIN}/   (HTTPS: sudo certbot --nginx -d ${DOMAIN})"
echo "    APK:   http://${DOMAIN}/MMTube.apk"
echo "    Bot:   t.me/${BOT_USERNAME}"
echo
echo "    Check: systemctl status mmtube-bot mmtube-web"
echo "    Logs:  journalctl -u mmtube-bot -f   |   journalctl -u mmtube-web -f"
