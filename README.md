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

## Automatic builds (GitHub Actions)

`.github/workflows/build.yml` builds the APK on GitHub, like the desktop version:

- **Publishing a release** with the tag `v` + `versionName` (e.g. `v1.8`): it runs the tests, builds a **signed** APK and attaches it to the release as `Overlingo-1.8-android.apk`.
- **Actions → Build APK → Run workflow**: the same, without a release - the APK is in the run's *Artifacts* (a debug APK if the signing secrets aren't set).

### One-time setup: the signing key

Android only installs an update over an app signed with the **same key**, so the release APKs need one permanent key.

1. Create it (PowerShell; `keytool` comes with Android Studio). It asks for a password and a name:
   ```
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore overlingo-release.jks -alias overlingo -keyalg RSA -keysize 2048 -validity 10000
   ```
   Keep `overlingo-release.jks` **outside the project folder** and back it up (with the password). If it's lost, updates can't be installed over the existing app.
2. Encode it for GitHub:
   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("overlingo-release.jks")) | Set-Content keystore-base64.txt
   ```
3. On GitHub: **Settings → Secrets and variables → Actions → New repository secret**, four times:
   - `OVERLINGO_KEYSTORE_BASE64` - the contents of `keystore-base64.txt`
   - `OVERLINGO_KEYSTORE_PASSWORD` - the keystore password
   - `OVERLINGO_KEY_ALIAS` - `overlingo`
   - `OVERLINGO_KEY_PASSWORD` - the key password (the same, unless you set a different one)

   Then delete `keystore-base64.txt`.

**Switching from a debug APK:** the APK from Android Studio is signed with a different (debug) key, so the first signed APK installs only after uninstalling the old app once (its settings and downloaded models go with it). After that, every new release installs as an update.

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
