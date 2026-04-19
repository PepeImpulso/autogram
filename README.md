# Autogram

Voice-based bidirectional messaging for Android Auto via Telegram.

Tap a button, speak, and your voice note is delivered to Telegram. When a reply comes, it plays automatically through your car speakers. Zero screen reading, zero typing.

## Features (v1)

- 🎤 **One-tap voice recording** from Android Auto
- 📨 **Send voice notes** to Telegram (OGG/OPUS)
- 🔊 **Auto-play incoming replies** (voice + TTS for text)
- ⚡ **Quick replies** — preset messages via buttons
- 📵 **Empty message filter** — discards accidental recordings
- 📞 **Call-aware** — suppresses playback during calls
- 🔇 **Silent mode** — pause auto-play when needed
- 📋 **Message history** on phone

## Setup

### Prerequisites
- Android Studio (Hedgehog or newer)
- Android device with API 29+ (Android 10)
- Android Auto compatible head unit or [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu)
- Telegram account

### Telegram Bot Setup
1. Open Telegram, search for `@BotFather`
2. Send `/newbot` and follow instructions
3. Save the **bot token** (e.g., `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)
4. Get your **chat ID**: send a message to your bot, then visit:
   `https://api.telegram.org/bot<TOKEN>/getUpdates`
5. Look for `chat.id` in the response

### Build & Install
```bash
git clone https://github.com/erpepebot/Autogram.git
cd Autogram
./gradlew assembleDebug
```
Install the APK on your device and connect to Android Auto.

### First Run
1. Open Autogram on your phone
2. Go to Settings
3. Enter your **Bot Token** and **Chat ID**
4. Tap **Test Connection** to verify

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Android Auto UI │────▶│   Domain Layer   │────▶│  Infrastructure  │
│  (Car App Lib)   │     │  (Use Cases,     │     │  (Telegram,      │
│                  │     │   State Machine)  │     │   Audio, Room)   │
└─────────────────┘     └──────────────────┘     └─────────────────┘
```

Clean Architecture with 3 layers: Interface → Domain → Infrastructure.

See `docs/` for detailed documentation.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| Min SDK | 29 (Android 10) |
| UI (Phone) | Jetpack Compose |
| UI (Auto) | Car App Library |
| DI | Hilt |
| Network | Retrofit + OkHttp |
| Database | Room |
| Background | ForegroundService + WorkManager |
| Audio | MediaRecorder (OGG/OPUS) + MediaPlayer |
| Security | EncryptedSharedPreferences |

## Documentation

- [01 — Critical Analysis](docs/01-critical-analysis.md)
- [02 — Functional Planning](docs/02-functional-planning.md)
- [03 — Architecture](docs/03-architecture.md)
- [04 — Data Model](docs/04-data-model.md)
- [05 — Risks](docs/05-risks.md)
- [06 — Development Plan](docs/06-development-plan.md)

## Status

🚧 **Phase 0 — Technical Prototype** (in progress)

## License

Personal use project. All rights reserved.
