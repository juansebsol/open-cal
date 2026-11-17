> **v0.2 TV layout update:** Fixed clipping/overlapping on 720p and 1080p screens, compacted QR pairing, removed marketing/footer text. Rebuild and use `adb install -r` to preserve an existing pairing.

# Room Calendar TV — local Google Calendar display

An **Android TV / Amazon Fire TV sideloadable app** that shows a clean, day-first Google Calendar dashboard. Read-only. No proprietary backend, database, paid service, hosted web app, OAuth credentials, or calendar account registration.

**Status:** working MVP source project intended for on-device testing. The source has not been tested on a physical Fire TV. Compiling the APK requires Android Studio / Android SDK (see below).

## What it does

- Bundled HTML/CSS/JavaScript daily agenda with big-screen layout, clock, mini month, next meeting, and day navigation.
- Pulls events directly from Google Calendar's **Secret address in iCal format** over HTTPS, every 15 minutes while the app is open.
- Handles regular events, all-day events, common repeating RRULE patterns (daily, weekly, monthly, yearly), EXDATE, RDATE, and overridden occurrences.
- Stores private calendar URL and cached events encrypted using the TV's Android Keystore. No SQLite/database required.
- TV hosts a short-lived local QR pairing page on `http://TV_LOCAL_IP:8765/connect?...` while pairing. The page is hosted by the APK itself, **not by the developer**.
- A TV-remote input field is available if QR pairing fails. Preview mode shows clearly labeled sample events.
- Remote controls: select top controls with D-pad, OK to press; remote Menu opens settings; rewind/fast-forward changes day; Home exits the application.

## Important security note

Google's secret iCal URL grants read-only access to your calendar without OAuth. **Treat the URL like a password.** The app stores it encrypted on the TV and fetches from Google using HTTPS. However, the *initial local QR pairing page uses plain HTTP on your Wi-Fi network*. Anyone with access to sniff traffic on that network could potentially capture the address while you submit it. Pair only on a trusted private Wi-Fi. For stronger pairing security, use the TV remote input or extend the app with TLS/secure pairing. There is no cloud proxy or analytics service.

The pairing QR expires after 15 minutes. Settings can re-open pairing. Resetting a secret URL in Google Calendar invalidates its old copy.

## Browser UI preview

To inspect the layout without a TV, open the separately included `RoomCalendar-TV-Preview.html` in a browser. This is **just a demo** with placeholder events; actual Google iCal syncing requires the APK.

## Build on GitHub Actions (no Android Studio needed)

If you put this source folder in a GitHub repository and enable Actions, run the `Build Android TV APK` workflow. It builds an Android debug APK and publishes it as a downloadable workflow artifact. No app backend or deployment server is needed.

## Set up your Google Calendar URL

On **a desktop browser**, go to Google Calendar > Settings > Settings for my calendars > **choose your calendar** > Integrate calendar > **Secret address in iCal format** > Copy. This is not the public address. Some Google Workspace administrators disable secret iCal links.

**Only `https://calendar.google.com/calendar/ical/... .ics` URLs** are accepted in this MVP. You can add multiple feeds or other providers in a later version.

## Build & install using Android Studio

1. Install Android Studio and let it install Android SDK Platform 35. Open this folder as an Android project. A small, source-included Gradle bootstrap is bundled; on first sync it downloads and SHA-256-verifies the official Gradle 8.9 distribution.
2. Wait for Gradle sync to finish. This project uses Android Gradle Plugin 8.7.3 and the ZXing QR Core dependency. Alternatively run `./gradlew assembleDebug` from Terminal.
3. In Android Studio: **Build > Build APK(s)** (debug build). The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
4. On an Android-based TV (Android 8.0+, API 26), enable developer options and USB/Network ADB debugging.
5. With ADB installed on your computer, run:

```sh
adb connect YOUR_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.roomcalendar.tv/.MainActivity
```

On Fire TV, ADB settings and sideloading availability vary by OS and model. **Vega OS does not run Android APKs.**

> `release` is temporarily signed with the debug key for local testing only. Do not publish it as a production build with this signing setup.

## Alexa integration

This APK does **not** register a custom Alexa voice skill and cannot guarantee that Alexa will discover a sideloaded app by its name.

A basic Alexa routine can turn a compatible Fire TV on/off (depending on device integration and HDMI-CEC), but automatic launching of this sideloaded APK may need an external device controller such as Home Assistant with ADB, which can execute:

```sh
adb shell am start -n dev.roomcalendar.tv/.MainActivity
```

For a 15-minute power-off, configure that in Alexa or Home Assistant, not within this app. Powering off the physical TV from an ordinary Android app is not generally supported. Do not configure unconditional shutdown if you also use the TV for video; a smart automation should verify the calendar app is still foregrounded.

## Architecture

```
Android TV app (Java Activity)
  ├── Android WebView loads bundled assets/index.html (NO remote webpage)
  ├── QR pairing HTTP server (TV LAN only, ephemeral)
  ├── Calendar HTTPS fetch and iCal parser (device only)
  └── Android Keystore encrypted preferences (device only)
```

App is intentionally smaller and more dependency-free than a React/Capacitor app. It uses only one third-party Android library, ZXing Core, to generate QR codes. It does not require Google APIs, Firebase, OAuth, Node.js, or any server. It needs the TV to have internet for fresh calendar events.

## Known MVP limitations

- Not verified on your specific TV or physical Fire TV. Some manufacturers omit/incompatibly update WebView.
- First public builds should upgrade the `targetSdk` and security configuration after TV compatibility testing.
- ICAL RRULE parser covers common repeat patterns, but complex rules (`BYSETPOS`, `WKST`, intricate timezone transitions, unusual `VTIMEZONE`) may not render perfectly. A full RFC-compliant library is recommended before public release.
- Google's private iCal feeds are not guaranteed to update in real time. This APK refreshes every 15 minutes, but Google may cache the feed longer.
- One Google calendar feed at a time. No two-way event editing; no Google account login.
- Pairing uses HTTP within your home LAN. Use only a trusted network or manual remote entry.
- TV power-off and Alexa voice invocation are external integrations, not features provided by this APK.

## Build bootstrap provenance

`gradle/wrapper/gradle-wrapper.jar` is a **small custom bootstrap**, not the official Gradle wrapper jar. Its complete Java source is in `tools/wrapper/GradleWrapperMain.java`. It fetches only the official Gradle 8.9 binary distribution and verifies SHA-256 against the upstream published checksum before extracting or running it. You can replace it with the official Gradle wrapper if you prefer.
