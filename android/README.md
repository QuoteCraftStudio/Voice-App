# Voice Reader — QuoteCraft Studio (Android native background reading)

Version: 2026.10.20.1. Android min SDK 26, target SDK 35, Java 17.

## Open and build

1. On Windows, install Android Studio (including SDK Platform 35 and Build Tools) if not already installed.
2. Extract this folder and open the `Voice-Reader-Android-Project` folder **as a project** in Android Studio.
3. Allow Gradle sync. Android Studio may download the Android Gradle Plugin 8.6.1 and matching Gradle 8.7. Use JDK 17.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The *debug* installer will appear in `app/build/outputs/apk/debug/app-debug.apk`.
6. Transfer that APK to the Android phone and test opening a document, playing, locking, pausing/resuming via the notification, switching applications, and phone call/audio-focus interruptions.

This project intentionally contains **no signing credentials or API keys**. To distribute a release APK use Android Studio's **Generate Signed Bundle / APK**, preferably reusing the existing Voice Reader signing key. Installing a package with a different signing key over an older installation may fail; make a backup/export of your saved publications before uninstalling anything.

## Architecture

- `voice-reader.html`: original reader, with native reader bridge and web fallback.
- `VoiceReaderActivity`: WebView hosted at the private `https://appassets.androidplatform.net/` synthetic origin (supports IndexedDB), native voice enumeration and picker, transfer reader text to private app cache.
- `VoiceReaderService`: standalone Android media-playback foreground service, native TTS and MediaSession with a user-visible notification, audio focus, pause/resume/stop, and reading position persistence.
- Android speech reads a prepared document fully inside the foreground service; WebView may pause/sleep while the service continues.
- The website retains Web Speech API and an optional wake-lock control; the website **cannot guarantee** playback with a locked screen.

PDF and DOCX import rely on external cdn.jsdelivr.net scripts, as in the user's original HTML. Those imports need an internet connection. PDF pages are normally loaded in 20-page batches; for full-book lock-screen playback, use the Android preloading setting introduced by the updated HTML.

## Status

The HTML script passed Node.js syntax validation in the ChatGPT environment. The Android project source has NOT been built, installed, or tested on a phone in this environment because neither Android SDK nor Gradle is available. Do not describe this package as a verified APK.

## Privacy / safety

No telemetry or model API key added. User documents remain in private browser storage and are temporarily copied to private cache for native TTS. Files in private cache are removed after loading. App has INTERNET (for existing document import), FOREGROUND_SERVICE and MEDIA_PLAYBACK permissions. Microphone and device-wide file scanning are not requested.
