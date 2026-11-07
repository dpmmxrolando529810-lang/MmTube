# MMTube — မြန်မာလမ်းညွှန် (Quick Start)

ဤလမ်းညွှန်သည် MMTube ကို ကိုယ့် GitHub ပေါ်မှာ 빌드ပြီး install လုပ်နည်းကို အမြန်ရှင်းပြခြင်း ဖြစ်ပါသည်။

> သတိပြုရန် — **မည်သည့်ခေတ်မီအက်ပ်မှ "100% အကုန်ရသည်" မရှိနိုင်ပါ။** MMTube သည် မူရင်းပလက်ဖောင်းက ခွင့်ပြုထားသည့် အမြင့်ဆုံး အရည်အသွေးကိုသာ ရယူပါသည်။ Private/DRM/region-lock ဗီဒီယိုများ ဒေါင်းလုဒ် မရနိုင်ပါ။ Free plan မှာ ၂၄ နာရီတစ်ကြိမ် ဒေါင်းလုဒ် ၁ ခုသာ ရသည်။

## ၁။ GitHub ပေါ် push လုပ်ပါ

```bash
git remote add origin https://github.com/<သင့်အကောင့်>/<repository>.git
git push -u origin main
```

In-app update အလုပ်လုပ်ရန် repository သည် **public** ဖြစ်ရမည်။

## ၂။ Firebase ဖွင့်ပါ

1. [console.firebase.google.com](https://console.firebase.google.com) → project အသစ် ဆောက်ပါ။
2. Android app ၂ ခု ထည့်ပါ — `com.mmtube.app` နှင့် `com.mmtube.admin`
3. App တစ်ခုချင်းစီအတွက် **SHA-1** ထည့်ပါ။ (MMTube app → ဆက်တင် → About → *Signing SHA-1*)
4. Authentication → Sign-in method → **Google** ကို ဖွင့်ပါ။
5. Firestore Database ကို production mode ဖြင့် ဆောက်ပါ။
6. Rules ထဲ `firebase/firestore.rules` ကို ကူးထည့်ပြီး Publish လုပ်ပါ။
7. Project settings → Cloud Messaging sender ID = `FIREBASE_SENDER_ID`
8. Web Client ID (자동생성 — Google Cloud → Credentials) = `GOOGLE_WEB_CLIENT_ID`

## ၃။ GitHub Secrets ထည့်ပါ

Settings → Secrets and variables → Actions → New repository secret

| Secret | အရင်းအမြစ် |
|--------|------------|
| `FIREBASE_API_KEY` | Firebase project settings → Web API key |
| `FIREBASE_APP_ID` | `com.mmtube.app` ၏ App ID |
| `FIREBASE_ADMIN_APP_ID` | `com.mmtube.admin` ၏ App ID |
| `FIREBASE_PROJECT_ID` | Firebase project ID |
| `FIREBASE_SENDER_ID` | Project number |
| `GOOGLE_WEB_CLIENT_ID` | Web client ID |
| `SUPPORT_CONTACT` | (ရွေးစရာ) Support လင့်ခ် (Telegram စသည်) |

## ၄။ APK ရယူပါ

Actions → **Build APKs** → Run workflow (သို့) `main` ကို push လုပ်ပါ။ `mmtube-apks` artifact မှ `MMTube.apk` / `MMTube-Admin.apk` ကို ဒေါင်းလုဒ်ဆွဲပြီး ဖုန်းမှာ install ပါ။

အပ်ဒိတ်အလိုအလျောက် ရယူလိုပါက tag တပ်ပါ — `git tag v1.0.1 && git push origin v1.0.1` — GitHub Release ပေါ်လာမည်။ အက်ပ်ထဲမှာတင် "Update available" ပေါ်လာပါလိမ့်မည်။

## ၅။ Admin အကောင့် ဖွင့်ပါ

1. **MMTube Admin** ကို install ပါ → Google ဖြင့် ဝင်ပါ။
2. "Access denied" ပြပါလိမ့်မည် — ထိုစာမျက်နှာရှိ **UID** ကို ကူးယူပါ။
3. Firebase console → Firestore → `admins` collection → စာရွက်စာတမ်း ID = ထို UID (`role: owner` စသည်)
4. Admin app ကို ပြန်ဖွင့်ပါ → Dashboard ရောက်ပါပြီ။

## ၆။ Plan ဖွင့်နည်း

1. User က MMTube → Account → **User ID** (`MM-XXXXXX`) ကို admin ထံ ပို့ပါ။
2. Admin app → ID ထည့်ပြီး Search → **1 / 3 / 6 / 12 Months** နှိပ်ပါ။
   - Plan အသက်ရှိသေးပါက လအရေအတွက် ပေါင်းထည့်ပေးပါသည် (လက်ရှိ သက်တမ်းကုန်ရက်အပေါ်မှာ ပေါင်း)။
   - Free အစီအစဉ်သို့ ပြန်လိုပါက **Set Free**
   - ၂၄ နာရီ စောင့်ဆိုင်းမှု ဖြတ်ရန် **Reset 24h**
   - အကောင့်ပိတ်ရန် **Disable**
3. User က Account → **Refresh** နှိပ်ပါ သို့မဟုတ် app ကို ပြန်ဖွင့်ပါ — Unlimited ဖြစ်နေပါပြီ။

## လိုင်စင်

youtubedl-android / yt-dlp / FFmpeg / aria2c တို့သည် GPL-3.0 ဖြစ်သောကြောင့် MMTube သည်လည်း GPL-3.0 ဖြစ်ပါသည်။ Source code ကို public repository တွင် အမြဲ တွေ့နိုင်ရမည်။

## ဆက်စပ်ဖတ်ရှုရန်

- `README.md` — အင်္ဂလိပ်လမ်းညွှန်၊ ပြဿနာရှာနည်း၊ signing၊ update flow
- `firebase/firestore.rules` — Firestore rules (Publish လုပ်ရန်)
- `keystore/mmtube-demo.p12` — နမူနာ signing key (alias/password = `mmtube`) — production အတွက် ကိုယ်ပိုင် key အစားထိုးပါ
