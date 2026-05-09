# Autogram — Data Model & State Machine

## 1. Entities

### 1.1 Message

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (String) | Primary key |
| conversationId | FK → Conversation | Parent conversation |
| direction | Enum: OUTGOING, INCOMING | Message direction |
| state | Enum: MessageState | Current state (see §2) |
| telegramMessageId | Long? | Telegram API message ID |
| audioFilePath | String? | Local path to audio file |
| audioDurationMs | Long? | Duration in milliseconds |
| textContent | String? | Text content (for text replies) |
| createdAt | Instant | When created locally |
| sentAt | Instant? | When sent/received via API |
| playedAt | Instant? | When played to user |
| errorMessage | String? | Last error if failed |
| retryCount | Int | Number of retry attempts (default 0) |
| quickReplyId | FK → QuickReply? | If sent from quick reply |

### 1.2 Conversation

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (String) | Primary key |
| targetChatId | String | Telegram chat ID |
| targetTitle | String? | Display name of chat/topic |
| isActive | Boolean | Currently active conversation |
| createdAt | Instant | Created timestamp |
| lastMessageAt | Instant? | Timestamp of last message |

> **Note** — the legacy `botToken` column was dropped in Room migration
> v17 → v18 (issue #69). Auth now goes through TDLib's MTProto session
> (phone + code), not a Bot API token, and the column is no longer
> persisted in any form.

### 1.3 QuickReply

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (String) | Primary key |
| label | String | Button label (e.g., "Driving") |
| textContent | String | Text to send |
| position | Int | Display order |
| isEnabled | Boolean | Active/inactive |

### 1.4 Settings

| Field | Type | Description |
|-------|------|-------------|
| id | Int (always 1) | Singleton |
| activeConversationId | FK → Conversation? | Current target |
| retentionDays | Int | History retention (default 30) |
| silentMode | Boolean | Suppress auto-play |
| minRecordingDurationMs | Long | Min recording length (default 1500) |
| maxRecordingDurationMs | Long | Max recording length (default 60000) |
| pollingIntervalMs | Long | Poll interval (default 3000) |
| maxRetryAttempts | Int | Max send retries (default 3) |
| autoPlayReplies | Boolean | Auto-play incoming (default true) |

### 1.5 DeliveryAttempt

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (String) | Primary key |
| messageId | FK → Message | Parent message |
| attemptNumber | Int | Attempt sequence |
| attemptedAt | Instant | When attempted |
| success | Boolean | Outcome |
| errorMessage | String? | Error if failed |
| httpStatusCode | Int? | Telegram API response code |

### 1.6 PendingPlayback

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (String) | Primary key |
| messageId | FK → Message | Queued message |
| enqueuedAt | Instant | When queued |
| reason | Enum: SILENT_MODE, IN_CALL, USER_PAUSED, AUDIO_FOCUS_DENIED | Why queued |
| playedAt | Instant? | When finally played |

## 2. Message State Machine

### States

| State | Description |
|-------|-------------|
| IDLE | No active operation |
| RECORDING | Audio recording in progress |
| RECORDED | Recording finished, not yet validated |
| VALIDATING | Checking for empty/silent audio |
| DISCARDED | Filtered out (too short/silent) |
| QUEUED | Validated, waiting to send |
| SENDING | HTTP request in flight |
| SENT | Telegram API confirmed receipt |
| FAILED | Send failed, retries available |
| PERMANENTLY_FAILED | All retries exhausted |
| RECEIVED | Incoming message downloaded/parsed |
| PENDING_PLAYBACK | Waiting for conditions to play |
| PLAYING | Audio currently playing |
| PAUSED | Playback paused by user |
| PLAYED | Playback completed |
| ARCHIVED | Beyond retention, kept for stats only |

### Transitions

```
IDLE ──[tap record]──▶ RECORDING
RECORDING ──[stop/silence/timeout]──▶ RECORDED
RECORDED ──[validate]──▶ VALIDATING
VALIDATING ──[too short/silent]──▶ DISCARDED ──▶ IDLE
VALIDATING ──[valid]──▶ QUEUED
QUEUED ──[send attempt]──▶ SENDING
SENDING ──[200 OK]──▶ SENT
SENDING ──[error]──▶ FAILED
FAILED ──[retry available]──▶ QUEUED (retryCount++)
FAILED ──[max retries]──▶ PERMANENTLY_FAILED
SENT ──[ack from Telegram]──▶ (delivered, stay SENT or → delivered state)

(incoming path)
RECEIVED ──[can play]──▶ PLAYING
RECEIVED ──[blocked: call/silent/focus]──▶ PENDING_PLAYBACK
PENDING_PLAYBACK ──[conditions met]──▶ PLAYING
PLAYING ──[complete]──▶ PLAYED
PLAYING ──[user pause]──▶ PAUSED
PAUSED ──[user resume]──▶ PLAYING
PLAYED ──[retention expired]──▶ ARCHIVED
```

## 3. Entity Relationships

```
Settings (1) ──── (1) Conversation [activeConversationId]
Conversation (1) ── (N) Message
Message (1) ── (N) DeliveryAttempt
Message (1) ── (0..1) PendingPlayback
Message (N) ── (0..1) QuickReply [if from quick reply]
```

## 4. Room Database Schema Notes

- All timestamps stored as `Long` (epoch millis) with TypeConverters
- Enums stored as `String` via TypeConverters
- TDLib's local message database is encrypted with a per-install key wrapped by
  Android Keystore (see `TdLibClient.tdlibDatabaseEncryptionKey`); the LLM
  summarisation API key lives in `EncryptedSharedPreferences` (see
  `LlmApiKeyStore`, audit issue #70). The legacy `Conversation.botToken`
  plaintext column was removed by migration v17 → v18 (issue #69).
- Indexes on: `Message.conversationId`, `Message.state`, `Message.createdAt`
- Cascade delete: deleting a Conversation deletes all its Messages
- `Settings` is a single-row table (enforced by inserting only row with id=1)
