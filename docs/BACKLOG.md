# Autogram — Backlog

Este fichero es la memoria autoritativa de lo que queda. Si alguien retoma el proyecto, esto es lo que había pendiente.

Last updated: 2026-04-22 (tras v1.13.0).

---

## Roadmap de 16 mejoras — cerrado

15 de 16 items entregados. El único que falta se cerró por decisión del usuario:

| Item | Estado | Release |
| --- | --- | --- |
| #1 Transcripción de voz entrante | ✅ | v1.8.0 |
| #2 TTS para respuestas de texto | ✅ | v1.6.0 |
| #3 Rutado Bluetooth del coche | ✅ | v1.6.0 |
| #4 Resumen de mensajes largos | ✅ | v1.11.0 |
| #5 Sugerencias contextuales quick-reply | ✅ | v1.9.0 |
| #6 Envío multi-círculo | ❌ rechazado | por usuario — ver memoria |
| #7 Preview antes de enviar | ✅ | v1.5.0 |
| #8 Haptic feedback | ✅ | v1.5.0 |
| #9 Reordenar círculos | ✅ | v1.5.0 |
| #10 Carpetas de círculos | ✅ | v1.13.0 |
| #11 Cola offline + retry | ✅ | v1.4.0 |
| #12 Crash reporting (Sentry) | ✅ | v1.4.0 |
| #13 Bloqueo biométrico | ✅ | v1.5.0 |
| #14 Limpieza audios huérfanos | ✅ | v1.4.0 |
| #15 Wizard primera vez | ✅ | v1.7.0 |
| #16 Exportar/importar config | ✅ | v1.7.0 |

Además se shipearon **features adicionales** (no del roadmap original):

- **Listen/Reply actions** en notificaciones (v1.10.0) — con RemoteInput, útil en AA y wearables
- **Auto-update prompt en pantalla inicio** (v1.10.0) — check cada 6h + dialog en Main + fallback a Ajustes
- **Contadores opt-in** (v1.10.0) — toggle en Ajustes, default off
- **Botón "reproducir original"** (v1.12.0) — para escuchar el audio del último mensaje resumido
- **Rediseño premium** (v1.12.0) — ManagePinned y TargetPicker con ElevatedCards, chips, empty states
- **Full security audit** (v1.8.1) — PBKDF2 600k, PFD leak, Mutex, Sentry PII scrub, MediaRecorder recovery

---

## Pendientes declarados pero **no críticos**

### Whisper on-device como segundo engine de transcripción

v1.8.0 usa Android `SpeechRecognizer`. Si Google rebaja la calidad o el soporte, la arquitectura `TranscriptionEngine` permite enchufar `whisper.cpp`:

- Añadir `whisper.cpp` como submódulo + build nativo NDK (o wrapper JitPack `com.github.TadasAnt:whisper-cpp-android`).
- Modelo `base` multilingüe (~142 MB) descargable al activar, no embebido.
- Cambiar `@Binds` en `AppModule.bindTranscriptionEngine` para apuntar a la nueva impl.

No urgente — `SpeechRecognizer` funciona bien en el S25+ del usuario.

### AICore (Gemini Nano) — activación completa

v1.11.0 integra Gemini Nano **via reflexión** así que no fuerza la dependencia. Cuando el artefacto `com.google.ai.edge.aicore:aicore` salga de early-access:

1. Añadirlo a `app/build.gradle.kts`.
2. Reemplazar la reflexión en [GeminiNanoSummarizationEngine.kt](app/src/main/java/com/autogram/app/infrastructure/summarization/GeminiNanoSummarizationEngine.kt) por la API tipada.

Mientras tanto, el LLM endpoint cubre la feature.

### Atajos de teclado / hardware en AA

Ideas de mejora del modo coche no implementadas:

- Mando de volumen → cambiar velocidad de reproducción
- Botón físico "hang up" → parar reproducción
- Grid con más de 8 secundarios visibles con paginación

---

## Deuda técnica conocida

### Tests

Cobertura actual (post acumulación, sin nueva release todavía):

- ✅ [TtsPlayerDetectLocaleTest](app/src/test/java/com/autogram/app/infrastructure/audio/TtsPlayerDetectLocaleTest.kt) — 12 tests, detección Unicode
- ✅ [SuggestQuickReplyUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/SuggestQuickReplyUseCaseTest.kt) — 14 tests, patrones ES+EN + LLM-wins-over-regex + LLM-fallback
- ✅ [ExportImportUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/ExportImportUseCaseTest.kt) — 8 tests, round-trip + magic versions + corrupt/wrong-pw
- ✅ [LlmQuickReplySuggesterTest](app/src/test/java/com/autogram/app/infrastructure/suggestion/LlmQuickReplySuggesterTest.kt) — 9 tests, JSON parser tolerancia
- ✅ [PlayReplyUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/PlayReplyUseCaseTest.kt) — 18 tests, gates + state transitions + summary preference + stopAll
- ✅ [OutboxRetryUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/OutboxRetryUseCaseTest.kt) — 10 tests, todas las ramas del retry decision tree
- ✅ [AutogramApplicationScrubTest](app/src/test/java/com/autogram/app/AutogramApplicationScrubTest.kt) — 11 tests, Sentry PII regex (encontró un bug real con espacios + un mismatch phone-vs-id)
- ✅ [SendVoiceMtprotoUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/SendVoiceMtprotoUseCaseTest.kt) — 10 tests, sendQuickReply paths + offline queue + auth gates + preview state
- ✅ [AndroidOnDeviceTranscriptionEngineTest](app/src/test/java/com/autogram/app/infrastructure/transcription/AndroidOnDeviceTranscriptionEngineTest.kt) — 12 tests, downmix stereo→mono + linear resample (overflow guards, partial frames, identity, interpolation)

**Total: 104 unit tests, 0 fallos.**

Pendientes de cobertura (todos opcionales, ninguno bloqueante):

- `IncomingMessageNotifier` — verificación de MessagingStyle + CarAppExtender, puede pinarse con un Robolectric pequeño si en el futuro emerge un bug aquí.
- `AndroidOnDeviceTranscriptionEngine.decodeToMono16k` (full pipeline) — requiere MediaCodec, solo testeable como instrumented test en device.
- Compose UI — no priorizado; el riesgo de regresión visual lo cubre la prueba manual + el rediseño está estable.

Cuando un bug aparece en una de estas piezas, añadir el test de regresión antes del fix.

### Gesto en lock-screen

**Resuelto** (post v1.15.0, en master sin shippar): Quick Settings tile "Voz a Merlin" en `tiles/RecordVoiceTileService.kt`. Tap desde la notification shade lanza MainActivity con `EXTRA_AUTO_RECORD` que arranca grabación al primary inmediatamente.

### Retention + .ogg borrado manualmente

v1.8.1 añadió `repairMissingAudioPaths` en `RetentionCleanupWorker`. Si el usuario borra un `.ogg` manualmente (file manager), la próxima corrida del worker null-ea el path. **Resuelto**.

### Bugs detectados por tests

Tests de v1.15.0+WIP encontraron y arreglaron:

- **Audio focus en AA** (v1.14.1): `AUDIOFOCUS_GAIN_TRANSIENT` denegado por AA hacía que la app no reprodujera. Fix: `MAY_DUCK` + play-aunque-deniegue. Confirmado por Sentry.
- **MediaRecorder.stop() <1.5s** (v1.14.1): runtime exception escalaba a Sentry. Fix: downgrade a warning + delete output file.
- **Sentry PII regex** (WIP en master): el regex de teléfono se comía espacios trailing y matching pure-digit IDs como teléfonos. Fix en companion: orden LONG_ID → PHONE + boundaries más estrictas.
- **WorkManager en unit tests** (WIP): `RetryWorker.enqueueOnce` crasheaba en JVM sin WorkManager init. Fix: wrap en runCatching, no es fatal porque el worker tiene su propio schedule.

---

## Cómo retomar

1. Leer este fichero + [docs/RELEASE_OPS.md](RELEASE_OPS.md) + la memoria en `.claude/projects/.../memory/MEMORY.md`.
2. Estado actual: **v1.15.0 publicada** en `PepeImpulso/autogram-releases`. Acumulando trabajo en master para una release final con todo (QS tile + LLM chips + tests + refactor + bug fixes).
3. El roadmap original está cerrado; lo único que queda es:
   - **Whisper on-device** — necesita spike contigo + tu Samsung
   - **AICore typed API** — esperar GA del SDK de Google
4. Antes de cualquier cambio de schema → bump de versión en [AutogramDatabase.kt](app/src/main/java/com/autogram/app/infrastructure/persistence/AutogramDatabase.kt) + migración no-destructiva.
5. Antes de añadir un string → localizar en los 6 locales (values, values-es, -en, -fr, -de, -zh, -ja).
6. Antes de una release → `scripts/publish_autogram_release.sh --manual X.Y.Z` desde la Windows box con keystore.
7. Cuando se decida shippar: bump versionCode/versionName en `app/build.gradle.kts` + commit + run script.
