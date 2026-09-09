# NoShorts

A standalone Android app that kills YouTube Shorts **without modifying, patching or replacing the YouTube app**. YouTube stays the stock Play Store build and keeps updating normally.

## What it actually does

Stock Android gives no way to inject code into another app's process, so nothing can stop Shorts from *existing* inside YouTube. What this app does instead, from the outside:

| Behaviour | How |
|---|---|
| Shorts player closes itself | An `AccessibilityService` sees the reel view ids appear and presses Back **once**, then waits ~900ms and re-checks before pressing again. The wait matters: YouTube keeps reel views in the tree for its whole exit animation, so reacting to every matching event fires extra Back presses that pop the screens *behind* Shorts and walk you out of the app. |
| Shorts shelves vanish from feeds | Black, **touch-transparent** overlays drawn over the shelf bounds. The feed still scrolls normally under them. |
| `youtube.com/shorts/…` links | Intercepted and rewritten to `youtube.com/watch?v=…`, so a shared Short opens in the regular player. Or open in the browser, or drop it entirely. |

In practice: tapping the Shorts tab flashes for a fraction of a second and dumps you back where you were.

## Privacy

`accessibility_service_config.xml` pins `android:packageNames="com.google.android.youtube"`. The framework will not deliver this service events from any other app, so it is structurally incapable of observing anything you do outside YouTube. There is no network permission in the manifest — nothing can leave the device.

## Build

Needs JDK 17 and the Android SDK (compileSdk 34, build-tools 34).

```bash
# in Android Studio: File > Open > this folder, then Run.
# or from a shell:
gradle wrapper          # once, to generate gradlew + the wrapper jar
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` also works and is signed with the debug key, so it sideloads directly. Swap in a real keystore in `app/build.gradle.kts` if you ever want to distribute it.

## Install and set up

1. Sideload the APK (`adb install -r app-debug.apk`, or copy it over and tap it).
2. Open NoShorts, tap **Enable in Accessibility settings**, find *NoShorts blocker* under Installed apps / Downloaded services, turn it on. Android will warn you about full device access — that warning is generic to all accessibility services; the package restriction above is what actually scopes it.
3. For the feed shelves, tap **Allow drawing over other apps**.
4. For link interception, tap **Open app settings**, then *Open by default* → *Add link* → tick the `youtube.com` entries. (Android 12+ won't let an app grab verified links silently; this step is unavoidable and is a one-time thing.)

Some OEM ROMs (Xiaomi, Samsung, Oppo) kill accessibility services on reboot or under battery optimisation. If blocking stops after a reboot, exclude NoShorts from battery optimisation.

## When a YouTube update slips past it

YouTube renames view ids between releases. If Shorts start getting through:

1. Turn on **Log view ids** in the app.
2. Open Shorts, then run `adb logcat -s NoShorts`.
3. Add the new prefixes to `PLAYER_ID_PREFIXES` (full-screen player) or `SHELF_ID_PREFIXES` (feed rows) in `app/src/main/java/com/noshorts/blocker/ShortsSignals.kt` and rebuild.

Matching is by prefix on the part after `id/`, so `reel_player` covers `reel_player_page_container`, `reel_player_overlay` and friends. Keep anything that can appear in a normal feed **out** of `PLAYER_ID_PREFIXES` — a false positive there would bounce you out of the home feed.

## Layout

```
app/src/main/
  AndroidManifest.xml
  java/com/noshorts/blocker/
    ShortsBlockerService.kt   detection, Back/Home escapes, shelf overlays
    ShortsSignals.kt          the view-id fingerprints (edit this one)
    LinkRedirectActivity.kt   /shorts/<id> -> /watch?v=<id>
    MainActivity.kt           toggles and permission shortcuts
    Prefs.kt                  SharedPreferences wrapper
  res/...
```

No third-party dependencies — plain framework widgets only.
