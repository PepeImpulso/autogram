package com.autogram.app.ui.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.Person
import com.autogram.app.R
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.AudioFocusMode
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.model.VoiceCommand
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.platform.VoiceCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Android Auto home screen. Uses the official templated messaging path so
 * Gearhead can list Autogram as a communication app and render conversation
 * history with AA's reply UI:
 *
 *   - Pinned conversations appear as ConversationItem rows, in the same order
 *     the phone's home screen shows them.
 *   - Reply callbacks send text replies to the selected target.
 *   - A per-conversation Record action starts a direct voice message.
 *   - Bottom ActionStrip keeps the Silent toggle + Voice-command CTA.
 */
@OptIn(ExperimentalCarApi::class)
class MainScreen(
    carContext: CarContext,
    private val sendMessageUseCase: SendVoiceMtprotoUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val manageSettingsUseCase: ManageSettingsUseCase,
    private val quickReplyRepository: QuickReplyRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val voiceCommandRecognizer: VoiceCommandRecognizer
) : Screen(carContext) {

    private val screenJob = SupervisorJob()
    private val screenScope = CoroutineScope(Dispatchers.Main + screenJob)
    private var resetJob: kotlinx.coroutines.Job? = null

    // Reflects settings.autoPlayReplies — the AA actionStrip toggle pauses
    // auto-playback (off) or resumes it (on). Inverted from the historical
    // "silentMode" flag this used to track; v1.20.11 collapsed both into
    // autoPlayReplies (see PlayReplyUseCase). The "silent" naming stays
    // here only because the icon affordance still reads as "mute audio".
    private val autoPlayOff = MutableStateFlow(false)
    private val cachedQuickReplies = MutableStateFlow<List<QuickReply>>(emptyList())
    private val cachedPinned = MutableStateFlow<List<Conversation>>(emptyList())
    private val cachedMessages = MutableStateFlow<List<Message>>(emptyList())
    // v1.24.0 — feed the new Pending tab. Same Flow the phone Pending Queue
    // screen consumes (messageRepository.observePendingPlaybackQueue), in
    // drain order (oldest first).
    private val cachedPending = MutableStateFlow<List<Message>>(emptyList())
    // v1.24.0 — full settings snapshot so the Settings tab can render
    // playbackSpeed / audioFocusMode / ttsAsNavigationPrompt cycle buttons
    // synchronously inside onGetTemplate without re-querying the repo.
    private val cachedSettings = MutableStateFlow<AppSettings?>(null)
    // v1.24.0 — which TabTemplate tab is active. Persists across invalidate()
    // so tab switches stick instead of resetting to Inbox every recomposition.
    private val currentTab = MutableStateFlow(TabId.INBOX)

    // Unchanged from v1.17.x: icon cache only used by the fallback
    // GridTemplate on the non-home screens (recording / status).
    private val iconCache: MutableMap<String, CarIcon> = HashMap()

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                screenJob.cancel()
                iconCache.clear()
            }
        })
        // Combine recording state flows so every status change invalidates the
        // car template from a single collector.
        screenScope.launch {
            kotlinx.coroutines.flow.combine(
                sendMessageUseCase.status,
                sendMessageUseCase.activeTargetId
            ) { status, _ -> status }.collect { status ->
                // Auto-clear any terminal status after 5 s so the pill /
                // status banner doesn't linger indefinitely. Moved here
                // from onGetTemplate (which had a side effect every time
                // AA re-requested the template — not idempotent-safe).
                // 5 s matches the phone's HeroHeader pill auto-dismiss.
                val terminal = status is SendVoiceMtprotoUseCase.SendStatus.Sent ||
                    status is SendVoiceMtprotoUseCase.SendStatus.Error ||
                    status is SendVoiceMtprotoUseCase.SendStatus.Discarded ||
                    status is SendVoiceMtprotoUseCase.SendStatus.QueuedOffline
                if (terminal) {
                    resetJob?.cancel()
                    resetJob = screenScope.launch {
                        delay(5000)
                        if (sendMessageUseCase.status.value === status) {
                            sendMessageUseCase.reset()
                        }
                    }
                }
                invalidate()
            }
        }
        screenScope.launch {
            manageSettingsUseCase.observe().collect {
                cachedSettings.value = it
                val muted = !it.autoPlayReplies
                if (autoPlayOff.value != muted) {
                    autoPlayOff.value = muted
                }
                // The Settings tab reads cycle-button labels from cachedSettings,
                // so any settings change has to refresh the template — but only
                // when that tab is currently visible, to avoid invalidating
                // mid-recording-state on unrelated tabs.
                if (currentTab.value == TabId.SETTINGS) invalidate()
            }
        }
        screenScope.launch {
            messageRepository.observePendingPlaybackQueue().collect { list ->
                cachedPending.value = list
                if (currentTab.value == TabId.PENDING) invalidate()
            }
        }
        screenScope.launch {
            quickReplyRepository.observeAll().collect { list ->
                cachedQuickReplies.value = list
                invalidate()
            }
        }
        screenScope.launch {
            conversationRepository.observePinned().collect { list ->
                cachedPinned.value = list
                invalidate()
            }
        }
        screenScope.launch {
            messageRepository.observeAllAcrossConversations().collect { list ->
                cachedMessages.value = list
                invalidate()
            }
        }
    }

    /**
     * v1.24.0 — TabTemplate root identifies. Each value's [contentId] is
     * what TabTemplate.setActiveTabContentId reads, and what
     * TabCallback.onTabSelected reports back when the host switches tabs.
     */
    enum class TabId(val contentId: String) {
        INBOX("inbox"),
        SHORTCUTS("shortcuts"),
        PENDING("pending"),
        SETTINGS("settings");

        companion object {
            fun fromContentId(id: String): TabId =
                values().firstOrNull { it.contentId == id } ?: INBOX
        }
    }

    override fun onGetTemplate(): Template {
        return when (val status = sendMessageUseCase.status.value) {
            // Recording uses a simple driver-safe status screen with
            // explicit Send / Cancel actions.
            is SendVoiceMtprotoUseCase.SendStatus.Recording -> buildRecordingTemplate()
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> buildStatusTemplate(carContext.getString(R.string.status_sending))
            // Auto-reset after Sent is scheduled by the flow collector
            // in init {} — onGetTemplate stays pure (no side effects)
            // so AA can re-request the template any number of times
            // without accumulating delayed coroutines.
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> buildStatusTemplate(carContext.getString(R.string.status_sent))
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> buildStatusTemplate(
                carContext.getString(R.string.status_discarded, status.reason)
            )
            is SendVoiceMtprotoUseCase.SendStatus.Error -> buildStatusTemplate(
                carContext.getString(R.string.status_error) + ": ${status.message}"
            )
            else -> buildTabbedHome()
        }
    }

    /**
     * v1.24.0 — the new home is a [TabTemplate] with four tabs:
     *   - Inbox: official messaging list (one [ConversationItem] per pinned
     *     conversation, with inline reply + Record per row).
     *   - Shortcuts: grid of one-tap quick replies.
     *   - Pending: list of messages waiting to play.
     *   - Settings: PaneTemplate with on-the-road tweakable toggles (audio
     *     focus mode, autoplay, playback speed, TTS nav routing).
     *
     * Tab switching keeps state via [currentTab]; `onTabSelected` records
     * the new selection and calls [invalidate] so the host re-asks for the
     * template with the new active content.
     */
    private fun buildTabbedHome(): Template {
        val tabCallback = object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                currentTab.value = TabId.fromContentId(tabContentId)
                invalidate()
            }
        }
        val contents = when (currentTab.value) {
            TabId.INBOX -> buildInboxTabContents()
            TabId.SHORTCUTS -> buildShortcutsTabContents()
            TabId.PENDING -> buildPendingTabContents()
            TabId.SETTINGS -> buildSettingsTabContents()
        }
        return TabTemplate.Builder(tabCallback)
            .setHeaderAction(Action.APP_ICON)
            .addTab(buildTab(TabId.INBOX, R.string.aa_tab_inbox, R.drawable.ic_inbox))
            .addTab(buildTab(TabId.SHORTCUTS, R.string.aa_tab_shortcuts, R.drawable.ic_bolt))
            .addTab(buildTab(TabId.PENDING, R.string.aa_tab_pending, R.drawable.ic_play_circle))
            .addTab(buildTab(TabId.SETTINGS, R.string.aa_tab_settings, R.drawable.ic_settings))
            .setActiveTabContentId(currentTab.value.contentId)
            .setTabContents(contents)
            .build()
    }

    private fun buildTab(id: TabId, titleRes: Int, iconRes: Int): Tab =
        Tab.Builder()
            .setContentId(id.contentId)
            .setTitle(carContext.getString(titleRes))
            .setIcon(carIcon(iconRes, CarColor.DEFAULT))
            .build()

    /**
     * Inbox tab — the messaging list that used to be the entire home in
     * v1.23.x. ListTemplate + ConversationItem rows, ActionStrip with mute
     * toggle + voice command stays here so it's reachable on the most-used
     * tab.
     */
    private fun buildInboxTabContents(): TabContents =
        TabContents.Builder(buildHomeTemplate()).build()

    /**
     * Shortcuts tab — GridTemplate of enabled quick replies. Tapping a tile
     * sends the shortcut via [SendVoiceMtprotoUseCase.sendQuickReply] (or
     * sendVoiceShortcut for voice). Falls back to an empty-state
     * MessageTemplate when no shortcuts are configured.
     */
    private fun buildShortcutsTabContents(): TabContents {
        val list = cachedQuickReplies.value.filter { it.isEnabled }
        if (list.isEmpty()) {
            return TabContents.Builder(
                MessageTemplate.Builder(carContext.getString(R.string.aa_shortcuts_empty))
                    .setTitle(carContext.getString(R.string.aa_tab_shortcuts))
                    .setIcon(carIcon(R.drawable.ic_bolt, CarColor.YELLOW))
                    .build()
            ).build()
        }
        val items = ItemList.Builder()
        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        for ((i, qr) in list.withIndex()) {
            if (i >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(qr.label)
                    .setImage(shortcutTileIcon(qr), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { sendShortcut(qr) }
                    .build()
            )
        }
        val grid = GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.aa_tab_shortcuts))
            .setSingleList(items.build())
            .setActionStrip(buildActionStrip())
        runCatching { grid.setItemSize(GridTemplate.ITEM_SIZE_LARGE) }
        return TabContents.Builder(grid.build()).build()
    }

    /**
     * Pending tab — driver-safe ListTemplate of messages queued for
     * playback. Tap a row to start playing from that point;
     * "Mark all as listened" clears the backlog without playing.
     */
    private fun buildPendingTabContents(): TabContents {
        val pending = cachedPending.value
        if (pending.isEmpty()) {
            return TabContents.Builder(
                MessageTemplate.Builder(carContext.getString(R.string.aa_pending_empty))
                    .setTitle(carContext.getString(R.string.aa_tab_pending))
                    .setIcon(carIcon(R.drawable.ic_play_circle, CarColor.DEFAULT))
                    .build()
            ).build()
        }
        val convById = cachedPinned.value.associateBy { it.id }
        val items = ItemList.Builder()
        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        for ((i, msg) in pending.withIndex()) {
            if (i >= maxItems) break
            val conv = convById[msg.conversationId]
            val title = conv?.let { tileTitle(it) } ?: carContext.getString(R.string.app_name)
            val subtitle = msg.summary?.takeIf { it.isNotBlank() }
                ?: msg.transcription?.takeIf { it.isNotBlank() }
                ?: msg.textContent?.takeIf { it.isNotBlank() }
                ?: carContext.getString(R.string.aa_voice_message)
            items.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(subtitle)
                    .setImage(carIcon(R.drawable.ic_play_circle, CarColor.PRIMARY))
                    .setOnClickListener {
                        screenScope.launch { playReplyUseCase.playFromHere(msg) }
                    }
                    .build()
            )
        }
        return TabContents.Builder(
            ListTemplate.Builder()
                .setTitle(carContext.getString(R.string.aa_tab_pending))
                .setSingleList(items.build())
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(
                            Action.Builder()
                                .setTitle(carContext.getString(R.string.aa_pending_mark_all))
                                .setOnClickListener {
                                    screenScope.launch {
                                        playReplyUseCase.markAllPendingAsListened()
                                        invalidate()
                                    }
                                }
                                .build()
                        )
                        .build()
                )
                .build()
        ).build()
    }

    /**
     * Settings tab — driver-safe PaneTemplate. Each row cycles through its
     * preset values on tap (no sliders / dropdowns in the car, both are
     * banned by AA distraction guidelines). Reads + writes
     * [manageSettingsUseCase] so changes persist + sync with the phone
     * Settings screen.
     */
    private fun buildSettingsTabContents(): TabContents {
        val settings = cachedSettings.value
        val pane = Pane.Builder()
        // 1. Auto-play toggle.
        val autoLabel = if (settings?.autoPlayReplies == true)
            carContext.getString(R.string.aa_settings_autoplay_on)
        else
            carContext.getString(R.string.aa_settings_autoplay_off)
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.aa_settings_autoplay))
                .addText(autoLabel)
                .setOnClickListener { toggleAutoPlay() }
                .build()
        )
        // 2. Playback speed cycle.
        val speed = settings?.playbackSpeed ?: 1.25f
        val speedLabel = carContext.getString(R.string.aa_settings_speed, "%.2f".format(speed))
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.aa_settings_speed, "%.2f".format(speed)))
                .addText(speedLabel)
                .setOnClickListener {
                    screenScope.launch {
                        val current = manageSettingsUseCase.get()
                        val next = nextPlaybackSpeed(current.playbackSpeed)
                        manageSettingsUseCase.update(current.copy(playbackSpeed = next))
                    }
                }
                .build()
        )
        // 3. Audio focus mode cycle.
        val focusMode = settings?.audioFocusMode ?: AudioFocusMode.TRANSIENT_MAY_DUCK
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.aa_settings_focus_mode, focusMode.name))
                .addText(focusMode.name)
                .setOnClickListener {
                    screenScope.launch {
                        val current = manageSettingsUseCase.get()
                        manageSettingsUseCase.update(
                            current.copy(audioFocusMode = nextFocusMode(current.audioFocusMode))
                        )
                    }
                }
                .build()
        )
        // 4. TTS as navigation prompt toggle.
        val ttsNavOn = settings?.ttsAsNavigationPrompt == true
        val ttsNavLabel = if (ttsNavOn)
            carContext.getString(R.string.aa_state_on)
        else
            carContext.getString(R.string.aa_state_off)
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.aa_settings_tts_nav, ttsNavLabel))
                .addText(ttsNavLabel)
                .setOnClickListener {
                    screenScope.launch {
                        val current = manageSettingsUseCase.get()
                        manageSettingsUseCase.update(
                            current.copy(ttsAsNavigationPrompt = !current.ttsAsNavigationPrompt)
                        )
                    }
                }
                .build()
        )
        return TabContents.Builder(
            PaneTemplate.Builder(pane.build())
                .setTitle(carContext.getString(R.string.aa_tab_settings))
                .build()
        ).build()
    }

    private fun nextPlaybackSpeed(current: Float): Float {
        val presets = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val idx = presets.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return presets[(idx + 1).mod(presets.size).coerceAtLeast(0)]
    }

    private fun nextFocusMode(current: AudioFocusMode): AudioFocusMode {
        val all = AudioFocusMode.values()
        val idx = all.indexOf(current).let { if (it < 0) 0 else it }
        return all[(idx + 1) % all.size]
    }

    /** Home = official Android Auto messaging list. */
    private fun buildHomeTemplate(): Template {
        val items = ItemList.Builder()
            .setNoItemsMessage(carContext.getString(R.string.aa_no_conversations))

        val messagesByConversation = cachedMessages.value.groupBy { it.conversationId }
        val pinned = cachedPinned.value
        pinned.forEach { conv ->
            val carMessages = messagesByConversation[conv.id]
                .orEmpty()
                .sortedByDescending { it.createdAt }
                .take(5)
                .asReversed()
                .map { it.toCarMessage(conv) }
                .ifEmpty { listOf(emptyCarMessage(conv)) }

            val callback = object : ConversationCallback {
                override fun onMarkAsRead() = Unit
                override fun onTextReply(text: String) {
                    screenScope.launch {
                        sendMessageUseCase.sendQuickReply(text, targetConversationId = conv.id)
                        invalidate()
                    }
                }
            }

            items.addItem(
                ConversationItem.Builder()
                    .setId(conv.id)
                    .setTitle(CarText.create(tileTitle(conv)))
                    .setSelf(selfPerson())
                    .setIcon(avatarTileIcon(conv, isPrimary = conv == pinned.firstOrNull()))
                    .setMessages(carMessages)
                    .setConversationCallback(callback)
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.aa_record))
                            .setIcon(carIcon(R.drawable.ic_mic, CarColor.PRIMARY))
                            .setOnClickListener { startRecording(conv.id) }
                            .build()
                    )
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.aa_conversations))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(buildActionStrip())
            .setSingleList(items.build())
            .build()
    }

    private fun selfPerson(): Person =
        Person.Builder()
            .setName(carContext.getString(R.string.app_name))
            .build()

    private fun senderPerson(conv: Conversation): Person =
        Person.Builder()
            .setName(tileTitle(conv))
            .build()

    private fun Message.toCarMessage(conv: Conversation): CarMessage {
        val body = summary?.takeIf { it.isNotBlank() }
            ?: transcription?.takeIf { it.isNotBlank() }
            ?: textContent?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.aa_voice_message)
        return CarMessage.Builder()
            .setSender(if (direction == MessageDirection.OUTGOING) selfPerson() else senderPerson(conv))
            .setBody(CarText.create(body))
            .setReceivedTimeEpochMillis(createdAt)
            .setRead(state != com.autogram.app.domain.model.MessageState.PENDING_PLAYBACK)
            .build()
    }

    private fun emptyCarMessage(conv: Conversation): CarMessage =
        CarMessage.Builder()
            .setSender(senderPerson(conv))
            .setBody(CarText.create(carContext.getString(R.string.aa_no_messages)))
            .setReceivedTimeEpochMillis(System.currentTimeMillis())
            .setRead(true)
            .build()

    private fun buildMainGrid(): GridTemplate {
        val builder = GridTemplate.Builder()
            // Empty title + no header action so AA renders the grid
            // without the "Autogram" wordmark bar. Reclaims ~60dp of
            // vertical space for larger tiles. A single space is safer
            // than null across host versions.
            .setTitle(" ")
            .setActionStrip(buildActionStrip())

        // ITEM_SIZE_LARGE = 4 — biggest tile size AA supports. Uniform
        // across the whole grid (per-item size isn't allowed) so we
        // convey "primary" via ordering + bitmap content, not size.
        runCatching { builder.setItemSize(GridTemplate.ITEM_SIZE_LARGE) }

        val items = ItemList.Builder()

        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        var itemCount = 0

        val pinned = cachedPinned.value
        val primary = pinned.firstOrNull()
        val secondaries = pinned.drop(1)

        // 1. Primary tile first — bitmap fills the whole tile; AA's
        // native Badge API overlays a peach mic dot at the corner.
        if (primary != null && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(tileTitle(primary))
                    .setImage(
                        avatarTileIcon(primary, isPrimary = true),
                        GridItem.IMAGE_TYPE_LARGE,
                        micBadge()
                    )
                    .setOnClickListener { startRecording(primary.id) }
                    .build()
            )
            itemCount++
        }

        // Fresh install with no pinned circles: single Record tile so
        // the user isn't stuck on an empty screen.
        if (pinned.isEmpty() && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.aa_record))
                    .setImage(carIcon(R.drawable.ic_mic, CarColor.PRIMARY), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { startRecording(null) }
                    .build()
            )
            itemCount++
        }

        // 2. Quick-reply shortcuts next. In the car, one-tap shortcuts
        // are more urgent than the 7th pinned contact — a driver's
        // glance sees primary-target + canned replies before having
        // to scroll. Also matches the phone's "chips next to Merlin".
        for (qr in cachedQuickReplies.value) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(qr.label)
                    .setImage(shortcutTileIcon(qr), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { sendShortcut(qr) }
                    .build()
            )
            itemCount++
        }

        // 3. Secondary pinned conversations. Bitmap draws a smaller
        // circle inside the canvas so secondaries render visibly
        // smaller than the primary tile. Native Badge adds the same
        // peach mic dot for affordance consistency.
        for (conv in secondaries) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(tileTitle(conv))
                    .setImage(
                        avatarTileIcon(conv, isPrimary = false),
                        GridItem.IMAGE_TYPE_LARGE,
                        micBadge()
                    )
                    .setOnClickListener { startRecording(conv.id) }
                    .build()
            )
            itemCount++
        }

        // Pending-count tile at the very end when relevant.
        val pendingCount = playReplyUseCase.pendingCount()
        if (pendingCount > 0 && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.aa_pending_count, pendingCount))
                    .setImage(carIcon(R.drawable.ic_inbox, CarColor.YELLOW), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        screenScope.launch { playReplyUseCase.playAllPending() }
                        invalidate()
                    }
                    .build()
            )
        }

        builder.setSingleList(items.build())
        return builder.build()
    }

    /**
     * Tile title that matches the phone home: forum-topic name when the
     * conversation is a topic inside a group (otherwise every secondary
     * would just read as the group's name), falling back to the chat
     * title, then to a hard-coded "Merlin" default for the primary.
     */
    private fun tileTitle(conv: Conversation): String {
        return conv.topicName?.takeIf { it.isNotBlank() }
            ?: conv.targetTitle?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.main_default_primary_name)
    }

    private fun buildActionStrip(): ActionStrip {
        // AA rule: ActionStrip accepts at most ONE action with a custom
        // title; rest must be icon-only.
        // Mute toggle = autoPlayReplies inverted. When OFF (muted),
        // incoming replies queue up for manual drain via the pending
        // tile / phone HeroHeader's "Play N pending" button.
        val isMuted = autoPlayOff.value
        val muteIcon = if (isMuted) R.drawable.ic_silent else R.drawable.ic_volume_up
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(muteIcon, if (isMuted) CarColor.YELLOW else CarColor.DEFAULT))
                    .setOnClickListener { toggleAutoPlay() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_voice_command, CarColor.BLUE))
                    .setTitle(carContext.getString(R.string.aa_voice_command))
                    .setOnClickListener { triggerVoiceCommand() }
                    .build()
            )
            .build()
    }

    private fun triggerVoiceCommand() {
        if (!voiceCommandRecognizer.isAvailable()) {
            screenManager.push(VoiceCommandsHelpScreen(carContext))
            return
        }
        screenScope.launch {
            val command = voiceCommandRecognizer.listenOnce()
            dispatchVoiceCommand(command)
        }
    }

    private fun dispatchVoiceCommand(command: VoiceCommand) {
        Timber.d("VoiceCommand recibido: $command")
        when (command) {
            VoiceCommand.REPETIR -> screenScope.launch { playReplyUseCase.playAllPending() }
            VoiceCommand.CANCELAR -> screenScope.launch { sendMessageUseCase.cancelRecording(); invalidate() }
            VoiceCommand.PAUSAR -> { playReplyUseCase.pause(); invalidate() }
            VoiceCommand.REANUDAR -> { playReplyUseCase.resume(); invalidate() }
            VoiceCommand.ENVIAR -> stopAndSend()
            VoiceCommand.SILENCIO -> toggleAutoPlay()
            VoiceCommand.UNKNOWN -> screenManager.push(VoiceCommandsHelpScreen(carContext))
        }
    }

    private fun buildRecordingTemplate(): MessageTemplate {
        return MessageTemplate.Builder(carContext.getString(R.string.status_recording))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(R.drawable.ic_mic, CarColor.RED))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_stop_send))
                    .setIcon(carIcon(R.drawable.ic_send, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { stopAndSend() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_cancel, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.SECONDARY)
                    .setOnClickListener {
                        screenScope.launch { sendMessageUseCase.cancelRecording() }
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun buildStatusTemplate(message: String): MessageTemplate {
        val (icon, color) = when (sendMessageUseCase.status.value) {
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> R.drawable.ic_send to CarColor.GREEN
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> R.drawable.ic_send to CarColor.BLUE
            is SendVoiceMtprotoUseCase.SendStatus.Error -> R.drawable.ic_cancel to CarColor.RED
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> R.drawable.ic_cancel to CarColor.YELLOW
            else -> R.drawable.ic_mic to CarColor.DEFAULT
        }
        return MessageTemplate.Builder(message)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(icon, color))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.common_back))
                    .setOnClickListener {
                        sendMessageUseCase.reset()
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun startRecording(targetId: String?) {
        resetJob?.cancel()
        screenScope.launch {
            val result = sendMessageUseCase.startRecording(targetId)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Failed to start recording")
            }
            invalidate()
        }
    }

    private fun stopAndSend() {
        resetJob?.cancel()
        screenScope.launch {
            // skipPreview = true: AA has no preview sheet, so honoring the
            // phone's `previewBeforeSend` setting from the car would park
            // the file with no UI to confirm and the voice note would be
            // silently lost. Car sessions send directly.
            val result = sendMessageUseCase.stopAndSend(skipPreview = true)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al enviar")
            }
            invalidate()
        }
    }

    private fun sendShortcut(qr: QuickReply) {
        screenScope.launch {
            when (qr.kind) {
                QuickReplyKind.TEXT -> sendMessageUseCase.sendQuickReply(qr.textContent)
                QuickReplyKind.VOICE -> {
                    val path = qr.audioFilePath
                    if (!path.isNullOrBlank()) {
                        sendMessageUseCase.sendVoiceShortcut(
                            audioFilePath = path,
                            audioDurationMs = qr.audioDurationMs,
                            targetConversationId = null
                        )
                    }
                }
            }
            invalidate()
        }
    }

    private fun toggleAutoPlay() {
        screenScope.launch {
            // autoPlayOff.value == true means muted → flipping unmutes
            // (autoPlayReplies = true). Inversely, !autoPlayOff (currently
            // playing) → mute (autoPlayReplies = false).
            manageSettingsUseCase.updateAutoPlay(autoPlayOff.value)
        }
    }

    private fun carIcon(resId: Int, tint: CarColor): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId))
            .setTint(tint)
            .build()
    }

    /**
     * Native AA Badge for the mic affordance. Unlike the painted-in
     * badge we used before, this is rendered BY the car host on top of
     * the tile icon, so it stays crisp at any car screen resolution and
     * doesn't get crushed when AA downsamples our bitmap.
     */
    private fun micBadge(): androidx.car.app.model.Badge {
        // setBackgroundColor requires setHasDot(true) — Badge throws
        // IllegalStateException otherwise. "Dot" here means the filled
        // circle chrome around the icon; without the dot the background
        // colour has nothing to paint. With hasDot + backgroundColor +
        // icon we get exactly the phone's "peach disc with white mic
        // glyph" affordance, rendered by the car host natively.
        val peach = CarColor.createCustom(COLOR_PEACH_BADGE, COLOR_PEACH_BADGE)
        return androidx.car.app.model.Badge.Builder()
            .setHasDot(true)
            .setBackgroundColor(peach)
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic))
                    .setTint(CarColor.createCustom(android.graphics.Color.WHITE, android.graphics.Color.WHITE))
                    .build()
            )
            .build()
    }

    /**
     * Cached [CarIcon] for a conversation avatar tile. Composites at
     * [AVATAR_TILE_SIZE] px: circle-cropped photo (or silhouette fallback)
     * + gold ring + peach mic badge with a white mic glyph, matching the
     * phone's RecordCircle aesthetic.
     *
     * When [isPrimary] is true the drawn circle fills ~96% of the canvas
     * so Merlin looks visually larger than secondaries (which render at
     * ~76%). AA forces uniform tile container sizes so this is the only
     * way to convey "primary > secondary" at the bitmap level.
     */
    private fun avatarTileIcon(conv: Conversation, isPrimary: Boolean): CarIcon {
        val key = "avatar:${if (isPrimary) "P" else "S"}:${conv.id}:${conv.photoLocalPath ?: ""}:${photoMtime(conv.photoLocalPath)}"
        iconCache[key]?.let { return it }
        val bitmap = renderAvatarTile(conv, isPrimary = isPrimary)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        iconCache[key] = icon
        return icon
    }

    /**
     * Cached [CarIcon] for a quick-reply shortcut tile. Composites a
     * rounded-rectangle chip background + centered Bolt (text) or Mic
     * (voice) icon so the tile reads as a phone chip, visually distinct
     * from the circular avatars it shares the grid with.
     */
    private fun shortcutTileIcon(qr: QuickReply): CarIcon {
        val key = "shortcut:${qr.id}:${qr.kind.name}"
        iconCache[key]?.let { return it }
        val bitmap = renderShortcutTile(qr)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        iconCache[key] = icon
        return icon
    }

    private fun photoMtime(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return runCatching { File(path).lastModified() }.getOrDefault(0L)
    }

    /**
     * Avatar tile at [AVATAR_TILE_SIZE]px. Primary ([isPrimary]=true)
     * fills ~96% of the canvas; secondaries fill ~76% so Merlin reads
     * as visually bigger even though AA forces uniform tile containers.
     *
     * Output canvas is padded with transparent pixels where the circle
     * doesn't reach, so the AA tile shows blank space around smaller
     * secondaries — mimicking the phone's 176dp primary vs 88dp
     * secondary size difference.
     */
    private fun renderAvatarTile(conv: Conversation, isPrimary: Boolean): Bitmap {
        val sizePx = AVATAR_TILE_SIZE
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val center = sizePx / 2f

        // Anchor the canvas at near-zero alpha on all 4 corners. AA's
        // host appears to trim bitmaps to their opaque bounding box
        // before rendering, which was erasing the transparent padding
        // we use to make secondaries look smaller. A ~1.5%-alpha black
        // fill is functionally invisible against AA's dark tile bg but
        // forces the bounding box to include the full canvas, so the
        // size difference between primary and secondary actually
        // survives to the rendered tile.
        canvas.drawColor(0x04000000)

        // Fill ratios: primary fills the entire tile; secondaries
        // shrink to ~55% so the rendered tile shows a visible ≈2× size
        // difference between primary and secondary circles.
        val circleFill = if (isPrimary) 1.00f else 0.55f
        val circleRadius = (sizePx / 2f) * circleFill
        val ringStroke = circleRadius * (if (isPrimary) 0.10f else 0.09f)
        val photoRadius = circleRadius - ringStroke / 2f

        // ── Photo (or person-silhouette fallback) ────────────────────
        val photoSizePx = (photoRadius * 2).toInt()
        val photoBitmap = loadPhotoBitmap(conv, photoSizePx)
        val photoCircled = toCircleBitmap(photoBitmap, photoSizePx)
        canvas.drawBitmap(
            photoCircled,
            center - photoRadius,
            center - photoRadius,
            null
        )

        // ── Gold ring ────────────────────────────────────────────────
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringStroke
            color = COLOR_GOLD_RING
        }
        canvas.drawCircle(center, center, photoRadius + ringStroke / 2f, ringPaint)

        // Mic badge is no longer painted into the bitmap — AA renders
        // it natively via `Badge` on the GridItem. That gives crisper
        // results on high-DPI car screens and survives AA's bitmap
        // downsampling intact.

        return output
    }

    /**
     * Shortcut chip tile: rounded-rectangle dark-brown background with a
     * centered Bolt (text) or Mic (voice) icon tinted to match the phone
     * chip accent (gold for text, peach for voice). Sized to feel like
     * a secondary avatar tile (~76% of the canvas) so the user gets the
     * same visual weight whether they're looking at a contact or a
     * shortcut.
     */
    private fun renderShortcutTile(qr: QuickReply): Bitmap {
        val sizePx = AVATAR_TILE_SIZE
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Same alpha-anchor trick as the avatar tiles: forces AA's
        // renderer to honour the full canvas size rather than cropping
        // to opaque content. Without this the chip was being scaled
        // up to fill the whole tile, losing the intended padding.
        canvas.drawColor(0x04000000)

        // Chip fills nearly the whole canvas so the tile reads as a
        // substantial button, not a tiny mark floating in empty space.
        // Previous 0.76 fill was too timid — after AA's downsample the
        // chip barely registered against the dark tile background.
        val center = sizePx / 2f
        val chipSide = sizePx * 0.94f
        val chipLeft = center - chipSide / 2f
        val chipTop = center - chipSide / 2f
        val chipRect = RectF(chipLeft, chipTop, chipLeft + chipSide, chipTop + chipSide)
        val chipRadius = sizePx * 0.16f

        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_CHIP_BG
        }
        canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipPaint)

        val isVoice = qr.kind == QuickReplyKind.VOICE
        drawDrawableCentered(
            canvas = canvas,
            resId = if (isVoice) R.drawable.ic_mic else R.drawable.ic_bolt,
            tint = if (isVoice) COLOR_PEACH_BADGE else COLOR_GOLD_RING,
            cx = center,
            cy = center,
            sizePx = chipSide * 0.60f
        )

        return output
    }

    /**
     * Load the conversation's photo as a square bitmap of [sizePx],
     * falling back to the tinted person silhouette when no file is set
     * or the decode fails. Silhouette gets a muted dark background so
     * it reads as an intentional empty-state, not a missing-image error.
     */
    private fun loadPhotoBitmap(conv: Conversation, sizePx: Int): Bitmap {
        val path = conv.photoLocalPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                val decoded = runCatching { decodeSampledPhoto(path, sizePx) }.getOrNull()
                if (decoded != null) return decoded
            }
        }
        val fallback = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fallback)
        canvas.drawColor(COLOR_SILHOUETTE_BG)
        drawDrawableCentered(
            canvas = canvas,
            resId = R.drawable.ic_person,
            tint = Color.WHITE,
            cx = sizePx / 2f,
            cy = sizePx / 2f,
            sizePx = sizePx * 0.55f
        )
        return fallback
    }

    private fun decodeSampledPhoto(path: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > targetPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun toCircleBitmap(src: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val srcShort = minOf(src.width, src.height)
        val srcLeft = (src.width - srcShort) / 2
        val srcTop = (src.height - srcShort) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + srcShort, srcTop + srcShort)
        val dstRect = Rect(0, 0, sizePx, sizePx)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return output
    }

    /**
     * Render a vector drawable into [canvas] centered at ([cx], [cy])
     * with the requested tint and pixel size. Uses a throwaway bitmap
     * because Drawable.draw + Canvas doesn't antialias vectors by default
     * on all API levels.
     */
    private fun drawDrawableCentered(
        canvas: Canvas,
        resId: Int,
        tint: Int,
        cx: Float,
        cy: Float,
        sizePx: Float
    ) {
        val drawable = ContextCompat.getDrawable(carContext, resId)?.mutate() ?: return
        val intSize = sizePx.toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(intSize, intSize, Bitmap.Config.ARGB_8888)
        val tmpCanvas = Canvas(bitmap)
        drawable.setTint(tint)
        drawable.setBounds(0, 0, intSize, intSize)
        drawable.draw(tmpCanvas)
        canvas.drawBitmap(bitmap, cx - intSize / 2f, cy - intSize / 2f, null)
    }

    companion object {
        // Source bitmap size for every tile. The 2024 Seat Ateca's MIB3
        // screen at 9.2" is 1280×640 native → AA projects tiles around
        // 280-320px wide at ITEM_SIZE_LARGE. 768px gives ~2.5× oversample
        // for crisp downsampling (antialiased downscales look sharper
        // than a 1:1 match at AA's render-time filter settings).
        // Memory: 768×768×4B = 2.3MB per tile × ~10 tiles = ~23MB
        // cached, which is fine for a single-screen app.
        private const val AVATAR_TILE_SIZE: Int = 768

        // Match the phone theme (see ui/theme/Color.kt extended colors).
        // Gold ring around each avatar + on the Bolt shortcut icon.
        private const val COLOR_GOLD_RING: Int = 0xFFD4A44C.toInt()
        // Peach/coral mic badge + on the Mic shortcut icon.
        private const val COLOR_PEACH_BADGE: Int = 0xFFFFA66A.toInt()
        // Dark-brown shortcut chip background (tonalContainer dark).
        private const val COLOR_CHIP_BG: Int = 0xFF3D2F26.toInt()
        // Muted grey-blue behind the person-silhouette fallback so the
        // empty avatar doesn't read as a missing-image error.
        private const val COLOR_SILHOUETTE_BG: Int = 0xFF3E4A56.toInt()
    }
}
