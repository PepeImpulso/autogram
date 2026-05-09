# Autogram — Development Plan

## 1. Phased Development Plan

### Phase 0 — Technical Prototype (Days 1-7)
Validate all core technical assumptions.
- [ ] Create Telegram bot, get token, test sendVoice + getUpdates via curl
- [ ] Create minimal Android app that records OGG/OPUS audio
- [ ] Send recorded audio to Telegram via Bot API
- [ ] Poll for replies and log them
- [ ] Play received voice note via MediaPlayer
- [ ] Create minimal Car App Library project, display a button

**Gate:** Voice note round-trip works: record → send → receive → play.

### Phase 1 — Core MVP (Days 8-21)
Build the minimal viable product.
- [ ] CarAppService with GridTemplate (Record button)
- [ ] Audio recording lifecycle (start/stop/silence detection)
- [ ] Empty message filter
- [ ] TelegramClient: sendVoice, getUpdates
- [ ] ForegroundService for polling
- [ ] Message state machine implementation
- [ ] Auto-play incoming messages
- [ ] Send/error visual feedback on AA
- [ ] Room database setup + Message entity
- [x] Settings Activity (TDLib auth replaced bot token + chat ID; the modern setup is pinned-circle picker + phone-number login)

**Gate:** Full round-trip works on Android Auto DHU.

### Phase 2 — Robustness (Days 22-35)
Make it reliable.
- [ ] WorkManager retry queue for failed sends
- [ ] Call state detection + playback suppression
- [ ] Audio focus management
- [ ] Silent mode toggle
- [ ] Quick replies (3 presets) on AA
- [ ] Error handling for all edge cases
- [ ] Proper notification for ForegroundService
- [ ] Permissions handling (RECORD_AUDIO, POST_NOTIFICATIONS)

**Gate:** App survives network errors, calls, and poor connections.

### Phase 3 — Polish (Days 36-45)
History and UX.
- [x] Message history view on phone
- [x] Configurable retention period (RetentionCleanupWorker, daily)
- [x] Pending reply alerts (notification) — PendingReplyNotifier
- [x] Android Auto UI polish (colors, icons, feedback) — vector drawables, ActionStrip, state-colored status icons
- [x] Battery optimization testing — Settings → opt-out button
- [x] Onboarding flow (first-run: TDLib phone-number login, pin a circle, test playback) — TelegramLoginActivity + OnboardingActivity

**Gate:** Daily use is smooth and reliable. **Status:** ✅

### Phase 4 — Voice Commands (Days 46-55)
- [x] SpeechRecognizer integration for commands — VoiceCommandRecognizer
- [x] Command set: repeat, cancel, pause, resume, send, silent — VoiceCommand enum
- [x] Help screen on AA with command list — VoiceCommandsHelpScreen
- [ ] Voice-triggered quick reply selection (deferred — requires UX design for ambiguous matches)

### Phase 5 — Evaluation (Days 56+)
- [ ] Evaluate WhatsApp Business API for v2 (product decision, not code)
- [ ] Evaluate LLM-based response summarization (product decision, not code)
- [ ] Evaluate Play Store readiness and compliance (product decision, not code)
- [x] Statistics dashboard on phone — StatsActivity / GetStatsUseCase

## 2. Recommended Tech Stack

| Component | Technology | Justification |
|-----------|-----------|---------------|
| Language | **Kotlin** | Modern, concise, official for Android |
| Min SDK | **API 29 (Android 10)** | OGG/OPUS native support; Android Auto stable |
| Target SDK | **API 35** | Latest stable |
| UI (Phone) | **Jetpack Compose** | Modern UI toolkit; less boilerplate |
| UI (Auto) | **Car App Library 1.4+** | Official AA support |
| Async | **Kotlin Coroutines + Flow** | Natural fit for Android; reactive streams |
| DI | **Hilt** | Official Google DI; simple setup |
| Network | **Retrofit + OkHttp** | Mature, reliable HTTP client |
| Audio Recording | **MediaRecorder** (OGG/OPUS) | Native Android; Telegram-compatible |
| Audio Playback | **MediaPlayer** | Simple, sufficient for voice notes |
| TTS | **android.speech.tts.TextToSpeech** | Built-in Android TTS |
| Persistence | **Room** | Official ORM; compile-time query verification |
| Background Work | **ForegroundService + WorkManager** | Required for Android 12+ |
| Security | **Android Keystore + EncryptedSharedPreferences** | Wraps the TDLib database key + the LLM summarisation API key. Bot tokens are no longer stored — auth uses TDLib's MTProto session. |
| Logging | **Timber** | Lightweight logging |
| Build | **Gradle Kotlin DSL** | Type-safe build scripts |

## 3. Repository Structure

```
autogram/
├── .github/
│   └── workflows/
│       └── ci.yml
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/autogram/app/
│   │   │   │   ├── AutogramApplication.kt
│   │   │   │   ├── domain/
│   │   │   │   │   ├── model/
│   │   │   │   │   │   ├── Message.kt
│   │   │   │   │   │   ├── Conversation.kt
│   │   │   │   │   │   ├── QuickReply.kt
│   │   │   │   │   │   ├── Settings.kt
│   │   │   │   │   │   ├── DeliveryAttempt.kt
│   │   │   │   │   │   ├── PendingPlayback.kt
│   │   │   │   │   │   ├── MessageState.kt
│   │   │   │   │   │   └── MessageDirection.kt
│   │   │   │   │   ├── statemachine/
│   │   │   │   │   │   └── MessageStateMachine.kt
│   │   │   │   │   ├── usecase/
│   │   │   │   │   │   ├── SendMessageUseCase.kt
│   │   │   │   │   │   ├── ReceiveMessageUseCase.kt
│   │   │   │   │   │   ├── PlayReplyUseCase.kt
│   │   │   │   │   │   ├── RecordAudioUseCase.kt
│   │   │   │   │   │   ├── FilterEmptyMessageUseCase.kt
│   │   │   │   │   │   ├── ManageHistoryUseCase.kt
│   │   │   │   │   │   └── ManageSettingsUseCase.kt
│   │   │   │   │   └── repository/
│   │   │   │   │       ├── MessageRepository.kt
│   │   │   │   │       ├── ConversationRepository.kt
│   │   │   │   │       ├── SettingsRepository.kt
│   │   │   │   │       └── QuickReplyRepository.kt
│   │   │   │   ├── infrastructure/
│   │   │   │   │   ├── telegram/
│   │   │   │   │   │   ├── TelegramApiClient.kt
│   │   │   │   │   │   ├── TelegramModels.kt
│   │   │   │   │   │   └── TelegramPollingWorker.kt
│   │   │   │   │   ├── audio/
│   │   │   │   │   │   ├── AudioRecorder.kt
│   │   │   │   │   │   ├── AudioPlayer.kt
│   │   │   │   │   │   └── EmptyMessageFilter.kt
│   │   │   │   │   ├── persistence/
│   │   │   │   │   │   ├── AutogramDatabase.kt
│   │   │   │   │   │   ├── dao/
│   │   │   │   │   │   │   ├── MessageDao.kt
│   │   │   │   │   │   │   ├── ConversationDao.kt
│   │   │   │   │   │   │   ├── SettingsDao.kt
│   │   │   │   │   │   │   └── QuickReplyDao.kt
│   │   │   │   │   │   ├── entity/ (Room entities)
│   │   │   │   │   │   └── converter/
│   │   │   │   │   │       └── Converters.kt
│   │   │   │   │   ├── retry/
│   │   │   │   │   │   └── RetryWorker.kt
│   │   │   │   │   └── platform/
│   │   │   │   │       ├── CallStateMonitor.kt
│   │   │   │   │       └── AudioFocusManager.kt
│   │   │   │   ├── interface/
│   │   │   │   │   ├── auto/
│   │   │   │   │   │   ├── AutogramCarAppService.kt
│   │   │   │   │   │   ├── AutogramCarSession.kt
│   │   │   │   │   │   └── MainScreen.kt
│   │   │   │   │   ├── phone/
│   │   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   │   ├── SettingsActivity.kt
│   │   │   │   │   │   └── HistoryActivity.kt
│   │   │   │   │   └── di/
│   │   │   │   │       └── AppModule.kt
│   │   │   │   └── service/
│   │   │   │       └── AutogramPollingService.kt
│   │   │   ├── res/
│   │   │   │   ├── xml/
│   │   │   │   │   └── automotive_app_desc.xml
│   │   │   │   └── values/
│   │   │   │       └── strings.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/com/autogram/app/
│   │           ├── domain/
│   │           │   ├── statemachine/
│   │           │   │   └── MessageStateMachineTest.kt
│   │           │   └── usecase/
│   │           │       └── FilterEmptyMessageUseCaseTest.kt
│   │           └── infrastructure/
│   │               └── telegram/
│   │                   └── TelegramApiClientTest.kt
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
├── README.md
└── docs/
    ├── 01-critical-analysis.md
    ├── 02-functional-planning.md
    ├── 03-architecture.md
    ├── 04-data-model.md
    ├── 05-risks.md
    └── 06-development-plan.md
```

## 4. Development Checklist

### Phase 0 — Prototype
- [ ] Create Telegram bot via @BotFather
- [ ] Test sendVoice via curl
- [ ] Test getUpdates via curl
- [ ] Create Android project with Kotlin + Gradle
- [ ] Implement OGG/OPUS recording
- [ ] Implement sendVoice via Retrofit
- [ ] Implement getUpdates polling
- [ ] Implement playback of received voice
- [ ] Create Car App Library hello-world
- [ ] Validate full round-trip on emulator

### Phase 1 — Core MVP
- [ ] Set up Hilt DI
- [ ] Set up Room database
- [ ] Implement Message entity + DAO
- [ ] Implement MessageStateMachine
- [ ] Implement AudioRecorder
- [ ] Implement EmptyMessageFilter
- [ ] Implement TelegramApiClient
- [ ] Implement ForegroundService + polling
- [ ] Implement AudioPlayer + auto-play
- [ ] Build Car App main screen (Record button)
- [ ] Build Settings Activity
- [ ] Wire everything together

### Phase 2 — Robustness
- [ ] Implement WorkManager retry
- [ ] Implement CallStateMonitor
- [ ] Implement AudioFocusManager
- [ ] Implement Silent Mode
- [ ] Implement Quick Replies
- [ ] Handle all error paths
- [ ] Test on DHU

## 5. Acceptance Criteria

| # | Criterion | Verification |
|---|-----------|--------------|
| AC-1 | Tap Record on AA → beep → record → send → "Sent ✓" shows | Manual test on DHU |
| AC-2 | Voice note appears in Telegram chat | Verify in Telegram client |
| AC-3 | Reply from Telegram → auto-plays in car within 5s | Manual test on DHU |
| AC-4 | No network → "Error ✗" shown → message retried on reconnect | Airplane mode test |
| AC-5 | Silent recording < 1.5s → discarded, not sent | Record briefly and release |
| AC-6 | Call comes in during playback → playback pauses, resumes after call | Simulate call |
| AC-7 | Silent mode ON → no auto-play → badge shows pending count | Toggle test |
| AC-8 | Quick reply button → text sent to Telegram | Tap quick reply on AA |
| AC-9 | Settings → change chat ID → subsequent messages go to new chat | Change and send |
| AC-10 | App survives 30 min of driving with screen on | Real-world test |

## 6. Testing Strategy

### Unit Tests
- MessageStateMachine: all valid transitions
- EmptyMessageFilter: duration check, silence detection
- TelegramApiClient: request/response parsing (mock OkHttp)
- Use cases: business logic validation

### Integration Tests
- Room DAO: CRUD operations
- TelegramApiClient → real Bot API test channel (CI flag)
- AudioRecorder → file output validation

### Instrumented Tests
- Audio recording + playback on emulator
- Car App Library rendering
- ForegroundService lifecycle

### Manual Tests (DHU)
- Full send/receive round-trip
- Network interruption recovery
- Call interruption
- Silent mode
- Quick replies
- Settings change

### Real-World Tests
- Install on phone, connect to car
- 30-minute driving session
- Poor signal area
- Music playing simultaneously
