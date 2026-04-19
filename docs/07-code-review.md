# Autogram — Code Review (Phase 0)

## Summary
The project has a solid skeleton with correct architecture, docs, and build configuration. However, most implementations are stubs/TODOs. Below are all findings organized by category.

## 1. Missing Implementations (TODOs/Stubs)

### Infrastructure
- **TelegramApiClient.sendVoice()**: Returns `NotImplementedError`. No multipart upload.
- **TelegramApiClient**: No `sendMessage` for text/quick replies.
- **EmptyMessageFilter**: No actual duration check (MediaMetadataRetriever) or RMS silence detection.
- **AudioRecorder**: No max duration limit, no silence detection, no amplitude monitoring.

### Interface
- **MainScreen**: Record button and quick replies have `TODO` onClick handlers.
- **SettingsActivity**: Empty Compose UI stub.
- **HistoryActivity**: Empty Compose UI stub.
- **MainActivity**: References `HistoryActivity` class which lives in SettingsActivity.kt.

### Service
- **AutogramPollingService**: No actual polling loop, no coroutine scope, no lifecycle management.

### Domain
- **No use cases**: All 7 use cases from the plan are missing (SendMessage, ReceiveMessage, PlayReply, RecordAudio, FilterEmpty, ManageHistory, ManageSettings).
- **No DI module**: No Hilt module provides repositories, OkHttpClient, etc.

### Persistence
- **No Room database**: No `AutogramDatabase`, no entities, no DAOs, no type converters.
- **No repository implementations**: All 4 repository interfaces are defined but not implemented.

## 2. Architecture Issues
- `MainActivity.kt` redefines `Spacer` composable (already exists in Compose).
- `HistoryActivity` defined in `SettingsActivity.kt` instead of its own file.
- No ViewModel classes for any screen.
- No Hilt modules (`@Module`) for dependency injection.

## 3. Missing Error Handling
- AudioRecorder: No handling for permission denial at recording time.
- AudioPlayer: Usage attribute should be `USAGE_MEDIA` not `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`.
- TelegramApiClient: No retry, no rate limit handling, no timeout configuration.

## 4. Build/Gradle Issues
- No `gradle-wrapper.jar` or `gradlew` script.
- Missing `gradle.properties` file.
- `androidx.core:core` dependency needed for `IconCompat` usage in MainScreen.

## 5. Android Manifest
- Missing `READ_PHONE_STATE` for call state detection (actually present ✓).
- Missing proper notification icon resource (uses system icon).
- No `android.app.lib_name` metadata for Automotive OS.

## 6. ProGuard/R8
- Basic rules present but missing:
  - Room entity keep rules
  - Hilt rules
  - Coroutines rules

## 7. Missing Resources
- No launcher icon (only references `@mipmap/ic_launcher` which doesn't exist).
- No notification icon.
- No colors.xml for theme.
- Missing `android:appComponentEnabled` for automotive features.

## 8. Missing Tests
- No test files exist at all (directories not created).
- State machine tests, filter tests, API client tests all needed.

## 9. Priority Order for Implementation
1. **Phase 0**: Build setup (#5), Audio recording (#6), Telegram client (#7)
2. **Phase 1**: State machine (#8), Empty filter (#9), AA screen (#10), Polling service (#11), Playback (#12), Room DB (#13), Settings (#14)
3. **Phase 2**: Retry queue (#15), Call/audio focus (#16)
