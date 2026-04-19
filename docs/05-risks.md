# Autogram — Risks & Mitigations

| # | Risk | Impact | Probability | Mitigation |
|---|------|--------|-------------|------------|
| R1 | Android Auto rejects app category or template usage | High | Low | Use standard messaging templates; test on DHU early; follow Car App Library docs strictly |
| R2 | Android kills ForegroundService on aggressive OEMs (Xiaomi, Huawei) | High | Medium | Use `START_REDELIVER_INTENT`; `WorkManager` as fallback to restart polling; test on multiple devices |
| R3 | Telegram Bot API outage or breaking change | High | Low | Pin to specific API version; implement health checks; show clear offline status |
| R4 | Audio recording fails on specific devices (permissions, hardware) | Medium | Medium | Graceful degradation; clear error messages; test on 3+ devices early |
| R5 | OPUS/OGG encoding not supported on some devices | Medium | Low | `MediaRecorder.OutputFormat.OGG` available since API 29; fallback to MPEG4/AAC if needed; convert server-side if adding backend later |
| R6 | Audio focus conflicts with music/nav/calls | Medium | High | Properly request `AUDIOFOCUS_GAIN_TRANSIENT`; duck other audio; test with Spotify and Google Maps |
| R7 | Large voice notes (60s) fail to upload on poor connection | Medium | Medium | Chunk upload not supported by Bot API; compress aggressively; limit to 60s max; queue + retry |
| R8 | Bot token leaked from device | High | Low | Store in EncryptedSharedPreferences; never log; ProGuard/R8 obfuscation |
| R9 | Google Play Protect flags app (sideloaded, not Play Store) | Medium | Low | Not a concern for personal use; document installation bypass |
| R10 | Android Auto head unit has significant audio latency | Medium | Medium | Test on real hardware; accept 1-2s latency as acceptable; avoid premature optimization |
| R11 | Long polling drains battery | Medium | Medium | Use 30s long-poll timeout (not short polling); `WakeLock` only when needed; measure battery impact |
| R12 | User accidentally triggers recording (pocket, bump) | Low | Medium | Empty message filter (min 1.5s); haptic feedback on start; require tap to start (no auto-trigger) |
| R13 | Telegram rate limiting (30 msg/s per chat) | Low | Low | Well within single-user usage; implement backoff if 429 received |
| R14 | Phone storage fills with audio files | Low | Medium | Retention policy deletes old files; default 30 days; warn at 90% storage |
| R15 | Multiple Telegram messages arrive simultaneously | Medium | Medium | Queue and play sequentially; show "N messages pending"; don't interleave |
| R16 | Android Auto disconnects mid-recording | Medium | Low | Save partial recording; resume or discard gracefully; handle `onDestroy` in CarAppService |
| R17 | WhatsApp integration attempted via unofficial means | High | N/A | Firmly excluded from v1; document why it's not viable |
