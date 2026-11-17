# Open Cal

An Android TV / Fire TV app that shows a Google Calendar day view on a TV. It reads the calendar's secret iCal address directly, so there is no backend, no OAuth and no Google API key.

- Day timeline with overlapping events, all-day events, a current-time line, a month view and the next upcoming event
- Syncs on launch and every 15 minutes while open; the last sync is cached for offline use
- Pairs by QR code: the TV serves a one-time form on the local network that you open on your phone
- Feed URL and cached events are encrypted with an Android Keystore key

## Requirements

- JDK 17
- Android SDK with Platform 35 (installed by Android Studio). Set `ANDROID_HOME`, or add `sdk.dir` to `local.properties`.
- `adb` (Android SDK platform-tools)
- A TV running Android TV, Google TV or Fire OS on Android 8.0 (API 26) or later. Fire TV devices on Vega OS cannot run APKs.

## Build

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also open the project in Android Studio and run the `app` configuration.

GitHub Actions (`.github/workflows/build.yml`) builds the same APK on every push to `main` and every pull request, and uploads it as the `open-cal-debug-apk` artifact.

## Install on a TV

1. Turn on developer options and ADB debugging on the TV. Menu names vary by device.

   | Platform | Unlock developer options | Turn on ADB |
   | --- | --- | --- |
   | Fire TV | Settings → My Fire TV → About → select the device name 7 times | Settings → My Fire TV → Developer Options → ADB Debugging |
   | Android TV / Google TV | Settings → System → About → select *Android TV OS build* 7 times | Settings → System → Developer options → USB debugging |

2. Find the TV's IP address under its network settings, then connect. Accept the debugging prompt on the TV.

   ```bash
   adb connect TV_IP:5555
   ```

3. Install the APK.

   ```bash
   ./gradlew installDebug
   ```

   Or install a prebuilt APK:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed copy was signed with a different debug key (a different machine or CI). Uninstall it first; you will need to pair the calendar again.

   ```bash
   adb uninstall dev.opencal.tv
   ```

4. Launch. The app also appears in the TV's app list. The same command works from home-automation tools that can run ADB commands.

   ```bash
   adb shell am start -n dev.opencal.tv/.MainActivity
   ```

## Connect a calendar

1. In Google Calendar on the web, open **Settings → (your calendar) → Integrate calendar** and copy **Secret address in iCal format**. Google Workspace admins can disable this option.
2. On the TV, scan the QR code with a phone on the same Wi-Fi network, paste the address and submit. If QR pairing isn't available, type the address into the field on the TV with the remote.

Only `https://calendar.google.com/calendar/ical/….ics` addresses are accepted. To switch calendars, open settings and select **Connect another calendar**.

## Remote controls

| Key | Action |
| --- | --- |
| D-pad, OK | Move between buttons and press them |
| Menu | Open or close settings |
| Rewind / Fast-forward | Previous / next day |
| Back | Close settings, return to today, or leave the pairing screen |
| Home | Leave the app |

## Development

### Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/dev/opencal/tv/MainActivity.java` | Hosts the WebView, the JS bridge, the sync loop and remote key handling |
| `app/src/main/java/dev/opencal/tv/IcalReader.java` | Downloads the feed and parses events, including recurrences |
| `app/src/main/java/dev/opencal/tv/PairingServer.java` | Temporary HTTP server that serves the QR pairing form |
| `app/src/main/java/dev/opencal/tv/Secrets.java` | Keystore-backed encrypted preferences |
| `app/src/main/assets/` | The UI: `index.html`, `app.js`, `style.css` |

The only third-party dependency is ZXing Core, which draws the QR code.

### Native ↔ web bridge

The UI is a static page loaded from the APK's assets. `MainActivity` blocks all navigation away from it.

- **JS → native:** `window.OpenCalNative` exposes `ready()`, `refresh()`, `pair()`, `cancelPairing()`, `save(url)`, `disconnect()`, `preview()` and `exitPreview()`.
- **Native → JS:** every state change calls `OpenCalUI.receive(state)` with the full state: `paired`, `hasFeed`, `demo`, `pairingUrl`, `qr`, `events`, `loading`, `error`, `lastSynced` and `timeZone`. Each event is `{id, title, start, end, allDay, location}`, with times in epoch milliseconds.

### Working on the UI in a browser

Open `app/src/main/assets/index.html` in Chrome. Without the native bridge the page renders sample events. Add `?pair=1` to the URL to see the pairing screen.

### Release builds

`./gradlew assembleRelease` signs with the debug key so it can be sideloaded. Configure a real signing key before distributing the app.

## Security

- The secret iCal address grants read-only access to the calendar. Treat it like a password; resetting it in Google Calendar revokes old copies.
- The app stores the address and cached events encrypted (AES-GCM, key held in Android Keystore). App backups are disabled.
- Feed requests go only to `calendar.google.com` over HTTPS, and every redirect is checked against the same rule.
- The pairing form is plain HTTP on port 8765. It needs a random one-time token, accepts one submission and closes after 15 minutes. Anyone who can see traffic on the local network during pairing could read the address, so pair on a trusted network or type the address with the remote.

## Limitations

- Shows one calendar, read-only.
- Loads events from 7 days back to 45 days ahead.
- Supports repeating events with `DAILY`, `WEEKLY`, `MONTHLY` or `YEARLY` frequency and `INTERVAL`, `COUNT`, `UNTIL`, `BYDAY`, `BYMONTHDAY` and `BYMONTH`. Also supports `EXDATE`, `RDATE` and moved single occurrences (`RECURRENCE-ID`). `BYSETPOS`, `WKST`, `BYWEEKNO` and `BYYEARDAY` are not supported.
- Google can take several hours to update a secret iCal feed, so recent edits may not show up at the next sync.
- `targetSdk` is 28, which is fine for sideloading but too old for app-store distribution.
