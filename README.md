# TeslAux

> **Note:** This application is a companion app for the
> [TeslAux project](https://github.com/jbschooley/TeslAux/tree/main). It keeps
> the TeslAux USB audio output at full volume on a Screenmate.

TeslAux sets the Android music volume to maximum whenever the TeslAux USB audio
device is the output. Android stores a music volume per output device, and a
Screenmate comes up at the default after a reboot, which is quieter than the car
needs. The app watches for the TeslAux sound card and sets the volume when it
appears, including the reconnection a few seconds after boot.

Steering-wheel media buttons are handled by Screenmate itself (1.1.0b and
later), so this app no longer bridges them.

## Requirements

- A Screenmate
- A TeslAux USB audio device

## Install

Install TeslAux through Obtainium, so it can detect and install future
releases.

1. On Screenmate, open the
   [latest Obtainium release](https://github.com/ImranR98/Obtainium/releases/latest).
2. Download and install `app-arm64-v8a-release.apk`. Allow installation from
   unknown sources if Android prompts.
3. Open Obtainium and select **Add App**.
4. Enter `https://github.com/KalenWessel/teslaux-app` as the app source.
5. Add TeslAux, then install its latest release through Obtainium.

The background service starts automatically after installation and at boot.

## Status and troubleshooting

Open the app to see whether the TeslAux audio device is connected and what the
music volume is. **Set volume now** applies the maximum immediately.

If the volume stays low, check that the TeslAux sound card is the active output:
the app only sets the volume for that device, not for the speaker.

## Privacy

TeslAux has no network code. It reads the audio device list and the music volume
through the Android audio service, and changes nothing else.

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
