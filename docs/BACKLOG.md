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

Cobertura actual **limitada pero no cero** (v1.13.0):

- ✅ [TtsPlayerDetectLocaleTest](app/src/test/java/com/autogram/app/infrastructure/audio/TtsPlayerDetectLocaleTest.kt) — 12 tests, detección Unicode
- ✅ [SuggestQuickReplyUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/SuggestQuickReplyUseCaseTest.kt) — 10 tests, patrones ES+EN
- ✅ [ExportImportUseCaseTest](app/src/test/java/com/autogram/app/domain/usecase/ExportImportUseCaseTest.kt) — 8 tests, round-trip + magic versions + corrupt/wrong-pw

Falta cobertura en:

- `PlayReplyUseCase` — muchas ramas de estado, alto riesgo de regresión
- `SendVoiceMtprotoUseCase.stopAndSend` (preview flow)
- `RetryWorker` — el backoff y el maxRetries
- `AndroidOnDeviceTranscriptionEngine` — decoder OGG→PCM puede tener edge cases con bitrates raros

Cuando un bug aparece en una de estas piezas, añadir el test de regresión antes del fix.

### Gesto en lock-screen

Actualmente no hay widget de lock-screen para grabar. Posible mejora: un TileService en quick-settings que inicie la grabación sin desbloquear.

### Retention + .ogg borrado manualmente

v1.8.1 añadió `repairMissingAudioPaths` en `RetentionCleanupWorker`. Si el usuario borra un `.ogg` manualmente (file manager), la próxima corrida del worker null-ea el path. **Resuelto**.

---

## Cómo retomar

1. Leer este fichero + [docs/RELEASE_OPS.md](RELEASE_OPS.md) + la memoria en `.claude/projects/.../memory/MEMORY.md`.
2. Estado actual: **v1.13.0 publicada** en `PepeImpulso/autogram-releases`.
3. El roadmap está cerrado; para nuevo trabajo preguntar al usuario por prioridad.
4. Antes de cualquier cambio de schema → bump de versión en [AutogramDatabase.kt](app/src/main/java/com/autogram/app/infrastructure/persistence/AutogramDatabase.kt) + migración no-destructiva.
5. Antes de añadir un string → localizar en los 6 locales (values, values-es, -en, -fr, -de, -zh, -ja).
6. Antes de una release → `scripts/publish_autogram_release.sh --manual X.Y.Z` desde la Windows box con keystore.
