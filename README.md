# Muzi Music (native Android app)

100% native Kotlin using only the Android platform APIs. No WebView, no third-party libraries.

## Build with GitHub
1. Upload everything in this folder to a GitHub repository (keep the `.github` folder).
2. Open the **Actions** tab, pick **Build Muzi APK**, press **Run workflow**.
3. When it turns green, download **Muzi-debug-apk** under Artifacts and install `app-debug.apk`.

## Folders
- `app/src/main/java/com/logman/muzi` - all app code
  - `MainActivity.kt` screens, swipes, dialogs, permissions
  - `Pages.kt` now playing, library and settings screens
  - `Engine.kt` the music player (MediaPlayer, equalizer, audio focus)
  - `PlaybackService.kt` background playback, notification, lock-screen controls
  - `Data.kt` library, scanning, imports, settings storage
  - `PlayerWidgetProvider*.kt` home-screen widgets
  - `Covers.kt` album art and cover loading utilities
  - `Theme.kt` theme and styling definitions
  - `Util.kt` helper and utility functions
- `branding/` your icon and loading-screen pictures (used by the build)
- `scripts/make_branding.py` creates the launcher icons from `branding/`
