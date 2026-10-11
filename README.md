# X without the For You page (Android, personal use)

An Android Accessibility Service that makes the real X app (`com.twitter.android`) unusable on the
"For you" tab: it covers the tab with an overlay that swallows taps and taps "Following" whenever
"For you" is selected. No separate X client; you keep using the normal app.

## Build
CI builds a debug APK on every push (`.github/workflows/apk.yml`); download the
`no-for-you-apk` artifact from the Actions run. Locally: `gradle assembleDebug` (needs the Android SDK).

## Install
1. Sideload `app-debug.apk` (allow "install unknown apps").
2. Open **No For You** → **Enable** → turn the service on under Accessibility.
3. Open X as usual.

## Known limits
- Matches the tab by its accessibility label ("For you" / "Following", English UI). Other languages
  or a relabelled tab need a tweak in `ForYouBlockerService.kt`.
- Not yet tested on a device: expect to adjust label matching and overlay colour after first run.
- The service sees events from all apps but only checks the foreground package name; it reads
  screen content only while X is in front.
