# TeslAux

TeslAux enables Tesla steering-wheel media buttons on a Screenmate Android
device. It keeps Screenmate's local Wi-Fi Audio consumer active, receives media
commands through an Android media session, and forwards them to the active
player.

TeslAux has been tested with:

- Spotify
- Open Headunit / Android Auto
- VLC

## Requirements

- A Screenmate running Android 12 or newer
- Screenmate Wi-Fi Audio enabled
- Notification Access granted to TeslAux

## Install

1. Download the latest APK from
   [GitHub Releases](https://github.com/KalenWessel/teslaux-app/releases/latest).
2. Allow installation from unknown sources when Android prompts.
3. Install and open TeslAux.
4. Allow restricted settings for the sideloaded app:
   - Open **Settings → Apps → TeslAux**.
   - Open the three-dot menu in the upper-right.
   - Select **Allow restricted settings**.
5. Return to TeslAux, tap **Enable notification access**, and grant access.
6. Confirm Screenmate Wi-Fi Audio is enabled.
7. Tap **Verify wheel controls**, then press the steering-wheel play/pause
   button within ten seconds.

The bridge starts automatically after installation and at boot.

Android applies the restricted-settings requirement to sensitive permissions
for apps installed outside an app store. If the Notification Access switch is
disabled, confirm step 4 was completed.

## Status and troubleshooting

TeslAux only shows **Everything is working** after the Screenmate audio
connection, Notification Access, and a real wheel-button event have all been
verified.

If verification reports that no wheel event was detected, restart Screenmate.
Screenmate's privileged steering-wheel observer can become stale after Wi-Fi
Audio is disabled and re-enabled. Restarting TeslAux alone cannot repair that
observer.

Android remembers the last media-button target. When switching between Spotify,
VLC, and Android Auto, stop the previous player and start playback once in the
new player if controls continue going to the old app.

## Privacy

TeslAux connects only to Screenmate's local audio WebSocket at
`127.0.0.1:8888`. Notification Access is used to discover and control active
media sessions. TeslAux does not upload notification or playback data.

## Build

Install JDK 17 and Android SDK 35, then run:

```shell
./gradlew lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Version tags matching `v*` build a signed release APK and publish it through
GitHub Releases.

## Disclaimer

TeslAux is an independent, unofficial project. It is not affiliated with or
endorsed by Tesla, Teslogic, Screenmate, Spotify, Google, or VideoLAN.

## License

[MIT](LICENSE)
