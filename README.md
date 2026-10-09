# Overlingo for Android

*English · [Български](README_bg.md)*

Take a photo of text and the translation appears right on top of it. The mobile version of the [Overlingo](https://github.com/realdark/overlingo) desktop app.

## Features (version 1.8)

- **Photo → translation over the text.** Point the camera and take a photo. The photo keeps exactly what you saw on the screen. The app finds the text and draws the translation over each block (heading, text, sign) - no need to select anything. The paragraphs of one text share one window, separated by a blank line; separate labels and headings keep their own windows.
- **Tap a block** to see the original and the translation, with buttons to copy and read aloud. From there the block can also be **hidden** - it disappears from the photo, isn't read aloud and isn't included when saving or sharing ("N hidden · Show all" appears at the top; tapping the frame brings it back). The 🔊 button next to "New photo" starts a selection: tap the blocks you want to hear (they get numbered and are read in the order you tapped them) and press "Read"; "All" reads everything in order. While reading, the same button stops it.
- **Photo from the gallery** or **"Share" → Overlingo** from another app (a photo or text).
- **Text translation** (the "Aa" button): type or paste text.
- **Zoom** with two fingers. The 🖼/文 button switches between the original and the translation, and "All text" shows everything at once.
- **Services:** Google (free, no key), DeepL, Microsoft (with an API key) or **on the phone, offline** (Google ML Kit; a model of ~30 MB per language, downloaded once). If DeepL or Microsoft fails, it translates with Google automatically; without internet, it translates on the phone if the models for the languages are already downloaded.
- **Save / Share** (the "Share or save" button at the top): the photo with the translations on it is saved to the gallery (Pictures/Overlingo) or sent straight to Viber, WhatsApp, etc.
- **Translation windows** (Settings): text size, background opacity, light or dark windows - also applied to saved photos.
- **Voice** (Settings → "Who reads the translation"): by default the phone's built-in text-to-speech - the voice for each language is downloaded once and then works offline. Or **Microsoft voices** - more natural, free and without a key (the same as in the desktop version), with a female/male choice; the voice follows the translation language. They need internet - without a connection, or if Microsoft has no voice for the language, the phone's voice reads instead.
- **Interface:** English or Bulgarian (or the phone's language).

## Text recognition (Google ML Kit)

Text is recognized with **Google ML Kit**. The model is bundled in the APK (about 4 MB) and works **offline**; nothing is downloaded or installed separately. ML Kit is made for camera photos: it copes with tilt, curved pages and uneven light, finds paragraphs and lines on its own and is fast (under half a second).

**Limitation:** for now only **Latin-script** text is recognized (English, German, French, Spanish, Italian, Polish, Turkish…). Cyrillic (Bulgarian, Russian), Greek, Arabic etc. are not read. A separate recognizer can be added for them if needed.

## Building the APK

All you need is **Android Studio** (free: https://developer.android.com/studio).

1. Unzip the project and in Android Studio choose **File → Open → the `overlingo-android` folder**.
2. Wait for the "Gradle sync". The first time it downloads the libraries and takes a few minutes.
3. **Build → Generate App Bundles or APKs → Generate APKs** (in older versions: *Build → Build Bundle(s) / APK(s) → Build APK(s)*). If the menu is missing, open the **Gradle** panel (the elephant icon on the right) → *Overlingo → app → Tasks → build → assembleDebug*, or in the **Terminal** tab: `.\gradlew.bat assembleDebug` (Windows) / `./gradlew assembleDebug` (Linux).
4. The APK is in `app/build/outputs/apk/debug/app-debug.apk` (Android Studio shows a "locate" link).

### Installing on the phone

- **With a cable:** enable "Developer options → USB debugging" on the phone, connect it and press ▶ Run in Android Studio.
- **Without a cable:** copy `app-debug.apk` to the phone (Drive, Telegram, USB) and open it. Android will ask to allow "Install unknown apps" for the app you open it from.

### From the command line (optional)

```
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
./gradlew testDebugUnitTest    # logic tests
```

You need an `ANDROID_HOME` variable or a `local.properties` file with `sdk.dir=...`. Android Studio creates them for you.

## Structure

```
app/src/main/java/com/overlingo/android/
  MainActivity.kt          startup + "Share"
  data/                    languages, settings (DataStore)
  ocr/                     ML Kit, block cleanup, loading photos
  translate/               Google / DeepL / Microsoft / on the phone (ML Kit), cache, fallback
  export/                  drawing the translations on the photo, saving and sharing
  tts/                     reading aloud: Android TextToSpeech or Microsoft (Edge) voices
  ui/                      screens (Jetpack Compose) and MainViewModel
app/src/main/res/values*/  texts: values (EN) and values-bg (BG)
app/src/test/              logic tests (JUnit)
```
