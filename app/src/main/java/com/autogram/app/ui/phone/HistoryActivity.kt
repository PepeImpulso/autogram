package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * v1.21.0 — hosts the unified History + Pending Queue screen. Reads
 * [EXTRA_INITIAL_TAB] to decide which tab the screen opens on; the home
 * banner deep-links into the Pending tab so a user with queued messages
 * still lands where they expect, and entry from the navbar / per-circle
 * badge defaults to History. The standalone PendingQueueActivity was
 * removed in this version: its only entry point was the home banner,
 * which made the screen unreachable when no messages were pending.
 */
@AndroidEntryPoint
class HistoryActivity : BaseLocaleActivity() {
    private val historyViewModel: HistoryViewModel by viewModels()
    private val pendingViewModel: PendingQueueViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = when (intent.getStringExtra(EXTRA_INITIAL_TAB)) {
            TAB_PENDING -> HistoryTab.PENDING
            else -> HistoryTab.HISTORY
        }
        setContent {
            AutogramTheme {
                DebugScreenshotOverlay {
                    HistoryWithPendingTabsScreen(
                        historyViewModel = historyViewModel,
                        pendingViewModel = pendingViewModel,
                        initialTab = initialTab,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Optional intent extra: open History scoped to a specific
         * conversation instead of the currently-active target. Used by
         * the per-circle pending badges on the Home screen
         * (v1.20.14) so the user can tap on the "1" next to a circle
         * and land on that circle's message history.
         */
        const val EXTRA_CONVERSATION_ID: String = "conversationId"

        /**
         * Optional intent extra picking the tab to open on. Accepts
         * [TAB_PENDING] or [TAB_HISTORY]; anything else (or absent)
         * defaults to History.
         */
        const val EXTRA_INITIAL_TAB: String = "initialTab"
        const val TAB_PENDING: String = "pending"
        const val TAB_HISTORY: String = "history"
    }
}
