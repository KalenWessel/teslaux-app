# TeslAux

> **Note:** This application is a companion app for the
> [TeslAux project](https://github.com/jbschooley/TeslAux/tree/main). It makes
> TeslAux more usable with Screenmate because, without this app, the Tesla
> steering wheel media buttons do not control playback.

TeslAux enables Tesla steering-wheel media buttons on a Screenmate Android
device. It keeps Screenmate's local Wi-Fi Audio consumer active, receives media
commands through an Android media session, and forwards them to the active
player.

TeslAux has been tested with:

- Spotify
- Open Headunit / Android Auto
- VLC

## Requirements

- A Screenmate
- Screenmate Wi-Fi Audio enabled
- Notification Access granted to TeslAux

## Install

Install TeslAux through Obtainium. Its session-based installer allows
Notification Access without ADB, unlike opening a downloaded TeslAux APK
directly on Screenmate.

1. On Screenmate, open the
   [latest Obtainium release](https://github.com/ImranR98/Obtainium/releases/latest).
2. Download and install `app-arm64-v8a-release.apk`. Allow installation from
   unknown sources if Android prompts.
3. Open Obtainium and select **Add App**.
4. Enter `https://github.com/KalenWessel/teslaux-app` as the app source.
5. Add TeslAux, then install its latest release through Obtainium.
6. Open TeslAux, tap **Enable notification access**, and grant access.
7. Confirm Screenmate Wi-Fi Audio is enabled.
8. Tap **Verify wheel controls**, then press the steering-wheel play/pause
   button within ten seconds.

Obtainium can also detect and install future TeslAux releases. The bridge starts
automatically after installation and at boot.

Do not install the TeslAux APK directly from a browser or file manager.
Android 14 marks Notification Access as restricted for that installation
method, and Screenmate's customized Settings app does not expose the standard
**Allow restricted settings** option.

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

TeslAux is not affiliated with Tesla, Teslogic, or Screenmate.

## License

[MIT](LICENSE)
