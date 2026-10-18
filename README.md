# 🎙️ Myanmar TTS Studio (Android App)

မြန်မာအမည်များဖြင့် Voice ရွေးချယ်ပြီး **MP3 Audio + SRT Subtitle** ထုတ်ယူနိုင်တဲ့
Android app ဖြစ်ပါတယ်။ Microsoft Edge TTS ကို ဖုန်းထဲကနေ တိုက်ရိုက်ခေါ်သုံးတာမို့
server လုံးဝမလိုပါဘူး — အင်တာနက်ရှိရင် ရပါပြီ။

## လုပ်ဆောင်ချက်များ

- Voice ရွေးချယ်မှု (မြန်မာအမည် — နေတိုး၊ ဝတ်မှုန်ရွှေရည်၊ ပြေတီဦး အပါအဝင်)
- **အသံနမူနာ** — voice ရွေးပြီးချင်း "🔊 အသံနမူနာ နားထောင်မယ်" နှိပ်ပြီး အသံစမ်းနားထောင်နိုင်မှု
- Script ကို `။` တိုင်းမှာ ခွဲပြီး အပိုင်းလိုက် အသံထုတ်
- အသံ Speed ချိန်ညှိမှု (-50% ~ +100%)
- MP3 + SRT (စကားလုံး ၂၀ ဝန်းကျင်စီ) ထုတ်ယူမှု
- App ထဲမှာတင် အသံပြန်ဖွင့်နားထောင်နိုင်မှု
- MP3 / SRT ကို ဖုန်းရဲ့ **Downloads** folder ထဲ တိုက်ရိုက် Download သိမ်းနိုင်မှု
  (Android 10+ — permission တောင်းစရာမလို)

## APK ထုတ်ယူနည်း

### နည်းလမ်း ၁ — ကွန်ပျူတာရှိရင် (Android Studio)

**Android Studio က ဖုန်းမှာ install လုပ်လို့မရပါဘူး** — Windows / Mac / Linux
ကွန်ပျူတာမှာသာ သုံးလို့ရတာပါ။ <https://developer.android.com/studio> ကနေ
download လုပ်ပါ။

1. Android Studio ကို ဖွင့်ပါ
2. **Open** → ဒီ `myanmar-tts-android` folder ကို ရွေးပါ
   (Gradle ကို ပထမအကြိမ်မှာ သူ့ဘာသာ download လုပ်ပါလိမ့်မယ် — အင်တာနက်လိုပါတယ်)
3. Sync ပြီးသွားရင် menu က **Build → Build App Bundle(s) / APK(s) → Build APK(s)** ကို နှိပ်ပါ
4. ပြီးရင် `app/build/outputs/apk/debug/app-debug.apk` ကို ဖုန်းထဲ ကူးထည့်ပြီး install လုပ်ပါ

### နည်းလမ်း ၂ — ဖုန်းတစ်လုံးတည်းနဲ့ (GitHub Actions, အခမဲ့)

ကွန်ပျူတာ မရှိရင် ဒီနည်းနဲ့ ဖုန်း browser ကနေပဲ APK ထုတ်လို့ရပါတယ်။
`.github/workflows/build-apk.yml` ကို project ထဲမှာ ကြိုထည့်ပေးထားပြီးသားပါ။

1. <https://github.com> မှာ အကောင့်ဖွင့်ပါ (အခမဲ့)
2. **New repository** ဆောက်ပါ (အမည် ဥပမာ `myanmar-tts-studio`)
3. **Add file → Upload files** နဲ့ ဒီ project ထဲက ဖိုင်အားလုံး
   (`app/`, `.github/` folder တွေအပါအဝင်) ကို upload လုပ်ပါ
4. Upload ပြီးရင် repo ရဲ့ **Actions** tab ကို ဖွင့်ကြည့်ပါ —
   "Build Debug APK" ဆိုတဲ့ workflow run နေပါလိမ့်မယ် (၅–၁၀ မိနစ်ခန့် ကြာတတ်ပါတယ်)
5. Run ပြီးသွားရင် အောက်နားက **Artifacts** → **MyanmarTtsStudio-apk** ကို
   နှိပ်ပြီး download လုပ်ပါ — zip ထဲမှာ `app-debug.apk` ပါပါတယ်
6. APK ကို ဖုန်းမှာ ဖွင့်ပြီး install လုပ်ပါ
   ("Unknown sources" ကို ခွင့်ပြုပေးဖို့ မေးရင် Allow လုပ်ပါ)

> Workflow ကို ကိုယ်တိုင် run ချင်ရင် Actions tab → "Build Debug APK" →
> **Run workflow** ကို နှိပ်နိုင်ပါတယ်။

## စမ်းသပ်မှု (Unit Test)

`DrmTest` မှာ Sec-MS-GEC token algorithm ရဲ့ Kotlin port မှန်မမှန်ကို
Python edge-tts fork ကိုယ်တိုင်ထုတ်ပေးထားတဲ့ test vector ၅ ခုနဲ့ စစ်ထားပါတယ်။
Android Studio မှာ `DrmTest` ကို right-click → **Run** နှိပ်ပြီး စစ်နိုင်ပါတယ်။

## နည်းပညာအသေးစိတ်

- **Network:** OkHttp WebSocket → `speech.platform.bing.com` (Edge TTS)
- **Auth:** Sec-MS-GEC v2 token — Python `edge-tts` fork (HarryXiaoCN, `sec-ms-gec-v2`
  branch) ရဲ့ `drm.py` ကို Kotlin (`Drm.kt`) သို့ port ကူးထားခြင်း
- **Audio:** MP3 chunk များကို အစဉ်လိုက် ပေါင်းစပ်ခြင်း (ffmpeg မလို)
- **SRT:** စာသားအရှည်အလိုက် အချိန်ခန့်မှန်းခြင်း + အသံဖိုင်အစစ်အမှန်ကြာချိန်

## မှတ်ချက်

- Zawgyi နဲ့ ရေးထားတဲ့စာသားက အသံထွက် လွဲတတ်လို့ Unicode ပြောင်းပြီးမှ ထည့်ပါ
- Edge TTS က အခမဲ့ service ဖြစ်ပေမယ့် Microsoft ဘက်က ပြောင်းလဲမှုရှိရင်
  app ကို update လုပ်ဖို့ လိုနိုင်ပါတယ်
