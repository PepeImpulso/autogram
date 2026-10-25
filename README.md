# Autogram

Hands-free Telegram voice messages for Android Auto.

Tap a button in the car, speak, and your voice message is sent on Telegram.
Replies are read aloud through the car speakers, so you never have to look at
a screen or type. The same voice flow works on the phone with push-to-talk.

> **Independent project.** Autogram is not affiliated with, endorsed by, or
> sponsored by Telegram. "Telegram" is a trademark of its respective owner.

## Features

- One-tap voice recording from Android Auto, or push-to-talk on the phone
- Voice messages are sent as OGG/Opus
- Incoming replies play automatically (voice messages, and text via text-to-speech)
- Quick replies, message history, silent mode, and call-aware playback
- Automatic retries when the network drops
- Signs in to your own Telegram account through [TDLib](https://github.com/tdlib/td)

*Screenshots: none yet.*

## Requirements

- Android 10 (API 29) or newer
- A Telegram account
- Android Auto, for use in the car

## Install

Autogram is distributed **only on GitHub**. There is no Google Play version.

1. On your phone, open the
   [latest release](https://github.com/PepeImpulso/autogram-releases/releases/latest).
2. Download the APK and open it. Allow your browser or file manager to install
   unknown apps when Android asks.
3. Open Autogram and sign in with your Telegram account.

## Updates

Updates come only from the in-app updater, which checks the same GitHub
releases: **Settings → Updates**. Nothing else will update the app.

## Android Auto setup

Android Auto hides apps that were not installed from the Play Store unless
developer mode allows them. Without this, Autogram will not appear in the car.

1. Open **Android Auto** in your phone's settings.
2. Scroll to the bottom and tap **Version** about 10 times, then confirm to
   enable developer mode.
3. Open the **⋮** menu → **Developer settings**.
4. Turn on **Unknown sources**.
5. Reconnect your phone to the car. Autogram should now be listed with your
   media and messaging apps.

## Build from source

See [docs/BUILDING.md](docs/BUILDING.md). Forks must register their own
Telegram `api_id` and `api_hash`.

## Privacy

- Autogram talks directly to Telegram using TDLib. There is no Autogram server
  in between, and the developers do not receive your messages.
- Your Telegram session and messages are stored on your device.
- Crash reporting through Sentry is optional.
- If you choose to configure an external AI summary endpoint, the text you ask
  to summarize is sent to that endpoint. The app asks you to confirm this first.

## License

Autogram is licensed under the [GNU General Public License v3.0](LICENSE).
Third-party components, including TDLib, are listed in [NOTICE](NOTICE).
