package com.autogram.app.ui.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind

/**
 * v1.24.1 — child Screen that the per-row "Reply" Action on the Inbox
 * tab pushes onto AA's Screen stack. AA's host doesn't expose a public
 * way to programmatically open the inline reply input from an Action's
 * onClickListener, so the explicit Reply UX takes the user to this
 * dedicated screen instead.
 *
 *   - Lists every enabled [QuickReply] as a tappable Row. Tap →
 *     [onSend] (caller invokes `SendVoiceMtprotoUseCase.sendQuickReply`
 *     pinned to [conversation]).
 *   - One final Row "Record voice reply" → [onRecord] (caller hops
 *     back to MainScreen and starts recording on [conversation]), or
 *     "Stop and send" when [isRecording] is true.
 *   - Back action returns to MainScreen / Inbox tab.
 *
 * Coexists with the implicit reply input AA's host renders via
 * `ConversationCallback.onTextReply` — hosts that support it keep
 * working; hosts that don't (Pepe's Seat Ateca) finally have a visible
 * reply entry point.
 */
class ConversationReplyScreen(
    carContext: CarContext,
    private val conversation: Conversation,
    private val quickReplies: List<QuickReply>,
    private val onSend: (QuickReply) -> Unit,
    private val isRecording: Boolean,
    private val onRecord: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        val enabled = quickReplies.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            items.setNoItemsMessage(
                carContext.getString(R.string.aa_shortcuts_empty)
            )
        } else {
            for (qr in enabled) {
                val iconRes = if (qr.kind == QuickReplyKind.VOICE)
                    R.drawable.ic_mic else R.drawable.ic_bolt
                items.addItem(
                    Row.Builder()
                        .setTitle(qr.label)
                        .setImage(carIcon(iconRes, CarColor.PRIMARY))
                        .setOnClickListener {
                            onSend(qr)
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }
        // Always offer the Record-voice option at the end so the user has
        // a reply path even when no quick replies are configured. It is a
        // toggle (#104): while this conversation is recording it stops and
        // sends instead.
        items.addItem(
            Row.Builder()
                .setTitle(
                    carContext.getString(
                        if (isRecording) R.string.aa_stop_send else R.string.aa_detail_record
                    )
                )
                .setImage(
                    if (isRecording) carIcon(R.drawable.ic_send, CarColor.RED)
                    else carIcon(R.drawable.ic_mic, CarColor.PRIMARY)
                )
                .setOnClickListener {
                    onRecord()
                    screenManager.pop()
                }
                .build()
        )

        val convTitle = conversation.topicName?.takeIf { it.isNotBlank() }
            ?: conversation.targetTitle?.takeIf { it.isNotBlank() }
            ?: carContext.getString(R.string.main_default_primary_name)
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.aa_detail_quick_reply) + " · " + convTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    private fun carIcon(resId: Int, tint: CarColor): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId))
            .setTint(tint)
            .build()
}
