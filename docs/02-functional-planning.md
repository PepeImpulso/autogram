# Autogram — Functional Planning

## 1. Purpose of the Product

Autogram is a voice-based walkie-talkie app for Android Auto that enables hands-free bidirectional messaging via Telegram. The driver taps a button, speaks, and the voice note is delivered to a preconfigured Telegram chat. When a reply arrives, it plays automatically. The entire interaction requires zero screen reading or typing.

## 2. Primary Use Cases

| # | Use Case | Actor | Description |
|---|----------|-------|-------------|
| UC-1 | Send voice message | Driver | Tap button → beep → speak → voice note sent to Telegram |
| UC-2 | Receive auto-played reply | System | Incoming Telegram message auto-plays through car speakers |
| UC-3 | Send quick reply | Driver | Tap a preset message button (e.g., "Driving, talk later") |
| UC-4 | Pause/resume playback | Driver | Pause incoming message playback; resume later |

## 3. Secondary Use Cases

| # | Use Case | Description |
|---|----------|-------------|
| UC-5 | View history on phone | Browse sent/received messages in a list |
| UC-6 | Change destination | Switch target Telegram chat/bot |
| UC-7 | Configure quick replies | Edit preset messages |
| UC-8 | Set retention period | Configure how long history is kept |
| UC-9 | Silent mode | Temporarily suppress auto-play |
| UC-10 | Empty message filter | Discard accidental silent recordings |
| UC-11 | Pending reply alert | Notify if reply not responded to within N minutes |

## 4. Main User Flow

```
[Android Auto Screen]
    |
    v
User taps "Record" button
    |
    v
Short beep sounds
    |
    v
Audio recording starts (max 60s)
    |
    v
User taps "Stop" OR auto-stop after silence
    |
    v
Validation: duration > 2s, not silent
    |
    |-- Invalid --> "Message too short" toast → back to idle
    |
    v (Valid)
Encode as OGG/OPUS
    |
    v
Send via Telegram Bot API (sendVoice)
    |
    v
Show "Sent ✓" on screen
    |
    v
[Background: poll for replies]
    |
    v
New message detected → download audio/text
    |
    v
Request audio focus
    |
    v
Auto-play reply (voice) or TTS (text)
    |
    v
Show "Reply received" on screen
    |
    v
Back to idle (ready for next message)
```

## 5. Alternative Flows

### 5a. Network Error During Send
- Show "Error ✗" on screen
- Queue message for retry (WorkManager, exponential backoff)
- Retry up to 3 times
- Notify user if all retries fail

### 5b. Multiple Pending Replies
- Queue replies in order
- Play sequentially
- Show count: "3 replies pending"

### 5c. Call in Progress
- Detect `CALL_STATE_OFFHOOK`
- Suppress auto-play
- Queue reply for when call ends
- Show indicator: "Reply waiting"

### 5d. Silent Mode Active
- Suppress auto-play
- Show unread count on button
- When silent mode ends, play oldest pending

## 6. Errors and Exceptions

| Error | Handling |
|-------|----------|
| No network | Queue + retry; show offline indicator |
| Bot token invalid | Show config error; stop polling |
| Chat ID not found | Show config error; prompt reconfigure |
| Audio recording permission denied | Show permission request; app unusable without it |
| Audio focus denied | Queue message; play when focus available |
| Telegram API rate limit | Backoff; retry after indicated time |
| Audio too short/empty | Discard; show "too short" feedback |
| Storage full | Alert user; stop recording |
| Service killed by OS | Restart via WorkManager; resume polling |

## 7. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-1 | Tap-to-record voice message from Android Auto | Must |
| FR-2 | Send voice note to Telegram (OGG/OPUS) | Must |
| FR-3 | Auto-play incoming Telegram replies | Must |
| FR-4 | Quick reply buttons (preset messages) | Must |
| FR-5 | Send confirmation visual feedback | Must |
| FR-6 | Error visual feedback | Must |
| FR-7 | Background message polling | Must |
| FR-8 | Configure bot token and chat ID | Must |
| FR-9 | Empty message filter (min 2s, silence detection) | Must |
| FR-10 | Pause/resume playback | Should |
| FR-11 | Silent mode | Should |
| FR-12 | Message history on phone | Should |
| FR-13 | Configurable retention period | Should |
| FR-14 | Call-aware playback suppression | Should |
| FR-15 | Pending reply alert | Could |
| FR-16 | Statistics | Won't (v1) |
| FR-17 | Scheduled sending | Won't (v1) |
| FR-18 | WhatsApp support | Won't (v1) |
| FR-19 | Voice commands | Won't (v1) |
| FR-20 | Response summarization | Won't (v1) |

## 8. Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | Message delivery reliability > 99% (when network available) |
| NFR-2 | Latency from tap to send < 3s (recording excluded) |
| NFR-3 | Latency from reply received to playback start < 2s |
| NFR-4 | App must not crash during normal driving use |
| NFR-5 | Battery impact < 5% per hour of active use |
| NFR-6 | Android Auto UI distraction-compliant (DO guidelines) |
| NFR-7 | All sensitive data (bot token) stored in Android Keystore |
| NFR-8 | Audio recordings deleted after retention period |
| NFR-9 | App recovers gracefully from network changes, service kills |

## 9. Technical and Legal Constraints

1. **Android Auto** — must use Car App Library templates; no custom views.
2. **Android 12+** — strict background execution limits; ForegroundService required.
3. **Telegram Bot API** — bot must be created and token obtained manually.
4. **Audio codec** — Telegram voice notes require OGG/OPUS.
5. **No WhatsApp API** for personal accounts — excluded from v1.
6. **Driving safety** — app must comply with distraction-optimized guidelines.
7. **Personal use only** — no Play Store requirements for v1.

## 10. Assumptions

1. User has a Telegram account and can create a bot via @BotFather.
2. User drives an Android Auto-compatible vehicle or uses a DHU.
3. Phone has stable internet connection most of the time.
4. User is the only person using this app instance.
5. Telegram Bot API remains stable and available.
6. Android Auto head unit supports audio output.

## 11. External Dependencies

| Dependency | Type | Risk |
|------------|------|------|
| Telegram Bot API | API | Low — stable, well-maintained |
| Google SpeechRecognizer | System service | Medium — may not be available offline |
| Android Auto framework | System | Low — stable since Android 11 |
| Android Keystore | System | Low |
| ExoPlayer / MediaPlayer | Library | Low |
| OPUS codec on Android | Hardware | Low — supported since API 21 |

## 12. Risks

See `05-risks.md` for detailed risk matrix.

## 13. MVP Definition

**v1.0 MVP — "Telegram Walkie-Talkie"**

Must include:
- [x] Record & send voice note to Telegram from Android Auto
- [x] Auto-play incoming Telegram replies
- [x] Quick replies (3 presets)
- [x] Send/error visual confirmation
- [x] Bot token + chat ID configuration
- [x] Empty message filter
- [x] Foreground service for polling
- [x] Local message history
- [x] Call-aware playback suppression
- [x] Silent mode toggle

Explicitly excluded from v1:
- [ ] WhatsApp
- [ ] Custom voice commands
- [ ] Scheduled sending
- [ ] Statistics
- [ ] Response summarization
- [ ] Backend server

## 14. Roadmap

### Phase 0 — Technical Prototype (Week 1-2)
- Validate Telegram Bot API sendVoice + getUpdates
- Validate Android Auto Car App Library basic template
- Validate audio recording → OGG/OPUS encoding
- Validate auto-playback

### Phase 1 — Core MVP (Week 3-6)
- Android Auto UI with record button
- Send voice to Telegram
- Background polling service
- Auto-play replies
- Quick replies
- Settings screen on phone

### Phase 2 — Robustness (Week 7-8)
- Retry queue with WorkManager
- Call detection + playback suppression
- Silent mode
- Empty message filter
- Error handling edge cases
- History view on phone

### Phase 3 — Polish (Week 9-10)
- Android Auto UI refinement
- Audio focus management
- Battery optimization
- Retention policy
- Pending reply alerts

### Phase 4 — Voice Commands (Week 11-13)
- SpeechRecognizer integration
- Voice commands: repeat, cancel, pause, resume
- Help screen with command list

### Phase 5 — Evaluation (Week 14+)
- Evaluate WhatsApp Business API feasibility
- Evaluate response summarization (LLM)
- Evaluate Play Store readiness
- Statistics dashboard
