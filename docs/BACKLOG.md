# Autogram — Backlog

Pendientes **no ejecutados** del roadmap de las 16 mejoras y de las ideas posteriores. Este fichero es la memoria autoritativa: si algún día retomamos el proyecto, esto es lo que estaba en cola.

Last updated: 2026-04-22.

---

## Items del roadmap no entregados

### #5 — Sugerencias contextuales de quick-reply  *(aplazado, depende de M4 completo)*

Tras v1.8.0 ya tenemos `message.transcription`. Falta:
- Heurísticas sencillas que lean la transcripción y propongan 2-3 frases de respuesta.
  - Keywords simples: "¿dónde estás?" → chip "en casa", "llámame" → chip "te llamo ahora", "¿llegas?" → chip "en 10 min".
  - Opcional: LLM via endpoint configurable (OpenAI/Claude/Ollama local) si el usuario mete un API key.
- Aparecen como chips efímeros arriba de los atajos durante ~5 min tras recibir.
- Toggle en Ajustes y gestión del endpoint LLM.

Ficheros probables a tocar:
- Nuevo `SuggestQuickReplyUseCase`
- `MainViewModel` expone `suggestedChips: StateFlow<List<String>>`
- `MainActivity` renderiza chips horizontalmente sobre el grid
- Settings sección "Sugerencias inteligentes" con el endpoint LLM opcional

### #4 — Resumen de mensajes largos  *(aplazado)*

Si `message.transcription.wordCount > 200` → llamar a LLM para resumir a 1-2 frases.
UI: muestra resumen como primer nivel; tap expande a texto completo + audio.
Misma dependencia que #5 (endpoint LLM configurable).

### #10 — Carpetas de círculos  *(aplazado — requiere iteración UX en vivo)*

Agrupar círculos en pestañas. Plan concreto:
- Nueva tabla `circle_folders(id, name, color, sortOrder)` — **migración v10→v11**.
- `ConversationEntity` gana `folderId: String?`.
- `MainActivity`: `TabRow` horizontal encima del grid. "Todos" + una pestaña por carpeta.
- `FlowRow` filtra por la pestaña activa.
- `ManagePinnedActivity`: nueva sección "Carpetas" con CRUD + asignación.

Razón para no ejecutarlo autónomamente: toca la pantalla más usada y hay que iterar UX contigo.

### #6 — Envío multicast  *(rechazado explícitamente)*

El usuario declinó esta feature el 2026-04-22. No re-proponer. Ver `<local-user-dir>\.claude\projects\c--Dev-VS-Code---Apps-autogram\memory\feedback_multicast_abandoned.md`.

---

## Ideas propuestas durante la sesión (no votadas)

### Atajos Listen/Reply en la notificación  *(post-M3)*

La notificación actual de voz entrante (`IncomingMessageNotifier`) usa `MessagingStyle + CarAppExtender` pero sin acciones explícitas. Añadir:
- `NotificationCompat.Action.Builder` con `RemoteInput` para responder (texto).
- `ReplyAction` específico de Android Auto.
- Acción "Escuchar" que, al tap, llama a `PlayReplyUseCase.playManually(messageId)`.

Utilidad: responder sin abrir la app desde wearables y AA.

### Whisper on-device como segundo engine  *(plan B para transcripción)*

v1.8.0 usa `SpeechRecognizer` de Google. Si resulta insuficiente en la práctica (calidad, idioma, dependencia de servicios Google), la arquitectura `TranscriptionEngine` permite añadir una impl con `whisper.cpp`:
- Añadir `whisper.cpp` como submódulo git + build nativo NDK
- O usar un wrapper JitPack como `com.github.TadasAnt:whisper-cpp-android`
- Modelo `base` multilingüe (~142 MB) descargable on-demand, no embebido
- Cambiar el `@Binds` en `AppModule` para apuntar a la nueva impl

Decisión se toma cuando se tenga tiempo de medir en el S25+ real.

### Biometric toggle feedback  *(pulido)*

Si el usuario activa "Unlock con huella" en un dispositivo sin biometría configurada, el toggle se guarda pero `BiometricGate.isSupported()` devuelve false y el gate se salta silenciosamente. Mejor:
- Chequear `isSupported()` antes de persistir el toggle.
- Si false → mostrar dialog "Configura primero una huella/PIN en Ajustes del sistema" con botón que abre `Settings.ACTION_BIOMETRIC_ENROLL`.

### Atajos de teclado / accesibilidad en AA

Probar que la app AA pasa el `CarAppApiLevelRestrictions` check y añadir:
- Soporte de mando de volumen para cambiar velocidad de reproducción
- Botón físico de "hang up" del coche para parar la reproducción

### Métrica de uso offline

Usar Sentry breadcrumbs o logs estructurados para medir:
- % de mensajes que se encolaron offline
- Tiempo medio entre encolado y envío exitoso
- Tasa de `PERMANENTLY_FAILED`

---

## Deuda técnica detectada

### Retention worker + audio ya borrado manualmente

Si el usuario borra manualmente un `.ogg` (via un explorador de ficheros) antes de que retention cleanup corra, el path en DB queda apuntando a nada. El worker no lo repara — simplemente skippa. Podría invalidar `audioFilePath` a null tras detectar fichero inexistente.

### Gesto de micro en pantalla lock

Actualmente `RECORD_AUDIO` permission permite grabar incluso con la pantalla bloqueada (si la app está en primer plano). Falta probar/decidir si queremos que el long-press funcione en lock-screen widget.

### Test coverage

Prácticamente todo el código nuevo de M1-M6 no tiene tests. `ExportImportUseCase` en particular debería tener un test de round-trip (export → import → verificar igualdad), y `AndroidOnDeviceTranscriptionEngine` podría tener tests del decoder OGG→PCM.

---

## Cómo retomar

1. Leer este fichero + `docs/RELEASE_OPS.md` (estado del roadmap) + la memoria en `.claude/projects/.../memory/MEMORY.md`.
2. Comprobar qué hay en `master` vs `autogram-releases` — el estado actual es v1.8.0.
3. Preguntar al usuario qué atacar; la lista es corta y cada punto está autocontenido.
