# Autogram — Technical Architecture

## 1. Architecture Overview

Clean Architecture with 3 layers:
- **Interface** — Android Auto UI, phone settings UI, notifications
- **Domain** — use cases, state machine, business rules
- **Infrastructure** — Telegram API, audio recording/playback, Room DB, WorkManager

```
┌─────────────────────────────────────────────────┐
│                  INTERFACE LAYER                 │
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │CarAppSvc │  │Settings  │  │Notifications  │  │
│  │(AA UI)   │  │Activity  │  │Manager        │  │
│  └────┬─────┘  └────┬─────┘  └───────┬───────┘  │
├───────┼──────────────┼────────────────┼──────────┤
│       │        DOMAIN LAYER           │          │
│  ┌────▼──────────────▼────────────────▼───────┐  │
│  │              Use Cases                      │  │
│  │  SendMessage / ReceiveMessage / PlayReply   │  │
│  │  RecordAudio / FilterEmpty / ManageHistory  │  │
│  ├────────────────────────────────────────────┤  │
│  │          State Machine (Message)            │  │
│  │          Repositories (interfaces)          │  │
│  └────────────────┬───────────────────────────┘  │
├───────────────────┼─────────────────────────────┤
│           INFRASTRUCTURE LAYER                   │
│  ┌────────────────▼───────────────────────────┐  │
│  │  TelegramClient  │  AudioRecorder          │  │
│  │  (Bot API HTTP)  │  (MediaRecorder/OPUS)   │  │
│  ├──────────────────┤─────────────────────────┤  │
│  │  AudioPlayer     │  MessageRepository      │  │
│  │  (ExoPlayer)     │  (Room DB)              │  │
│  ├──────────────────┤─────────────────────────┤  │
│  │  PollingService  │  RetryManager           │  │
│  │  (ForegroundSvc) │  (WorkManager)          │  │
│  └──────────────────┴─────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## 2. Module Diagram

```
autogram/
├── app/                          # Main Android application
│   ├── domain/                   # Pure Kotlin — no Android deps
│   │   ├── model/                # Entities: Message, Conversation, etc.
│   │   ├── state/                # Message state machine
│   │   ├── usecase/              # Use cases (SendMessage, etc.)
│   │   └── repository/           # Repository interfaces
│   ├── infrastructure/
│   │   ├── telegram/             # Telegram Bot API client
│   │   ├── audio/                # Recorder + Player
│   │   ├── persistence/          # Room DB, DAOs
│   │   ├── retry/                # WorkManager retry logic
│   │   └── platform/             # Call state, audio focus, etc.
│   ├── interface/
│   │   ├── auto/                 # Android Auto CarAppService + screens
│   │   ├── phone/                # Settings Activity, History Activity
│   │   └── notification/         # Foreground service notification
│   └── service/
│       └── AutogramPollingService.kt  # ForegroundService for polling
└── build.gradle.kts
```

## 3. Core Flows

### 3.1 Send Voice Message

```
User taps Record on AA
    │
    ▼
CarAppService → SendMessageUseCase.startRecording()
    │
    ▼
AudioRecorder.start() [MediaRecorder, OGG/OPUS]
    │
    ▼ (user taps Stop or silence detected after 2s)
AudioRecorder.stop() → file saved
    │
    ▼
EmptyMessageFilter.validate(file)
    │── Invalid → show "Too short" → IDLE
    ▼ (Valid)
Message created (state: RECORDING → RECORDED → VALIDATING → QUEUED)
    │
    ▼
TelegramClient.sendVoice(chatId, file)
    │
    ▼ (on success)
Message state → SENT → update UI "Sent ✓"
    │
    ▼ (on failure)
Message state → FAILED → RetryManager.enqueue(messageId)
                 → update UI "Error ✗"
```

### 3.2 Receive & Auto-Play Reply

```
AutogramPollingService (ForegroundService, always running)
    │
    ▼ (every 3s)
TelegramClient.getUpdates(offset)
    │
    ▼ (new message from target chat)
Parse: text? → TTS path | voice? → download file
    │
    ▼
Message created (state: RECEIVED)
    │
    ▼
CallStateChecker.isInCall()?
    │── Yes → state: PENDING_PLAYBACK → queue
    ▼ (No)
AudioFocusManager.requestFocus()
    │
    ▼
AudioPlayer.play(file) OR TextToSpeech.speak(text)
    │
    ▼
State: PLAYING → PLAYED
    │
    ▼
UI update: "Reply from [chat]"
```

### 3.3 Network Error

```
TelegramClient.sendVoice() throws IOException
    │
    ▼
SendMessageUseCase catches → Message state: FAILED
    │
    ▼
RetryManager.schedule(messageId, delay=30s, maxAttempts=3)
    │
    ▼
WorkManager enqueues OneTimeWorkRequest
    │
    ▼ (backoff: 30s, 60s, 120s)
On success → state: SENT
On all retries exhausted → state: PERMANENTLY_FAILED → notify user
```

### 3.4 Empty Message Filter

```
AudioRecorder.stop() → file
    │
    ▼
EmptyMessageFilter:
  1. duration < 1.5s → REJECT
  2. RMS amplitude < threshold → REJECT (silence)
  3. Otherwise → ACCEPT
    │
    ▼ (REJECTED)
Show "Empty message discarded" → state: DISCARDED → IDLE
```

### 3.5 Call in Progress

```
PhoneStateListener detects CALL_STATE_OFFHOOK
    │
    ▼
PlaybackManager.pauseCurrent()
    │
    ▼
Queue incoming messages (state: PENDING_PLAYBACK)
    │
    ▼ (call ends)
PlaybackManager.playPendingQueue()
```

### 3.6 Silent Mode

```
User toggles Silent Mode ON (from AA or phone)
    │
    ▼
Settings.silentMode = true
    │
    ▼
Incoming messages → state: PENDING_PLAYBACK (no auto-play)
    │
    ▼
UI shows badge: "N replies waiting"
    │
    ▼ (user toggles OFF)
Play all pending in order
```

## 4. Message State Machine

```
         ┌─────────────────────────────────────────────┐
         │                                             │
    [IDLE]──tap──▶ [RECORDING]──stop──▶ [RECORDED]    │
         ▲                          │                  │
         │                     validate                │
         │                          │                  │
         │              ┌───────────┴────────┐         │
         │              ▼                    ▼         │
         │         [DISCARDED]          [QUEUED]       │
         │              │                    │         │
         │           → IDLE            send/HTTP       │
         │                                 │           │
         │                     ┌───────────┴────┐      │
         │                     ▼                ▼      │
         │                 [FAILED]          [SENT]    │
         │                     │                │      │
         │               retry (≤3x)        delivered  │
         │                     │                │      │
         │            ┌────────┴──┐            ▼      │
         │            ▼           ▼        [DELIVERED] │
         │    [QUEUED(retry)] [PERM_FAILED]   │        │
         │                                       │        │
         │                            reply received     │
         │                                       │        │
         ┼───────────────────────────────────────┘        │
         │                                                │
    [RECEIVED]──can play──▶ [PLAYING]──done──▶ [PLAYED]──┘
         │                      │
         │ (call/silent)        │ (user pause)
         ▼                      ▼
    [PENDING_PLAYBACK]     [PAUSED]
         │                      │
         └──resume──▶ [PLAYING] ◄─┘
```

## 5. Key Technical Decisions

### 5.1 No Backend
The phone communicates directly with Telegram Bot API. Rationale:
- Fewer moving parts
- No server costs
- Telegram Bot API is designed for client-side polling
- If a backend is needed later (WhatsApp bridge, LLM), it can be added without changing the domain layer

### 5.2 Foreground Service for Polling
`getUpdates` long polling (timeout=30s) runs in a ForegroundService:
- Required for Android 12+ background execution
- Shows persistent notification: "Autogram listening"
- Survives Doze mode

### 5.3 Audio Format: OGG/OPUS
- Telegram's native voice note format
- Excellent compression (small files)
- Android `MediaRecorder` supports it natively with `OutputFormat.OGG`, `AudioEncoder.OPUS`

### 5.4 Car App Library
Using `androidx.car.app:app` (Car App Library) with:
- `GridTemplate` for main screen (Record, Quick Replies, Silent Mode)
- `PaneTemplate` for message status screens
- `CarAppService` + `Session` for AA lifecycle

### 5.5 Room for Persistence
- Messages, conversations, settings, quick replies
- Type converters for enums (state, direction)
- Migration strategy for schema changes
