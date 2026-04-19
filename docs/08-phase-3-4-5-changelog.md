# Phases 3, 4, 5 — Changelog

This document captures every change that landed in the master branch to close out Phases 3 (Polish), 4 (Voice Commands), and 5 (Statistics).

## Summary

| Area | Status before | Status after |
|------|---------------|--------------|
| Gradle wrapper jar / scripts | missing from repo | committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` 8.11.1) |
| Hilt + WorkManager wiring | missing `Configuration.Provider`; `RetryWorker` used `@Inject` | `AutogramApplication` provides `HiltWorkerFactory`; `RetryWorker` uses `@AssistedInject` |
| Retention period | UI-only; cleanup never ran | daily `RetentionCleanupWorker` (KEEP policy, requires battery-not-low) |
| Pending reply alerts | none | `PendingReplyNotifier` posts/cancels a notification when replies queue up while silent / in-call / focus-denied |
| Android Auto UI | generic system icon, hardcoded strings | custom vector drawables, `ActionStrip` (silent toggle, voice command), state-colored status icons, string-resource labels, live quick replies from DB |
| Onboarding | none | 3-step Compose wizard (welcome → token+test → chat detection); `MainActivity.onResume` routes here on first run |
| Battery optimization | none | Settings button calls `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, reflects current state |
| Voice commands | none | `VoiceCommandRecognizer` (SpeechRecognizer wrapper), `VoiceCommand` enum with Spanish/English keyword mapping, dispatched from MainScreen |
| Statistics dashboard | none | `StatsActivity` shows totals, success rate, avg duration, last-7-days bar chart |
| BUG-55 | code fixed in `d752e89`, issue still open | issue #59 closed |

## Bug fixes uncovered while finishing

- **`RetryWorker` constructor** used `@Inject` (with raw `Context` and `WorkerParameters`); this is invalid for `@HiltWorker` and would have failed at compile time. Switched to `@AssistedInject` + `@Assisted`.
- **`RetryWorker` import** referenced `dagger.hilt.android.HiltWorker`, which does not exist. Switched to `androidx.hilt.work.HiltWorker`.
- **`AutogramApplication`** never implemented `Configuration.Provider`, so any `@HiltWorker` would have crashed at runtime (`WorkManager` cannot construct workers without the Hilt factory). Added `Configuration.Provider` and removed the default `WorkManagerInitializer` from the manifest.
- **`PlayReplyUseCase.stopAll()`** invoked `playbackScope.cancel()` without importing `kotlinx.coroutines.cancel` — the file would not have compiled. Import added.

## Files added

```
app/src/main/java/com/autogram/app/domain/model/VoiceCommand.kt
app/src/main/java/com/autogram/app/domain/usecase/GetStatsUseCase.kt
app/src/main/java/com/autogram/app/infrastructure/platform/PendingReplyNotifier.kt
app/src/main/java/com/autogram/app/infrastructure/platform/VoiceCommandRecognizer.kt
app/src/main/java/com/autogram/app/infrastructure/retry/RetentionCleanupWorker.kt
app/src/main/java/com/autogram/app/interface/auto/VoiceCommandsHelpScreen.kt
app/src/main/java/com/autogram/app/interface/phone/OnboardingActivity.kt
app/src/main/java/com/autogram/app/interface/phone/OnboardingScreen.kt
app/src/main/java/com/autogram/app/interface/phone/OnboardingViewModel.kt
app/src/main/java/com/autogram/app/interface/phone/StatsActivity.kt
app/src/main/java/com/autogram/app/interface/phone/StatsScreen.kt
app/src/main/java/com/autogram/app/interface/phone/StatsViewModel.kt
app/src/main/res/drawable/ic_cancel.xml
app/src/main/res/drawable/ic_inbox.xml
app/src/main/res/drawable/ic_mic.xml
app/src/main/res/drawable/ic_quick_reply.xml
app/src/main/res/drawable/ic_send.xml
app/src/main/res/drawable/ic_silent.xml
app/src/main/res/drawable/ic_voice_command.xml
app/src/main/res/drawable/ic_volume_up.xml
gradle/wrapper/gradle-wrapper.jar
gradlew
gradlew.bat
docs/08-phase-3-4-5-changelog.md
```

## Verification status

The dev environment used to land these changes had no JDK and no Android SDK installed, so `./gradlew assembleDebug` was not executed. The code was reviewed file-by-file against existing patterns. Required smoke tests on a real device:

- Fresh install → onboarding wizard appears → token test → chat detection → land on the main menu, polling service starts.
- Send a message to the bot from another Telegram account with silent mode ON → notification appears → tap → opens History.
- Open Stats → totals match expectations, last-7-days bars render.
- On DHU/car: tap "Comando por voz" → say "cancelar" while recording → recording cancels.
- Settings → "Permitir ejecución en segundo plano" → system whitelist intent appears, button updates after granting.
- Trigger `RetentionCleanupWorker` manually via `WorkManager.getInstance(ctx).enqueueUniqueWork(...)` and confirm old messages are purged.
