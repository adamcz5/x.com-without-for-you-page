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

## Status: on hold

The Accessibility APK builds fine (CI is green) but could not be installed on the author's phone:

- Play Protect blocks it ("App blocked to protect your device"), because the app requests Accessibility access.
- The phone has a work profile managed by Microsoft Intune (Company Portal). Its work policy forces Play Protect scanning on and the setting cannot be turned off ("Blocked by work policy").
- Bypassing an employer's device policy is not an option, so the project is parked.

### Ways forward
| Option | Native X app | Notes |
|---|---|---|
| Accessibility APK (this repo) | Yes | Needs a phone without the work-policy block, or the work profile removed (deletes work apps/data; re-enrolling brings the policy back; check with IT first) |
| Browser userscript (`web/`) | No, website | Firefox + Violentmonkey from the Play Store; no policy exception needed; untested |
| WebView wrapper APK (not built) | No, website | Asks for no sensitive permissions, so it might pass Play Protect; may still hit a "no sideloading" work policy |
| Another Android phone | Yes | Install the APK directly |

Not yet tested on any device: label matching and overlay colour in `ForYouBlockerService.kt` will likely need tweaks.
