# Autogram — Critical Analysis

## 1. What's Realistic vs. What Has Limitations

### ✅ Realistic (can be done cleanly)
- **Send voice messages to Telegram** via Bot API (sendVoice) — well-documented, reliable, no hacks.
- **Receive messages from Telegram** via long polling or webhook — straightforward with `getUpdates`.
- **Android Auto voice button** — supported via `CarAppService` + `GridTemplate` or `MessageTemplate`.
- **Audio recording** — `MediaRecorder` / `AudioRecord` on Android is mature.
- **Audio playback** — `MediaPlayer` or ExoPlayer; TTS for text responses.
- **Local persistence** — Room DB is solid.
- **Background service** — `ForegroundService` with proper notification is the way.
- **Quick replies** — trivial to implement.

### ⚠️ Possible but with constraints
- **Android Auto custom voice commands** — limited to what Google Assistant allows via `CarAppService`. Custom wake words like "Hey Autogram, respond" are NOT supported. You can only use tap-to-speak or Assistant shortcuts.
- **Automatic playback of incoming responses** — doable but must respect audio focus, call state, and Android Auto audio routing.
- **Scheduled sending** — doable via `WorkManager` but limited utility while driving; better as phone-only feature.
- **Response summarization** — requires an LLM API or on-device model; adds complexity.

### ❌ Problematic / Not viable
- **WhatsApp integration** — no official API for personal accounts. WhatsApp Business API exists but requires a registered business, phone number verification, and is pay-per-message. Unofficial libraries (e.g., wa-web.js, Baileys) violate ToS, are fragile, and unreliable. **Verdict: Drop from v1 entirely.**
- **Custom voice commands beyond Assistant** — Android Auto doesn't allow apps to register custom hotwords. Voice commands must go through Google Assistant or use tap-to-trigger `SpeechRecognizer`.
- **Bidirectional real-time conversation feel** — latency between send → bot response → playback will be 3-10 seconds minimum depending on backend. Not instant-messaging speed.

## 2. What Can Be Done Where

| Layer | Approach |
|-------|----------|
| Telegram sending | Native via Bot API (HTTP) — no backend needed |
| Telegram receiving | Long polling from app, OR lightweight backend for reliability |
| Audio recording | Native Android (`AudioRecord` / `MediaRecorder`) |
| Audio playback | Native Android (`MediaPlayer`) + TTS for text |
| Android Auto UI | Android for Cars App Library (Car App Library) |
| Voice input | Android `SpeechRecognizer` (Google) for transcription, OR send raw audio |
| Persistence | Room (SQLite) on-device |
| Background work | `ForegroundService` + `WorkManager` for retries |

**Key decision:** For v1, send **voice/audio messages** (OGG/OPUS) to Telegram — not transcribed text. This avoids STT complexity and keeps the "walkie-talkie" feel. The Telegram bot on the other end (or a person) receives voice notes.

## 3. Android Auto Specific Constraints

1. **Car App Library** requires specific templates (Grid, Pane, Message, Navigation). No free-form UI.
2. **Distraction-optimized (DO) mode** — limits interactions while driving. Short text, few buttons.
3. **No custom keyboard input** while driving — only voice or preset taps.
4. **App must declare `androidx.car.app.category.MESSAGING`** or similar category.
5. **Background execution** is heavily restricted on Android 12+. ForegroundService with notification is mandatory.
6. **Audio focus** must be requested properly; app can't just play over music/calls.
7. **Screen timeout** — Android Auto head unit may sleep/lock; app must handle lifecycle events.

## 4. Telegram Integration Assessment

- **Bot API** is excellent for v1: sendVoice, getUpdates, sendMessage.
- **Rate limits** are generous (30 msg/sec to one chat, 1 msg/sec overall for bots).
- **Authentication** — bot token is all you need. Simple.
- **Voice messages** — OGG with OPUS codec is the native format. Perfect.
- **No backend needed initially** — the phone can poll directly.
- **Risk:** If phone loses connectivity, messages queue locally and retry.

## 5. WhatsApp Integration Assessment

- **No personal WhatsApp API.** Period.
- **Business API** requires Meta Business verification, costs money, not suited for personal use.
- **Unofficial reverse-engineered clients** break constantly, risk account bans, and are legally grey.
- **Recommendation:** Do not include WhatsApp in any v1 plan. If needed later, evaluate via a backend bridge using WhatsApp Business API, but that's a completely different product.

## 6. Recommended Technical Decisions for v1

1. **Telegram-only** for v1.
2. **Send voice (OGG/OPUS) directly** — no STT, no text conversion. Keep it simple.
3. **Receive text replies via TTS** and voice replies via direct playback.
4. **Single fixed destination** (one Telegram chat) with ability to change in settings.
5. **ForegroundService** for message polling and playback.
6. **Room** for local persistence.
7. **Car App Library** for Android Auto interface.
8. **No backend** — phone does everything directly via Telegram Bot API.
9. **No custom voice commands** — use tap-to-speak via `SpeechRecognizer` only if needed for v1.2+.
10. **Quick replies** as preset voice templates.

## 7. What to Leave Out of v1

| Feature | Reason |
|---------|--------|
| WhatsApp | No viable API |
| Custom voice commands | Android Auto limitation |
| Response summarization | Needs LLM backend |
| Scheduled sending | Low value while driving |
| Statistics | Nice-to-have, not critical |
| Command help screen | Can be a simple list |
| STT/transcription | Adds complexity, voice notes suffice |
| Backend server | Not needed for Telegram-only v1 |

## 8. Risks

### Android Auto
- **Risk:** Google may reject the messaging category if app doesn't follow guidelines strictly.
- **Mitigation:** Study Car App Library samples; test on DHU (Desktop Head Unit).

### Audio Recording/Playback
- **Risk:** Audio focus conflicts with media/music playing.
- **Mitigation:** Properly request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; test with Spotify playing.

### Background Execution
- **Risk:** Android 12+ kills background services aggressively.
- **Mitigation:** ForegroundService with persistent notification; WorkManager for retries.

### Telegram Integration
- **Risk:** Bot token compromised.
- **Mitigation:** Store encrypted in Keystore; don't log.
- **Risk:** Rate limiting or API changes.
- **Mitigation:** Implement exponential backoff; pin to stable API version.

### Security/Permissions
- **Risk:** Overly broad permissions scare users.
- **Mitigation:** Request only RECORD_AUDIO, INTERNET, FOREGROUND_SERVICE, POST_NOTIFICATIONS.

### UX While Driving
- **Risk:** Complex interactions cause distraction.
- **Mitigation:** Maximum 2-3 taps for any action; big buttons; voice-first.

## 9. Conservative Strategy for v1

**v1 = "Walkie-talkie for Telegram via Android Auto"**

1. One button in Android Auto: tap → beep → record → send voice note to Telegram.
2. Background service polls for replies; auto-plays incoming voice/text via TTS.
3. Quick replies: 3-5 preset messages accessible as buttons.
4. Simple history view on phone (not on Android Auto).
5. Settings: bot token, target chat ID, retention period.
6. That's it. Ship it. Iterate.

**Success criteria:** While driving, the user can tap once, speak, and the voice note arrives in Telegram. When a reply comes, it plays automatically. No crashes, no missing messages.
