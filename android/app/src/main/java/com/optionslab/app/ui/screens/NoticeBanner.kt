package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.work.NoticeCard
import com.optionslab.app.work.NoticeCards
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A tapped notification, opened over the app (Boss, 5 Oct): its title, when it came and its whole text, so it is read
 * here rather than found at the bottom of the chat (where it still is). One that asks something shows that question's
 * own buttons - never a shortcut:
 *
 *  - a request of Jarvis's waiting for Confirm (a news trade, a stop, a close, the kill switch): the chat's own
 *    [ActionConfirm] - Confirm / Cancel, and for a trade going to Zerodha "Approve with fingerprint";
 *  - a strategy Jarvis backtested: the chat's own [ProposalActions] (Approve as a paper arm / Dismiss);
 *  - a position's card: Close opens the app's own close popup for it ([onClose]: the review, the slide, and for
 *    Zerodha the PIN / fingerprint, exactly as tapping the position's row);
 *  - an arm's entry or a strategy's start waiting for approval: Home's own rows ([entries]: [OrbRows] /
 *    [StrategyArmCard], their Approve / Skip and, in Live, the PIN or fingerprint);
 *  - a Settings row: the app has already opened it, highlighted ([com.optionslab.app.ui.SettingSpot]).
 *
 * Anything no longer waiting (answered, lapsed, the position already closed) says so and shows no button. It is only
 * ever composed inside the unlocked app (the main screen), so nothing of the account shows over the lock.
 */
@Composable
internal fun NoticeBanner(
    card: NoticeCard,
    onDismiss: () -> Unit,
    onClose: (venue: String, symbol: String) -> Unit,
    /** Test seam: whether the position a card offers to close is still open (null: could not tell - the popup checks). */
    stillOpen: suspend (venue: String, symbol: String) -> Boolean? = { v, s -> positionStillOpen(v, s) },
    /**
     * Home's own rows for an entry waiting for approval ([NoticeCard.approve]: "orb" -> the ORB arms' rows, "strategy"
     * -> the Strategies card), with their Approve / Skip and, in Live, the PIN or fingerprint. Null: only the text.
     */
    entries: (@Composable (kind: String) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val setting = NoticeCards.route(card) is NoticeCards.Route.Setting
    // Back closes the banner first (it was registered after the page's own Back, so it is asked first).
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // A Settings notice leaves the highlighted row in sight: a strip at the bottom, nothing dimmed. Anything else is a
    // card at the top over a dimmed screen (a tap outside it closes it).
    Box(
        Modifier.fillMaxSize()
            .then(if (setting) Modifier else Modifier.background(Color.Black.copy(alpha = 0.35f))
                // (A gesture, not a clickable: the banner's words stay separate for TalkBack, not merged into one node.)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } })
            .statusBarsPadding().navigationBarsPadding().padding(12.dp),
        contentAlignment = if (setting) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("notice-banner")
                .background(p.card, RoundedCornerShape(16.dp))
                .border(1.dp, if (NoticeCards.asks(card)) p.oxblood.copy(alpha = 0.6f) else p.rule, RoundedCornerShape(16.dp))
                // Taps on the card itself stay on it (not the dimmed screen's close).
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(card.title, style = Type.title.copy(color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Text(whenText(card.at), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
            Column(Modifier.heightIn(max = if (setting) 160.dp else 320.dp).verticalScroll(rememberScrollState())) {
                Text(card.text, style = Type.body.copy(color = p.ink))
            }
            card.action?.let { ActionPart(it) }
            card.proposal?.let { ProposalPart(it) }
            card.approve?.let { kind ->
                if (entries == null) Note("Approve or skip it on Home while it waits.")
                else {
                    Note("Approve or skip it here while it waits; once it has lapsed or been answered, no button shows.")
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { entries(kind) }
                }
            }
            val venue = card.closeVenue; val symbol = card.closeSymbol
            if (venue != null && symbol != null) {
                val open by produceState<Boolean?>(null, venue, symbol) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { stillOpen(venue, symbol) }
                }
                if (open == false) Note("$symbol is no longer open: nothing to close.")
                else BrassButton(if (venue == "Paper") "Close position…" else "Close… (review, then PIN or fingerprint)", Modifier.fillMaxWidth(),
                    tone = p.oxblood) { onClose(venue, symbol); onDismiss() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.optionslab.app.ui.components.TextButton(onDismiss) { Text(if (NoticeCards.asks(card)) "Later" else "OK") }
            }
        }
    }
}

/** A request of Jarvis's: the chat's own Confirm / Cancel while it waits; once answered, his reply; else why not. */
@Composable
private fun ActionPart(id: Long) {
    val waitingAtOpen = remember(id) { id in IraHub.state.value.pending }
    val seen = remember(id) { IraHub.state.value.messages.size }
    val waitingNow by iraSlice(id) { id in it.pending }
    // Read only while it matters (after the answer): Jarvis's last reply since the card opened.
    val reply by iraSlice(seen) { s -> s.messages.drop(seen).lastOrNull { it.fromIra }?.text }
    when {
        waitingNow -> ActionConfirm(id)
        waitingAtOpen -> {
            // Answered here: what Jarvis said back (the chat has it too).
            Note(reply?.let { "Done. Jarvis: $it" } ?: "Answered.")
        }
        else -> Note("This is no longer waiting: it was answered, or it lapsed and nothing was done.")
    }
}

/** A strategy of Jarvis's: the chat's own Approve / Dismiss while it is new; else what became of it. */
@Composable
private fun ProposalPart(id: Long) {
    val offered by iraSlice(id) { s -> s.proposals.any { it.id == id } }
    if (!offered) Note("That strategy is no longer offered.")
    else ProposalActions(id)
}

private fun whenText(at: Long): String = runCatching {
    Instant.ofEpochMilli(at).atZone(com.optionslab.engine.IST).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH))
}.getOrDefault("")

/**
 * Is [symbol] still held on [venue] ("Paper" / "Live")? Read from the paper book, or Zerodha's position book when
 * logged in; null when it cannot be told (the close popup then checks it itself, as from the row).
 */
internal suspend fun positionStillOpen(venue: String, symbol: String): Boolean? =
    if (venue == "Paper") runCatching { com.optionslab.app.data.Paper.state.positions.any { it.symbol == symbol && it.quantity != 0 } }.getOrNull()
    else if (!com.optionslab.app.data.Broker.loggedIn) null
    else runCatching { com.optionslab.app.data.Broker.positionBook().net.any { it.symbol == symbol && it.qty != 0 } }.getOrNull()
