package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Debug-only host for repeatable Google Play screenshots with synthetic data. */
@AndroidEntryPoint
class StoreScreenshotActivity : BaseLocaleActivity() {
    @Inject lateinit var dataSeeder: SyntheticReviewDataSeeder
    @Inject lateinit var conversations: ConversationRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { dataSeeder.seed(clearExisting = true) }
            render(intent.getStringExtra(EXTRA_SCREEN).orEmpty())
        }
    }

    private fun render(screen: String) {
        setContent {
            AutogramTheme {
                when (screen) {
                    SCREEN_HISTORY -> HistoryWithPendingTabsScreen(
                        historyViewModel = hiltViewModel(),
                        pendingViewModel = hiltViewModel(),
                        initialTab = HistoryTab.HISTORY,
                        onBack = {}
                    )
                    SCREEN_SETTINGS_PRIVACY -> SettingsScreen(
                        viewModel = hiltViewModel(),
                        onBack = {},
                        initialScrollOffset = 9_000
                    )
                    SCREEN_ONBOARDING_RECORD -> OnboardingScreen(
                        activeTargetFlow = conversations.observeActive(),
                        onFinish = {},
                        onChooseTarget = {},
                        initialStep = 1
                    )
                    SCREEN_TERMS -> Surface(modifier = Modifier.fillMaxSize()) {
                        AcceptableUseScreen(onAccept = {}, onExit = {})
                    }
                    else -> MainScreen(
                        onOpenSettings = {},
                        onOpenHistory = {},
                        onOpenHistoryFor = {},
                        onOpenStats = {},
                        onOpenPendingQueue = {},
                        onManagePinned = {},
                        onManageQuickReplies = {}
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN: String = "screen"
        const val SCREEN_HOME: String = "home"
        const val SCREEN_HISTORY: String = "history"
        const val SCREEN_SETTINGS_PRIVACY: String = "settings_privacy"
        const val SCREEN_ONBOARDING_RECORD: String = "onboarding_record"
        const val SCREEN_TERMS: String = "terms"
    }
}
