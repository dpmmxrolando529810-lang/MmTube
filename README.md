# MMTube

**MMTube** is a native Android video downloader for **YouTube, Facebook and TikTok**, built with Kotlin + Jetpack Compose. It downloads the maximum quality the source provides and saves it straight to your phone's gallery — no Termux, no PC, no server required. A second companion app, **MMTube Admin**, activates Unlimited plans by User ID.

> ⚠️ Honest expectations: **no app can download "100% of everything, everywhere, forever".**
> MMTube gives you the best quality *the source actually offers* (e.g. up to 4K on YouTube when available), merged from the best video + audio streams with FFmpeg. DRM-protected, private, age-restricted, removed or region-locked videos cannot be downloaded. Speed and availability depend on the site and your network. Free limits are enforced client-side (best effort) unless you add a server component.

---

## Two apps in this repository

| App | Package | Purpose |
|-----|---------|---------|
| **MMTube** | `com.mmtube.app` | Downloader for everyone. Sign in with Google or browse as a guest. |
| **MMTube Admin** | `com.mmtube.admin` | For the project owner: activate/renew Unlimited plans by User ID, reset 24h cooldowns, disable accounts. |

Both apps ship from the same codebase and are built together in CI.

---

## Features

- **Link auto-detection** — paste or share a YouTube / Facebook / TikTok link, MMTube recognizes the platform instantly.
- **Maximum quality** — probes the source with yt-dlp, selects the best video + best audio, and merges them with FFmpeg. Options: MAX, 1080p, 720p, MP3 (best audio).
- **TikTok without watermark** — requests the clean stream TikTok serves. (If TikTok only offers the watermarked version, that is what gets downloaded — the app cannot invent one that does not exist.)
- **On-device engine** — Python + yt-dlp + FFmpeg + aria2c run inside the app via [youtubedl-android](https://github.com/yausername/youtubedl-android). No Termux, no PC, no backend. Engine auto-updates from GitHub nightly builds.
- **True free tier** — guests: 1 local download per 24h. Signed-in free users: 1 download per 24h, synced through Firestore usage counters.
- **Unlimited plans** — 1 / 3 / 6 / 12 months, activated by the admin. Plans stack from the current expiry date.
- **In-app auto-update** — checks GitHub Releases for a newer APK on launch (configurable), downloads and hands off to the Android package installer. Same-key signing required (see "Signing" below).
- **Gallery export** — saves into `Movies/MMTube` (videos) and `Music/MMTube` (audio) via MediaStore on Android 10+, no broad storage permission needed.
- **Dark / light / system theme**, smooth Compose animations, English + Burmese languages, share-target support, foreground-service downloads with notifications and cancellation.

---

## System requirements

- **Android 7.0 (API 24) or newer.** The download engine (Python + native libs from youtubedl-android) requires API 24+; older versions are not supported.
- 3–4 GB free RAM recommended on first launch while the engine unpacks.
- The universal APK contains `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86` native libraries, so it is large (~150–250 MB).
- **Target SDK 35**, compiled against the Android 15 toolchain.

---

## Quick start — build the APKs with GitHub (recommended)

No Android SDK on your phone/PC is required if you use GitHub Actions.

### 1. Create a GitHub repository and push this code

```bash
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

The repository **must be public** for the in-app updater to download releases without a token.

### 2. Add GitHub Secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**

Required (both apps):

| Secret | From |
|--------|------|
| `FIREBASE_API_KEY` | Firebase console → Project settings → Web API key (or the Android app config) |
| `FIREBASE_APP_ID` | Firebase console → Android app `com.mmtube.app` → App ID (`1:xxxx:android:yyyy`) |
| `FIREBASE_ADMIN_APP_ID` | Same for the second Android app `com.mmtube.admin` |
| `FIREBASE_PROJECT_ID` | Firebase project ID |
| `FIREBASE_SENDER_ID` | Firebase project number (Cloud Messaging sender ID) |
| `GOOGLE_WEB_CLIENT_ID` | Google Cloud console → Credentials → **Web client** (auto-created by Firebase) |
| `UPDATE_REPO` | *Not needed — the workflow sets it to `github.repository` automatically.* |

Optional:

| Secret | Purpose |
|--------|---------|
| `SUPPORT_CONTACT` | Where "Contact support" opens (Telegram/email/WhatsApp link) |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Production signing (defaults use the committed demo key) |

### 3. Run the workflow

Actions → **Build APKs** → Run workflow (or push to `main`), then download the `mmtube-apks-*` artifacts. Tag a release like `v1.0.1` to publish a GitHub Release automatically; MMTube users will receive the update inside the app.

---

## Firebase setup (Google sign-in + plans)

1. **Create a Firebase project** at console.firebase.google.com. Add two Android apps:
   - `com.mmtube.app` (main app)
   - `com.mmtube.admin` (admin app)
   For each, add the **signing SHA-1**. In the main app: Settings → About → *Signing SHA-1* (shown in the app) — use the one matching the keystore used to build (demo key for CI, your production key otherwise).
2. **Enable Google sign-in**: Authentication → Sign-in method → Google → Enable.
3. **Create Firestore** in production mode, then paste the rules from `firebase/firestore.rules` (Firestore → Rules → paste → Publish).
4. **Copy the config into GitHub Secrets** (see table above) — you do *not* commit `google-services.json`; the apps initialize Firebase programmatically.
5. **Create your admin account**: sign in to the admin app once — it will show *Access denied* with your UID. In the Firebase console create a document `admins/{uid}` (any data, e.g. `{ role: "owner" }`). Sign in again → dashboard unlocked.

Firestore layout:

```
admins/{uid}      — { } existence check only; managed manually/console
users/{uid}       — uid, email, displayName, photoUrl, userCode (MM-XXXXXX),
                    plan ('free'|'1m'|'3m'|'6m'|'12m'), planExpiresAt, dailyLimit,
                    disabled, createdAt, updatedAt
usage/{uid}       — lastDownloadAt, nextAllowedAt, totalDownloads, quotaResetAt, updatedAt
```

Users can read their own profile/usage and create only a *free* profile. Plan changes are admin-only. All plan logic stays server-side via security rules.

---

## Plans & the admin app

1. Install **MMTube Admin** on the owner's phone.
2. Sign in with the Google account whose UID is in `admins/{uid}`.
3. A user sends you their **User ID** (Account screen, starts with `MM-`).
4. Search the ID, then **Activate 1 / 3 / 6 / 12 months**. Time stacks: if the plan is active, months are added onto the existing expiry; otherwise they start from now.
5. **Reset 24h cooldown** sets `quotaResetAt` and clears `nextAllowedAt`; the main app treats pre-reset local cooldowns as invalid, so the user can download immediately.
6. **Disable / Enable** flips `users/{uid}.disabled`; the main app then refuses downloads for that account.

Admin actions use the Firebase Admin SDK from the phone with the owner's admin UID — no server required; Firestore rules enforce that only admins can write plan data.

---

## Signing (important)

This repository commits a **demo keystore** (`keystore/mmtube-demo.p12`, alias/password `mmtube`) so the CI pipeline works with zero configuration and so app updates can install over each other. **Anyone can sign an APK with this key.** Before real distribution:

1. Generate your own release keystore (`keytool -genkeypair ...` or Android Studio).
2. Remove the committed demo keystore from git.
3. Provide `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as secrets and `keystore/your-key.p12` in CI (e.g. via a private repo artifact or base64 secret + decode step).
4. Keep the **same key forever** after the first release; otherwise users cannot update in place.

---

## In-app updates

MMTube checks `https://api.github.com/repos/<owner>/<repo>/releases/latest` on launch (throttled to every 6 hours, toggleable in Settings). A release is eligible when:

- the tag is newer (`v1.2.0` > installed `1.1.0`),
- it has an `.apk` asset that does **not** contain `admin` (the admin APK is correctly filtered out),
- its asset prefers `*universal*` builds.

Tapping **Download update** fetches the APK to app storage, then **Install** launches the Android installer via FileProvider (a one-time "Allow from this source" grant is required). Unattended silent install is not possible without root or managed Play tracks — the flow is fully automatic except for that last OS-mandated tap.

---

## Local build (optional, needs Android SDK)

```bash
export ANDROID_HOME=$HOME/android-sdk   # + platform-tools, platform-35, build-tools
export FIREBASE_API_KEY=... FIREBASE_APP_ID=... FIREBASE_PROJECT_ID=...
export FIREBASE_ADMIN_APP_ID=... FIREBASE_SENDER_ID=... GOOGLE_WEB_CLIENT_ID=...
export UPDATE_REPO=<owner>/<repo>
./gradlew :app:assembleRelease :admin:assembleRelease
```

APKs land in `app/build/outputs/apk/release/` and `admin/build/outputs/apk/release/`.

---

## License & legal

youtubedl-android, yt-dlp, FFmpeg and aria2c are **GPL-3.0** components; consequently MMTube is **GPL-3.0** and its source must remain available to users who receive the APK. Downloading from YouTube, Facebook or TikTok may violate those services' Terms of Service — MMTube is a personal tool for content you own or have permission to save. It does not bypass DRM or paid content. You are responsible for your downloads.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| First launch stuck on "Preparing the download engine" | Wait 30–60s (Python/FFmpeg unpack). Keep the app in the foreground. |
| "The engine could not start" | Settings → Download engine → Update engine; restart the app. |
| Analysis fails for a site that worked before | The extractor changed. Settings → Update engine (nightly yt-dlp), then retry. |
| "Update available" but Install does nothing | Allow installs from unknown sources for MMTube in Android Settings, then Install again. |
| Paid plan active but app still limits | Account → Refresh plan. Check internet + Firestore rules published. |
| Admin reset didn't lift the cooldown | Ask the user to force-stop and reopen MMTube; the app honors `quotaResetAt` on next read. |
| App won't install older than Android 7 | Use an older device build (not supported) or a different tool — the engine needs API 24+. |

See `docs/SETUP-MY.md` for a Burmese quick-start guide.
