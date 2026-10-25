# Autogram — Backlog

Authoritative record of what's left. If anyone picks up the project,
this is what was pending.

Last updated: 2026-04-22 (after v1.16.0).

---

## 16-item roadmap — closed

15 of 16 items delivered. The one missing item was closed by user
decision:

| Item | Status | Release |
| --- | --- | --- |
| #1 Incoming voice transcription | ✅ | v1.8.0 |
| #2 TTS for text replies | ✅ | v1.6.0 |
| #3 Car Bluetooth routing | ✅ | v1.6.0 |
| #4 Long-message summary | ✅ | v1.11.0 |
| #5 Contextual quick-reply suggestions | ✅ | v1.9.0 |
| #6 Multi-circle send | ❌ rejected | by user — see memory |
| #7 Preview before send | ✅ | v1.5.0 |
| #8 Haptic feedback | ✅ | v1.5.0 |
| #9 Reorder circles | ✅ | v1.5.0 |
| #10 Circle folders | ✅ | v1.13.0 |
| #11 Offline queue + retry | ✅ | v1.4.0 |
| #12 Crash reporting (Sentry) | ✅ | v1.4.0 |
| #13 Biometric lock | ✅ | v1.5.0 |
| #14 Orphan audio cleanup | ✅ | v1.4.0 |
| #15 First-run wizard | ✅ | v1.7.0 |
| #16 Config export/import | ✅ | v1.7.0 |

Also shipped **additional features** (not in the original roadmap):

- **Listen/Reply actions** on notifications (v1.10.0) — with
  RemoteInput, useful on AA and wearables
- **Auto-update prompt on home screen** (v1.10.0) — 6h check +
  dialog in Main + fallback to Settings
- **Opt-in counters** (v1.10.0) — toggle in Settings, default off
- **"Play original" button** (v1.12.0) — to listen to the audio of
  the last summarized message
- **Premium redesign** (v1.12.0, v1.14.0) — ManagePinned,
  TargetPicker, History, Stats, QuickReplies, PinnedPicker with
  ElevatedCards, chips, empty states
- **Full security audit** (v1.8.1) — PBKDF2 600k, PFD leak, Mutex,
  Sentry PII scrub, MediaRecorder recovery
- **Deep audit + third-pass hardening** (v1.16.0) — activity labels
  i18n, backup size guard, LLM endpoint validation, 61 new tests

---

## Declared but **not critical** pending items

### Whisper on-device as a second transcription engine

v1.8.0 uses Android `SpeechRecognizer`. If Google downgrades quality
or support, the `TranscriptionEngine` interface lets us plug in
`whisper.cpp`:

- Add `whisper.cpp` as a submodule + native NDK build (or a JitPack
  wrapper `com.github.TadasAnt:whisper-cpp-android`).
- `base` multilingual model (~142 MB) downloadable on activation,
  not embedded.
- Change `@Binds` in `AppModule.bindTranscriptionEngine` to point
  at the new impl.

Not urgent — `SpeechRecognizer` works well on the user's S25+.

### AICore (Gemini Nano) — full activation

v1.11.0 integrates Gemini Nano **via reflection**, so the dependency
isn't forced. When the `com.google.ai.edge.aicore:aicore` artifact
exits early-access:

1. Add it to `app/build.gradle.kts`.
2. Replace the reflection in [GeminiNanoSummarizationEngine.kt](app/src/main/java/com/autogram/app/infrastructure/summarization/GeminiNanoSummarizationEngine.kt)
   with the typed API.

In the meantime, the LLM endpoint covers the feature.

### Keyboard / hardware shortcuts on AA

Car-mode improvement ideas not yet implemented:

- Volume knob → change playback speed
- Physical "hang up" button → stop playback
- Grid with more than 8 visible secondaries, paginated

---

## Known technical debt

### Tests

Current coverage (post accumulation, shipped in v1.16.0):

- ✅ [TtsPlayerDetectLocaleTest](app/src/test/java/com/autogram/app/infrastructure/audio/TtsPlayerDetectLocaleTest.kt) — 12 tests, Unicode detection
- ✅ [SuggestQuickReplyUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/SuggestQuickReplyUseCaseTest.kt) — 14 tests, ES+EN patterns + LLM-wins-over-regex + LLM-fallback
- ✅ [ExportImportUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/ExportImportUseCaseTest.kt) — 8 tests, round-trip + magic versions + corrupt/wrong-pw
- ✅ [LlmQuickReplySuggesterTest](app/src/test/java/com/autogram/app/infrastructure/suggestion/LlmQuickReplySuggesterTest.kt) — 9 tests, JSON parser tolerance
- ✅ [PlayReplyUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/PlayReplyUseCaseTest.kt) — 18 tests, gates + state transitions + summary preference + stopAll
- ✅ [OutboxRetryUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/OutboxRetryUseCaseTest.kt) — 10 tests, all branches of the retry decision tree
- ✅ [AutogramApplicationScrubTest](app/src/test/java/com/autogram/app/AutogramApplicationScrubTest.kt) — 11 tests, Sentry PII regex (found a real bug with whitespace + a phone-vs-id mismatch)
- ✅ [SendVoiceMtprotoUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/SendVoiceMtprotoUseCaseTest.kt) — 10 tests, sendQuickReply paths + offline queue + auth gates + preview state
- ✅ [AndroidOnDeviceTranscriptionEngineTest](app/src/test/java/com/autogram/app/infrastructure/transcription/AndroidOnDeviceTranscriptionEngineTest.kt) — 12 tests, downmix stereo→mono + linear resample (overflow guards, partial frames, identity, interpolation)

**Total: 104 unit tests, 0 failures.**

Coverage still pending (all optional, none blocking):

- `IncomingMessageNotifier` — verification of MessagingStyle +
  CarAppExtender, can be pinned with a small Robolectric run if a
  bug ever surfaces here.
- `AndroidOnDeviceTranscriptionEngine.decodeToMono16k` (full
  pipeline) — requires MediaCodec, only testable as an instrumented
  test on device.
- Compose UI — not prioritized; visual regression risk is covered
  by manual testing + the redesign is stable.

When a bug appears in one of these pieces, add the regression test
before the fix.

### Lock-screen gesture

**Resolved** (post-v1.15.0, in master before shipping as part of
v1.16.0): Quick Settings tile "Voice to Merlin" in
`tiles/RecordVoiceTileService.kt`. Tap from the notification shade
launches MainActivity with `EXTRA_AUTO_RECORD` that immediately
starts recording to the primary.

### Retention + manually-deleted .ogg files

v1.8.1 added `repairMissingAudioPaths` in `RetentionCleanupWorker`.
If the user deletes an `.ogg` manually (file manager), the next
worker run null-s the path. **Resolved.**

### Bugs surfaced by tests

Tests written for v1.15.0+WIP surfaced and fixed:

- **Audio focus on AA** (v1.14.1): `AUDIOFOCUS_GAIN_TRANSIENT`
  denied by AA made the app fail to play. Fix: `MAY_DUCK` + play-
  even-if-denied. Confirmed via Sentry.
- **MediaRecorder.stop() <1.5s** (v1.14.1): runtime exception
  escalated to Sentry. Fix: downgrade to warning + delete output
  file.
- **Sentry PII regex** (v1.16.0): the phone regex ate trailing
  whitespace and matched pure-digit IDs as phones. Fix in companion:
  LONG_ID → PHONE order + stricter boundaries.
- **WorkManager in unit tests** (v1.16.0): `RetryWorker.enqueueOnce`
  crashed in JVM without WorkManager init. Fix: wrap in runCatching,
  non-fatal because the worker has its own schedule.

---

## How to resume

1. Read this file + [docs/BUILDING.md](BUILDING.md) + the
   memory under `.claude/projects/.../memory/MEMORY.md`.
2. Current state: **v1.16.0 published** on
   `PepeImpulso/autogram-releases`. Clean master, nothing pending.
3. The original roadmap is closed; only remaining ideas:
   - **Whisper on-device** — needs a spike with you + your Samsung
   - **AICore typed API** — waiting on Google SDK GA
4. Before any schema change → version bump in [AutogramDatabase.kt](app/src/main/java/com/autogram/app/infrastructure/persistence/AutogramDatabase.kt)
   plus a non-destructive migration.
5. Before adding a string → localize in the 6 locales
   (`values`, `values-es`, `values-fr`, `values-de`, `values-zh`,
   `values-ja`).
6. Before a release → `scripts/publish_autogram_release.sh --manual X.Y.Z`
   from the Windows box with keystore.
7. When shipping: bump versionCode/versionName in
   `app/build.gradle.kts` + commit + run script.
