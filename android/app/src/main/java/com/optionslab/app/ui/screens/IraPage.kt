package com.optionslab.app.ui.screens

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ira.IraOrders
import com.optionslab.app.ira.JarvisVoice
import com.optionslab.app.ira.IraModel.Status as ModelStatus
import com.optionslab.app.ira.OrbView
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.optionslab.ira.Market as IraMarket

/**
 * Jarvis's Home: Ira first, the usual dashboard (prices, P&L, strategies) behind the second switch. The choice is
 * kept while the app runs. Over the Ira page, a one-row "What's new" header while changes are unseen ([WhatsNewOverPage]; the
 * same list and "Got it" as the Dashboard's); [onGo]: a change's page, as Home's shortcuts (a change for the Ira page itself
 * is no link there).
 */
@Composable
fun IraHome(orders: IraOrderPaths? = null, onGo: ((String) -> Unit)? = null, dashboard: @Composable () -> Unit) {
    val p = LocalPalette.current
    var showIra by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    // The Requests panel from Home (Boss, 5 Oct): its badge beside the switch; Back closes it.
    var homeRequests by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = homeRequests) { homeRequests = false }
    // Jarvis: listening was left on - start it again now the app is on screen (Android allows it only then).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { if (com.optionslab.app.BuildConfig.JARVIS && JarvisVoice.wanted) JarvisVoice.start(ctx) }
    // Voice off: the AI model is loaded while Boss reads the page, so the first typed question is answered sooner.
    LaunchedEffect(Unit) { if (com.optionslab.app.BuildConfig.JARVIS && !JarvisVoice.listenOn) com.optionslab.app.ira.IraModel.preload() }
    // And the voice for typed replies, started ahead (the first reply is spoken at once).
    LaunchedEffect(Unit) { runCatching { com.optionslab.app.ira.JarvisSpeaker.warm(ctx) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).background(p.card, RoundedCornerShape(12.dp)).padding(4.dp)) {
            listOf("Ira" to true, "Dashboard" to false).forEach { (label, ira) ->
                val on = showIra == ira
                Text(label, style = Type.label.copy(color = if (on) p.onPrimary else p.inkSoft, fontSize = 14.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (on) p.brass else Color.Transparent, RoundedCornerShape(9.dp))
                        .clickable { showIra = ira; homeRequests = false }.padding(vertical = 8.dp))
            }
            RequestsBadge(Modifier.align(Alignment.CenterVertically).padding(start = 6.dp)) { homeRequests = true }
        }
        Box(Modifier.weight(1f)) { if (homeRequests) RequestsPanel(onClose = { homeRequests = false }) else if (showIra) WhatsNewOverPage(onGo) { IraPage(orders) } else dashboard() }
    }
}

/** Example questions shown before the first one is asked. */
private val EXAMPLES = listOf("What is BankNifty doing today?", "Nifty levels", "Is gold up today?", "Any news on banks?", "Backtest the breakout on BankNifty 15m", "Any pattern on FinNifty?", "Buy 1 lot Nifty ATM CE", "Should I trade now?", "What did you study last night?", "Nifty PCR and max pain", "What did FIIs do?", "My weekly review", "Analyze my orders", "Any events this week?", "What can you do?")

/**
 * Ira: the orb (the market at a glance) above the conversation. Answers come from IraAlgo's own data only; an order
 * request goes through [orders] (the app's own paths) only after the owner confirms it.
 */
/**
 * Speed, round 2: one slice of [IraHub.state] - the proposals, what waits, the snapshots - so a new or rewritten message
 * recomposes only what shows it. [pick] reads the whole state; the caller recomposes only when what it picks changes
 * ([policy]: by equality, or by identity for big values the hub rebuilds as a whole). [key]: what [pick] depends on.
 */
@Composable
internal fun <T> iraSlice(key: Any? = null,
                          policy: androidx.compose.runtime.SnapshotMutationPolicy<T> = androidx.compose.runtime.structuralEqualityPolicy(),
                          pick: (IraHub.State) -> T): androidx.compose.runtime.State<T> {
    val all = IraHub.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    return remember(all, key) { androidx.compose.runtime.derivedStateOf(policy) { pick(all.value) } }
}

/** [startInChat]: open on the chat, not the globe (a question asked from Settings → What can I ask? shows its reply). */
@Composable
fun IraPage(orders: IraOrderPaths? = null, startInChat: Boolean = false) {
    val p = LocalPalette.current
    // The whole state is read only inside the conversation's list (its own scope); the page itself reads slices.
    val st by IraHub.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val snaps by iraSlice(policy = androidx.compose.runtime.referentialEqualityPolicy()) { it.snaps }
    val working by iraSlice { it.busy || it.loading }
    val waitingCount by iraSlice { it.pending.size }
    val newest by iraSlice { s -> s.messages.lastOrNull()?.let { it.id to it.text } }
    // The saved conversation is read off the main thread at the start: until then the chat says so (no examples).
    val memoryReady by IraHub.ready.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val voice by JarvisVoice.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    // "Don't listen" (Boss, 6 Oct): the same switch as in Settings - the globe says so plainly while it is on.
    val deafNow by JarvisVoice.deafState.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var typed by remember { mutableIntStateOf(0) }
    // The orb shows the typed exchange first, else what the voice is doing (plain listening for the name is "idle").
    val mode = if (typed != 0) typed else when (voice.mode) {
        JarvisVoice.Mode.AWAKE -> 1; JarvisVoice.Mode.THINKING -> 2; JarvisVoice.Mode.SPEAKING -> 3; else -> 0 }
    // At rest, say plainly whether Jarvis can hear its name ("Idle" read the same with the voice off).
    val restLabel = when {
        com.optionslab.app.BuildConfig.JARVIS && deafNow -> "Not listening"
        voice.mode == JarvisVoice.Mode.LISTENING -> "Say Jarvis"
        voice.problem != null || voice.mode == JarvisVoice.Mode.OFF -> "Voice off"
        else -> "Idle"
    }
    fun orbLabel(m: Int) = if (m == 0) restLabel else listOf("Idle", "Listening", "Thinking", "Answering")[m]
    var focus by remember { mutableStateOf(IraMarket.NIFTY) }
    // Live prices every minute while Ira is on screen (and news every ten minutes, inside the hub). Battery (round 10): a
    // read begun under 50 s ago (the listening loop's, the feed check's) is shared, not made again ([com.optionslab.ira.LiveReadPace]).
    com.optionslab.app.ui.PollWhileStarted { while (true) { IraHub.refreshIfDue(quiet = false); delay(60_000) } }
    val list = rememberLazyListState()
    // The newest message in view: after each new or rewritten one (the cards above it counted).
    LaunchedEffect(newest) {
        if (newest != null) list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    val ctxSpeak = androidx.compose.ui.platform.LocalContext.current
    fun send(q: String) {
        if (q.isBlank()) return
        IraMarket.mentioned(q).firstOrNull()?.let { focus = it }
        text = ""
        // The answer is ready at once (it is built from facts); the orb still shows a beat of thinking, then answers.
        // Read off the screen's thread (IraHub.askSoon); the spoken reply waits until the question is taken in, as before.
        // Jarvis: the reply to a typed question is said aloud too (the owner's switch, on by default). [askAsTyped]: the
        // question guide asks the same way.
        askAsTyped(ctxSpeak, scope, q)
        typed = 2
        scope.launch { delay(600); typed = 3; delay(1_800); typed = 0 }
    }

    // Jarvis: only the globe until the owner opens the chat (the owner's wish, 2026-10-02); voice works either way.
    var chat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startInChat || !com.optionslab.app.BuildConfig.JARVIS) }
    // The Requests panel (Boss, 5 Oct): what waits for his yes, apart from the chat. Back closes it.
    var requestsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = requestsOpen) { requestsOpen = false }
    if (requestsOpen) { RequestsPanel(onClose = { requestsOpen = false }); return }
    // "What can I ask?" (06 Oct): the question guide from the "?" beside the question box. A tap asks the question exactly
    // as typed ([send]) and closes it, back to the chat with the question in it; Back closes it too.
    var guideOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = guideOpen) { guideOpen = false }
    if (guideOpen) { AskGuideSheet(onAsk = { q -> guideOpen = false; send(q) }, onClose = { guideOpen = false }); return }
    // "Today's notes" (06 Oct): what Jarvis posted by himself today, from the chip in the chat's header row. "Turn these off"
    // only opens that switch in Settings → Jarvis (the row brought into view); Back closes it. Read only.
    var notesOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = notesOpen) { notesOpen = false }
    if (notesOpen) {
        TodayNotesSheet(rememberTodayNotes(), onTurnOff = { key -> notesOpen = false; com.optionslab.app.ui.SettingFocus.open("jarvis", key) },
            onClose = { notesOpen = false })
        return
    }
    // The phone's Back closes the chat (back to the globe) in Jarvis.
    androidx.activity.compose.BackHandler(enabled = chat && com.optionslab.app.BuildConfig.JARVIS) { chat = false }
    if (!chat) {
        var quick by remember { mutableStateOf(false) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val writing by com.optionslab.app.ira.IraModel.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
        // Analysing in the background (reading the market, a backtest, the model writing) shows as thinking too.
        val orbMode = when {
            mode == 0 && text.isNotEmpty() -> 1
            mode == 0 && (working || writing.writing) -> 2
            else -> mode
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(Alignment.Center)) {
                Orb(vol = orbVol(snaps), trend = orbTrend(snaps[focus]), mode = orbMode,
                    onLongPress = if (com.optionslab.app.BuildConfig.JARVIS) ({ quick = true }) else null)
            }
            // Long-press the globe: the quick commands.
            if (quick) QuickCommands(onDismiss = { quick = false }) { q, showChat ->
                quick = false
                // Starting or stopping everything from a menu tap always waits for Confirm (a mis-tap does nothing).
                val asked = IraHub.askSoon(q, confirmed = showChat)
                scope.launch { asked.join(); com.optionslab.app.ira.JarvisSpeaker.replyTo(ctx, q) }
                if (showChat) chat = true
            }
            Row(Modifier.align(Alignment.TopCenter).padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                // Not listening: a crossed-out microphone beside the word, so it reads at a glance.
                if (com.optionslab.app.BuildConfig.JARVIS && deafNow && orbMode == 0) {
                    MicGlyph(crossed = true, color = Color(0xFF4AA8FF)); Spacer(Modifier.width(6.dp))
                }
                Text(orbLabel(orbMode).uppercase(),
                    style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 12.sp, letterSpacing = 3.sp))
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val waiting = waitingCount
                // Muted: said plainly on the globe, one tap to hear Jarvis again.
                var mutedNow by remember { mutableStateOf(JarvisVoice.muted) }
                com.optionslab.app.ui.PollWhileStarted { while (true) { mutedNow = JarvisVoice.muted; kotlinx.coroutines.delay(2_000) } }
                if (mutedNow) BrassButton("🔇  Muted · tap to unmute") { JarvisVoice.muted = false; mutedNow = false }
                if (com.optionslab.app.BuildConfig.JARVIS) DontListenButton(deafNow)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // No Talk while the microphone is off: nothing could hear it.
                    if (!deafNow) MicButton("🎙  Talk")
                    BrassButton(if (waiting > 0) "Open chat · $waiting waiting" else "Open chat") { chat = true }
                }
                RequestsBadge { requestsOpen = true }
            }
        }
        if (com.optionslab.app.BuildConfig.JARVIS) ModelAsk()
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(if (com.optionslab.app.BuildConfig.JARVIS) Color.Black else Color.Transparent),
            verticalAlignment = Alignment.CenterVertically) {
            if (com.optionslab.app.BuildConfig.JARVIS) Text("‹  Back to Jarvis", style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 14.sp),
                modifier = Modifier.weight(1f).clickable { chat = false }.padding(horizontal = 14.dp, vertical = 8.dp))
            else Spacer(Modifier.weight(1f))
            // Today's notes: what Jarvis said by himself today (in the header, so the question box keeps its room).
            if (todayNotesShown()) TodayNotesChip(onOpen = { notesOpen = true }, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp))
            RequestsBadge(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { requestsOpen = true }
        }
        // The keyboard is up: the globe steps aside so the question box and Ask keep their room.
        val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (!imeOpen) Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black)) {
            val s = snaps[focus]
            // Tapping the globe in the chat hides the chat again (Jarvis).
            Orb(vol = orbVol(snaps), trend = orbTrend(s), mode = if (mode == 0 && text.isNotEmpty()) 1 else mode,
                onTap = if (com.optionslab.app.BuildConfig.JARVIS) ({ chat = false }) else null)
            Text(orbLabel(if (mode == 0 && text.isNotEmpty()) 1 else mode).uppercase(),
                style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 11.sp, letterSpacing = 2.sp), modifier = Modifier.padding(12.dp))
            s?.let { snap ->
                Text(headline(snap), style = Type.label.copy(color = Color(0xFFB8C0E8), fontSize = 12.sp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LedgerCard(title = "Ira") {
                    Note("Ask about Nifty, BankNifty, FinNifty, Sensex, India VIX or gold. Ira answers only from the market data and news it reads, " +
                        "and says when it does not know. It never gives buy or sell advice. An order you ask for is sent only after you confirm it " +
                        "(Paper: Confirm; Live: the order review, swipe and PIN).")
                    val day = st.lastDay?.let { " Latest data: ${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} ${it.year}." } ?: ""
                    Note(when {
                        !memoryReady -> "Reading what I remember..."
                        st.loading -> "Reading the market data..."
                        st.problem != null -> st.problem!!
                        else -> "${st.days} days of candles read; ${st.learned} pattern outcomes learned.$day " + liveLine(st)
                    })
                }
            }
            // Its own slice (speed round 3): a new message or a busy flag no longer recomposes this card.
            item { val record by iraSlice { IraRecord.of(it) }; HowIraIsDoing(record) }
            if (com.optionslab.app.BuildConfig.JARVIS) item { JarvisStudyCard() }
            // Jarvis's weekly review: the newest week's three sentences, the whole review and the last 12 weeks (not IraGoldAlgo).
            if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD) item { WeeklyReviewSlot() }
            if (st.messages.isEmpty() && memoryReady) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXAMPLES.forEach { q ->
                            Text(q, style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                                modifier = Modifier.background(p.card, RoundedCornerShape(16.dp)).clickable { send(q) }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            // Each reply with the question just before it (null: a note Jarvis posted by himself), for its short line.
            val convo = st.messages
            itemsIndexed(convo, key = { _, msg -> msg.id }, contentType = { _, _ -> "message" }) { idx, m ->
                Bubble(m, orders, if (m.fromIra) convo.getOrNull(idx - 1)?.takeIf { prev -> !prev.fromIra }?.text else null)
            }
            if (st.messages.isNotEmpty()) item {
                Text("Forget this conversation", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp),
                    modifier = Modifier.clickable { IraHub.forgetConversation(); com.optionslab.ira.ShortAnswer.clearCache() }.padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // "What can I ask?": every question Jarvis answers, by topic (48dp; the box keeps the rest of the row).
            AskGuideChip(onOpen = { guideOpen = true })
            OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text("Ask Ira about the market") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(text) }))
            Spacer(Modifier.width(8.dp))
            if (com.optionslab.app.BuildConfig.JARVIS && text.isBlank()) MicButton("🎙")
            else BrassButton("Ask", enabled = text.isNotBlank()) { send(text) }
        }
    }
}

@Composable
private fun Bubble(m: IraHub.Msg, orders: IraOrderPaths?, asked: String? = null) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    // Short answers (Boss, 5 Oct; his choice, the default): one precise line, the full answer behind "Details". Never an
    // order, a trade proposal, a confirm or a message still being written ([com.optionslab.ira.ShortAnswer] keeps
    // questions, confirms, warnings, staleness and "could not read" notes).
    val keepWhole = !m.fromIra || m.whole || m.order != null || m.proposal != null || m.action != null || m.writing
    val shortOn = remember { runCatching { com.optionslab.app.ira.IraTools.shortAnswers }.getOrDefault(true) }
    val brief = remember(m.text, asked, shortOn, keepWhole) {
        if (keepWhole) com.optionslab.ira.ShortAnswer.Short(m.text, null)
        // Speed, round 7: read once per (question, answer, choice) for the app's life, not each time the bubble comes back into the list.
        else runCatching { com.optionslab.ira.ShortAnswer.cached(asked, m.text, shortOn) }.getOrDefault(com.optionslab.ira.ShortAnswer.Short(m.text, null))
    }
    var showDetails by remember { mutableStateOf(false) }
    val shownText = if (showDetails || brief.details == null) m.text else brief.line
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromIra) Alignment.Start else Alignment.End) {
        Text(if (m.fromIra) "IRA" else "YOU", style = Type.label.copy(color = if (m.fromIra) Color(0xFF4AA8FF) else p.inkSoft, fontSize = 10.sp, letterSpacing = 2.sp))
        Text(if (m.fromIra && com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.Address.boss(shownText) else shownText, style = Type.label.copy(color = p.ink, fontSize = 15.sp),
            modifier = Modifier.background(p.card, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        if (brief.details != null) Text(if (showDetails) "Hide details" else "Details · or say \"more\"", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.clickable { showDetails = !showDetails }.padding(top = 4.dp))
        // A request's whole words behind its chat line (a news trade's risk, IV, cautions, turn-downs, confidence).
        m.details?.let { reqDetails ->
            var showReqDetails by remember(m.id) { mutableStateOf(false) }
            Text(if (showReqDetails) "Hide details" else "Details", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
                modifier = Modifier.clickable { showReqDetails = !showReqDetails }.padding(top = 4.dp))
            if (showReqDetails) Text(reqDetails, style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 2.dp))
        }
        if (m.fromIra && com.optionslab.app.BuildConfig.JARVIS && !m.writing) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text("▶ Listen", style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 13.sp),
                modifier = Modifier.clickable { com.optionslab.app.ira.JarvisSpeaker.speak(ctx, shownText) }.padding(top = 4.dp, bottom = 2.dp))
        }
        m.order?.let { o ->
            if (o.missing.isNotEmpty() || o.refusal != null) Note("Nothing was sent.")
            else if (orders == null) Note("Orders from Ira work on the Home screen; nothing was sent.")
            else OrderActions(o, orders)
        }
        m.proposal?.let { id -> ProposalActions(id) }
        m.action?.let { id -> ActionConfirm(id) }
        if (m.fromIra && m.action == null && m.order == null && m.proposal == null) OfferButtons(m.id)
        if (m.writing) Text("Jarvis is writing this on the phone...", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
        m.draft?.let { d ->
            var showDraft by remember { mutableStateOf(false) }
            Text(if (showDraft) "Ira's words: $d" else "Written on the phone by ${com.optionslab.app.ira.IraModel.NAME} · every number checked · show Ira's words",
                style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.clickable { showDraft = !showDraft }.padding(top = 4.dp))
        }
        if (m.fromIra && m.facts.isNotEmpty()) {
            Text(if (open) "Hide the facts used" else "Facts used (${m.facts.size})", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
                modifier = Modifier.clickable { open = !open }.padding(top = 4.dp))
            if (open) m.facts.forEach { f -> Text("• $f", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp)) }
        }
    }
}

/** Approve / Dismiss under a strategy Jarvis backtested, or what was decided. */
@Composable
internal fun ProposalActions(id: Long) {
    val found by iraSlice(id) { s -> s.proposals.firstOrNull { it.id == id } }
    val p = found ?: return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (p.status) {
            IraHub.Proposal.NEW -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrassButton(if (p.result.recommended) "Approve as a paper arm" else "Add anyway (paper)") { scope.launch { IraHub.approve(id) } }
                BrassButton("Dismiss", tone = LocalPalette.current.inkSoft) { IraHub.dismiss(id) }
            }
            IraHub.Proposal.APPROVED -> Note("Approved: armed on paper in Research → Pine.")
            else -> Note("Dismissed.")
        }
        Text(if (open) "Hide the strategy" else "See the strategy (Pine)", style = Type.label.copy(color = LocalPalette.current.inkSoft, fontSize = 12.sp),
            modifier = Modifier.clickable { open = !open })
        if (open) Text(p.result.script, style = Type.label.copy(color = LocalPalette.current.inkSoft, fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
    }
}

/**
 * The app's own order paths, as Ira uses them. [paper] places on the paper account; [review] opens IraAlgo's order
 * review (swipe and PIN) for Zerodha. Read at the moment of the tap: [live] and [maxLots] from the settings.
 */
class IraOrderPaths(val live: () -> Boolean, val maxLots: () -> Int,
                    val paper: (IraOrders.Ticket) -> Unit, val review: (IraOrders.Ticket) -> Unit)

/** [IraOrderPaths] on the app's [model]: a market order (the review shows the best price for Live), the usual product. */
fun iraOrderPathsFor(model: com.optionslab.app.ui.AppModel) = with(model) { com.optionslab.app.ira.IraActions.attach(model); IraOrderPaths(
    live = { settings.value.live }, maxLots = { settings.value.guardMaxLots },
    paper = { t -> paperPlace(t.underlying, t.expiry, t.strike, t.right, if (t.buy) "BUY" else "SELL", t.lots, "MARKET",
        settings.value.orderProduct, null, null, null, "Ira") },
    review = { t -> planManual(t.underlying, t.expiry, t.strike, t.right,
        if (t.buy) com.optionslab.engine.Kite.Side.BUY else com.optionslab.engine.Kite.Side.SELL, t.lots, settings.value.orderProduct, null, null, "Ira") },
) }

/**
 * Under an order Ira read: Review looks the contract up (nearest expiry; ATM from the live price). Paper then shows
 * the contract with Confirm / Cancel; Live opens the order review, where only the swipe and the PIN send it.
 */
@Composable
private fun OrderActions(o: com.optionslab.ira.OrderRequest, orders: IraOrderPaths) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<IraOrders.Ticket?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var done by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val t = ticket
        when {
            done != null -> Note(done!!)
            t != null -> LedgerCard(title = "Paper order") {
                Text(t.title, style = Type.figure.copy(color = if (t.buy) pal.verdigris else pal.oxblood))
                Note("Market order on the paper account, ${t.lots * t.lotSize} quantity (lot ${t.lotSize}). Nothing is placed until you press Confirm.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrassButton("Confirm (paper)") { orders.paper(t); ticket = null; done = "Sent to the paper account: ${t.title}. See Trade → Orders." }
                    BrassButton("Cancel", tone = pal.inkSoft) { ticket = null; done = "Cancelled; nothing was sent." }
                }
            }
            else -> {
                problem?.let { Note(it) }
                val live = orders.live()
                BrassButton(if (busy) "Looking up the contract…" else if (live) "Review (Live, Zerodha)" else "Review (paper)", enabled = !busy) {
                    busy = true; problem = null
                    scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            IraOrders.prepare(o, live, orders.maxLots(), o.market?.let { m -> IraHub.state.value.snaps[m] }?.price)
                        }
                        busy = false
                        r.onSuccess { tk ->
                            if (live) { orders.review(tk); done = "Opened in the order review: ${tk.title}. Only the swipe and your PIN send it." }
                            else ticket = tk
                        }.onFailure { problem = it.message ?: "Could not prepare the order." }
                    }
                }
            }
        }
    }
}

/**
 * Jarvis: the switch for listening to "Jarvis" (the microphone permission is asked the first time). The phone's
 * on-device recognizer only; a phone without one is told so, and nothing is sent anywhere instead.
 */
@Composable
internal fun VoiceSwitch() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vs by JarvisVoice.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var on by remember { mutableStateOf(JarvisVoice.wanted) }
    var note by remember { mutableStateOf<String?>(null) }
    fun begin() { JarvisVoice.wanted = true; on = true; note = null; JarvisVoice.start(ctx) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else note = "Without the microphone permission Jarvis cannot hear its name. Typing works as before."
    }
    // The service stopped on its own ("Jarvis, stop listening", or the notification's Stop): the switch follows.
    LaunchedEffect(vs.mode) { if (vs.mode == JarvisVoice.Mode.OFF && !JarvisVoice.wanted) on = false }
    // "Don't listen": the globe's button and this switch are one setting; while it is on, listening's own switch waits.
    val deafNow by JarvisVoice.deafState.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    LedgerCard(title = "Voice") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Don't listen (microphone off)", style = Type.label.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = deafNow, onCheckedChange = { v ->
                note = null
                if (v) JarvisVoice.dontListen(ctx) else JarvisVoice.listenAgain(ctx)
            }, modifier = Modifier.semantics { contentDescription = if (deafNow) "Don't listen is on: Jarvis's microphone is off" else "Don't listen is off" })
        }
        Note(if (deafNow) "Jarvis hears nothing: no \"Jarvis\", no follow-ups, no Talk button, and Android's microphone dot stays off. He still speaks and you can still type. " +
            "Only this switch or \"Listen again\" on the globe turns listening back on - never your voice, a chat message or a backup."
            else "Switch the microphone off altogether (also: type \"don't listen\" or \"mat suno\"). Listening comes back only when you tap.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Listen for \"Jarvis\"", style = Type.label.copy(color = if (deafNow) p.inkSoft else p.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = on && !deafNow, enabled = !deafNow, onCheckedChange = { want ->
                when {
                    !want -> { JarvisVoice.wanted = false; on = false; JarvisVoice.stop(ctx) }
                    !JarvisVoice.available(ctx) -> note = "This phone has no on-device speech recognizer (it needs Android 12 or later), so Jarvis " +
                        "will not listen: your voice is never sent off the phone."
                    JarvisVoice.permitted(ctx) -> begin()
                    else -> ask.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            })
        }
        VoiceStyle()
        var speakTyped by remember { mutableStateOf(com.optionslab.app.ira.JarvisSpeaker.speakTyped) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Say replies to typed questions aloud", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = speakTyped, onCheckedChange = { v ->
                speakTyped = v; com.optionslab.app.ira.JarvisSpeaker.speakTyped = v; if (!v) com.optionslab.app.ira.JarvisSpeaker.stop() })
        }
        // On by itself with a headset or echo cancelling; Boss's own choice wins, and "Automatic" gives it back.
        var cutChoice by remember { mutableStateOf(JarvisVoice.cutInChoice) }
        val cutNow = remember(cutChoice) { JarvisVoice.cutInNow(ctx) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Let me cut in while Jarvis talks", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = cutNow.on, onCheckedChange = { v -> JarvisVoice.cutInChoice = v; cutChoice = v })
        }
        Note("Say \"Jarvis\" or \"stop\" while it speaks and it stops. " +
            (if (cutChoice == null) "Automatic: on with a headset or where the phone cancels its own voice (now ${cutNow.why.say}). "
            else "Your choice (${if (cutChoice == true) "on" else "off"}). ") +
            "Some phones go silent when they listen while speaking: then Jarvis switches this off by itself.")
        if (cutChoice != null) androidx.compose.material3.TextButton({ JarvisVoice.cutInChoice = null; cutChoice = null }) { Text("Back to automatic") }
        var mute by remember { mutableStateOf(JarvisVoice.muted) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Mute Jarvis (replies on screen only)", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = mute, onCheckedChange = { v -> mute = v; if (v) { JarvisVoice.muteBy(com.optionslab.ira.VoiceMute.By.SETTINGS) } else { JarvisVoice.muted = false } })
        }
        Note("Or say \"Jarvis, mute\" and \"Jarvis, unmute\". A mute said by voice lasts until the end of the day.")
        // Boss, 5 Oct: answers short and precise, spoken and in the chat; detailed is the old way. Words only.
        var shortOn by remember { mutableStateOf(com.optionslab.app.ira.IraTools.shortAnswers) }
        Text("Answers", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = shortOn, onClick = { shortOn = true; com.optionslab.app.ira.IraTools.shortAnswers = true },
                label = { Text("Short") })
            androidx.compose.material3.FilterChip(selected = !shortOn, onClick = { shortOn = false; com.optionslab.app.ira.IraTools.shortAnswers = false },
                label = { Text("Detailed") })
        }
        Note(if (shortOn) "One precise line, spoken and in the chat. Say \"more\" or tap Details for the rest." else "The full answer in the chat; the first few sentences spoken.")
        // What Jarvis says aloud by himself (a display choice only: answers and safety warnings are always spoken).
        var speaksSel by remember { mutableStateOf(com.optionslab.app.ira.IraTools.speakChoice) }
        Text("Jarvis speaks", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.padding(top = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (choiceItem in com.optionslab.ira.SpeakChoice.Choice.entries) {
                androidx.compose.material3.FilterChip(selected = speaksSel == choiceItem,
                    onClick = { speaksSel = choiceItem; com.optionslab.app.ira.IraTools.speakChoice = choiceItem },
                    label = { Text(choiceItem.label) })
            }
        }
        Note("Answers to you and safety warnings are always spoken (unless muted or in quiet hours).")
        // Boss, 4 Oct: the phone's on-device recognizer did not hear "Jarvis"; the keyboard's voice typing does, fast.
        var google by remember { mutableStateOf(JarvisVoice.googleSpeech) }
        val vctx = androidx.compose.ui.platform.LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Use Google's speech service", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = google, onCheckedChange = { v ->
                google = v; JarvisVoice.googleSpeech = v
                // Listening starts again from here (the app on screen) with the new ears.
                if (JarvisVoice.wanted) { JarvisVoice.stop(vctx); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (JarvisVoice.wanted) JarvisVoice.start(vctx) }, 700) }
            })
        }
        // Boss, 4 Oct: "ignore background noise, and hear only my voice".
        var onlyMe by remember { mutableStateOf(JarvisVoice.onlyBoss) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Answer only my voice", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = onlyMe, onCheckedChange = { v -> onlyMe = v; JarvisVoice.onlyBoss = v })
        }
        Note(if (com.optionslab.app.ira.VoiceGuard.enrolled) "Words in other voices (TV, people nearby) are ignored. Your microphone is shared with noise reduction for this check."
            else "Teach me your voice first (above); until then this does nothing.")
        // Boss, 4 Oct: one report to send - everything the app knows about Jarvis's ears (no words, keys or secrets).
        val diagScope = rememberCoroutineScope()
        androidx.compose.material3.TextButton({
            diagScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.optionslab.app.data.Diag.report() }
                vctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("IraAlgo diagnostics", text))
                com.optionslab.app.work.Alerts.success("Diagnostics copied (Jarvis's ears included): paste them in the chat. Keys, tokens, passwords and your words are never included.")
            }
        }, Modifier.fillMaxWidth()) { Text("Copy diagnostics (for help)") }
        Note("Off: Jarvis hears you on this phone only. On: he listens through the phone's speech service (the one your keyboard's voice typing uses) - faster and better at hearing \"Jarvis\", but your speech may be sent to Google to be understood.")
        var brief by remember { mutableStateOf(com.optionslab.app.ira.IraTools.brief) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Short spoken answers", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = brief, onCheckedChange = { v -> brief = v; com.optionslab.app.ira.IraTools.brief = v })
        }
        Note("Only the key line is said; say \"Jarvis, tell me more\" for the rest. The full answer is always on screen.")
        var strict by remember { mutableStateOf(com.optionslab.app.ira.IraTools.wakeStrict) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Harder to wake", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = strict, onCheckedChange = { v -> strict = v; com.optionslab.app.ira.IraTools.wakeStrict = v })
        }
        Note("Wakes only when \"Jarvis\" starts what you say: fewer false starts from the TV or other people.")
        var quiet by remember { mutableStateOf(JarvisVoice.quietHours) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Quiet hours 22:00 to 07:00", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = quiet, onCheckedChange = { v -> quiet = v; JarvisVoice.quietHours = v })
        }
        Note("Nothing is said unasked at night (pop-ups instead); Jarvis still answers when you ask.")
        // Battery, round 1: Boss's own switch, off by default (off = listening exactly as before).
        var saver by remember { mutableStateOf(JarvisVoice.listenSaver) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Battery saver for listening", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = saver, onCheckedChange = { v -> saver = v; JarvisVoice.listenSaver = v })
        }
        Note(com.optionslab.ira.ListenSaver.WHAT + " Alerts and stops never wait on listening.")
        var hin by remember { mutableStateOf(JarvisVoice.hindi) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Spoken replies in Hindi", style = Type.label.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = hin, onCheckedChange = { v -> hin = v; JarvisVoice.hindi = v })
        }
        Note("The AI model translates each answer and every figure is checked (English is used when it cannot). Needs the model and the phone's offline Hindi voice.")
        JarvisVoice.heardText?.let { Note("Last heard: \"$it\"") }
        VoiceTeach()
        Note(note ?: vs.problem ?: when (vs.mode) {
            JarvisVoice.Mode.OFF -> if (on) "Starting..." else "Off. Switch on and say \"Jarvis, how is Nifty?\" - or \"Jarvis\", then your question."
            JarvisVoice.Mode.AWAKE -> "Yes? Ask your question."
            JarvisVoice.Mode.THINKING -> "Working it out..."
            JarvisVoice.Mode.SPEAKING -> "Answering."
            JarvisVoice.Mode.LISTENING -> "Listening for \"Jarvis\" on this phone only. Say \"Jarvis, stop listening\" or use the notification to stop. " +
                "Orders you ask for by voice are only prepared here: you confirm them on screen."
        })
    }
}

/**
 * Jarvis: the on-device model. When the Ira screen opens and the model is not on the phone, the owner is asked
 * (Download / Later / Don't ask again); nothing is downloaded without that tap. Then: progress with Cancel, or the
 * switch to use it, and Delete.
 */
/** "Download Jarvis's AI model?" - asked when Jarvis is opened (Download / Later / Don't ask again) until answered. */
@Composable
internal fun ModelAsk() {
    var asking by remember { mutableStateOf(com.optionslab.app.ira.IraModel.shouldAsk()) }
    ModelAskDialog(asking) { asking = false }
}

@Composable
private fun ModelAskDialog(asking: Boolean, done: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val M = com.optionslab.app.ira.IraModel
    val mb = { b: Long -> "%,d MB".format(Locale.ENGLISH, b / 1_000_000) }
    if (asking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { M.later(); done() },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Download Jarvis's AI model?") },
        text = { Text("${M.NAME} (${mb(M.SIZE)}) from Hugging Face, over Wi-Fi or mobile data (it uses ${mb(M.SIZE)} of data). It lets Jarvis write its answers in natural " +
            "language on this phone: nothing you ask leaves the phone, and every number it writes is checked against the market data. " +
            "The file is checked against its fingerprint before use, and you can delete it any time.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { done(); M.download(ctx) }) { Text("Download") } },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = { M.never(); done() }) { Text("Don't ask again") }
                androidx.compose.material3.TextButton(onClick = { M.later(); done() }) { Text("Later") }
            }
        },
    )
}

@Composable
internal fun ModelCard() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val ms by com.optionslab.app.ira.IraModel.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val M = com.optionslab.app.ira.IraModel
    var use by remember { mutableStateOf(M.enabled) }
    var confirmDelete by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<String?>(null) }
    val mb = { b: Long -> "%,d MB".format(Locale.ENGLISH, b / 1_000_000) }
    ModelAskDialog(asking) { asking = false }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Delete the model?") },
        text = { Text("Frees ${mb(M.SIZE)}. Ira keeps answering in its own words; you can download the model again later.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; scope.launch { M.delete(ctx) } }) { Text("Delete") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
    LedgerCard(title = "AI model") {
        // Fastest (0.5B), fast (1.5B, the default) or quality (3B): the owner's choice, never while a file is coming or being checked.
        if (ms.status != ModelStatus.DOWNLOADING && ms.status != ModelStatus.VERIFYING) {
            var chosen by remember { mutableStateOf(M.choice) }
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                for (s in M.SPECS) {
                    androidx.compose.material3.FilterChip(selected = chosen == s,
                        onClick = { if (chosen != s) { chosen = s; scope.launch { M.choose(ctx, s) } } },
                        label = { Text("${s.name} · ${s.about} · ${mb(s.size)}") })
                }
            }
        }
        // A model on the phone that is no longer the chosen one (the 3B after the switch to the fast one): deletable.
        var refresh by remember { mutableStateOf(0) }
        val others = remember(ms.status, refresh, M.choice) { M.others(ctx) }
        for ((o, bytes) in others) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text("${o.name} is also on the phone (${mb(bytes)}), not in use.", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp),
                    modifier = Modifier.weight(1f))
                Text("Delete it", style = Type.label.copy(color = p.ink, fontSize = 13.sp),
                    modifier = Modifier.clickable { scope.launch { M.deleteOther(ctx, o); refresh++ } }.padding(start = 8.dp))
            }
        }
        when (ms.status) {
            ModelStatus.UNSUPPORTED -> Note(M.unsupportedWhy(ctx) + " Ira answers in its own words.")
            ModelStatus.ABSENT, ModelStatus.FAILED -> {
                Note(ms.message ?: ("Not on the phone. ${M.NAME}, ${mb(M.SIZE)}, Wi-Fi or mobile data." +
                    if (ms.done > 0) " ${mb(ms.done)} already downloaded." else ""))
                BrassButton(if (ms.done > 0) "Resume the download" else "Download the model", Modifier.fillMaxWidth().padding(top = 6.dp)) { asking = true }
            }
            ModelStatus.DOWNLOADING -> {
                Note("Downloading ${M.NAME}: ${mb(ms.done)} of ${mb(M.SIZE)}.")
                androidx.compose.material3.LinearProgressIndicator(progress = { (ms.done.toFloat() / M.SIZE).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                BrassButton("Cancel", tone = p.inkSoft) { M.cancel(ctx) }
            }
            ModelStatus.VERIFYING -> Note("Checking the file against its fingerprint...")
            ModelStatus.READY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Write answers with ${M.NAME}", style = Type.label.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = use, onCheckedChange = { use = it; M.enabled = it })
                }
                Note((ms.message ?: if (ms.writing) "Writing..." else if (ms.loaded) "Loaded." else "Ready; it loads when first needed.") +
                    " Runs on this phone only. An answer whose numbers do not match the facts is never shown.")
                // "Is it set up right?": a real run on the phone - load, a short answer, the time it took.
                BrassButton(if (testing) "Testing..." else "Test the model", Modifier.padding(top = 6.dp), busy = testing) {
                    if (!testing) { testing = true; tested = null; scope.launch { tested = M.selfTest(); testing = false } }
                }
                tested?.let { Note(it) }
                Text("Delete the model", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp),
                    modifier = Modifier.clickable { confirmDelete = true }.padding(top = 6.dp))
            }
        }
    }
}

/**
 * How Jarvis sounds: Young girl (the phone's voice pitched up), Woman or Deep; which of the phone's offline voices; and
 * a sample to hear. Uses its own text-to-speech while this card is on screen, offline voices only.
 */
@Composable
private fun VoiceStyle() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var style by remember { mutableStateOf(JarvisVoice.style) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf(JarvisVoice.voiceName) }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    val tts = remember { arrayOfNulls<android.speech.tts.TextToSpeech>(1) }
    DisposableEffect(Unit) {
        if (Build.FINGERPRINT != "robolectric") tts[0] = android.speech.tts.TextToSpeech(ctx.applicationContext) { status ->
            val t = tts[0]
            ready = status == android.speech.tts.TextToSpeech.SUCCESS && t != null && JarvisVoice.offlineVoices(t).isNotEmpty()
            if (t != null && ready == true) voices = JarvisVoice.offlineVoices(t).map { it.name }
        }
        onDispose { runCatching { tts[0]?.stop(); tts[0]?.shutdown() }; tts[0] = null }
    }
    fun sample() {
        val t = tts[0] ?: return
        if (JarvisVoice.applyStyle(t)) t.speak("Hello Boss, I am Jarvis. Nifty is at twenty four thousand six hundred, up zero point two percent today.",
            android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sample")
    }
    Text("Voice style", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        JarvisVoice.Style.entries.forEach { st ->
            val on = st == style
            Text(st.label, style = Type.label.copy(color = if (on) p.onPrimary else p.ink, fontSize = 13.sp),
                modifier = Modifier.background(if (on) p.brass else p.card, RoundedCornerShape(14.dp))
                    .clickable { style = st; JarvisVoice.style = st; JarvisVoice.voiceName = null; chosen = null; sample() }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    if (voices.size > 1) {
        val idx = voices.indexOf(chosen).coerceAtLeast(0)
        Text("Phone voice ${idx + 1} of ${voices.size} (tap for the next)", style = Type.label.copy(color = p.ink, fontSize = 13.sp),
            modifier = Modifier.clickable { chosen = voices[(idx + 1) % voices.size]; JarvisVoice.voiceName = chosen; sample() }.padding(vertical = 4.dp))
    }
    when (ready) {
        true -> BrassButton("Hear a sample", tone = p.inkSoft) { sample() }
        false -> Note("No offline English voice on this phone: add one in Settings, Accessibility, Text-to-speech (install voice data). Jarvis never uses an online voice.")
        null -> Unit
    }
}

/**
 * Yes / No under a request of Jarvis's - in the chat under its own message, in the Requests panel, on the pop-up's
 * card. Tied to [id] alone: a tap answers that request only, never another waiting one, and only while it waits
 * ([com.optionslab.ira.Requests.tapTarget]). Yes is [IraHub.confirm] with all its gates (the fingerprint for real money
 * and the emergency exit, the live and proven-record checks inside it); No is [IraHub.cancelAction]. Answered, lapsed or
 * gone: no buttons. Yes runs in the hub's own scope ([IraHub.confirmAsync]), never this card's: confirming takes the
 * request off the list at once, the card leaves the screen, and a scope of its own would cancel the confirm mid-way.
 */
@Composable
internal fun ActionConfirm(id: Long, yes: String = "Yes", no: String = "No") {
    val waiting by iraSlice(id) { id in it.pending }
    if (!waiting) return
    fun mine(): Long? = com.optionslab.ira.Requests.tapTarget(id, IraHub.state.value.pending)
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.fragment.app.FragmentActivity
        if (IraHub.needsFingerprint(id) && activity != null) {
            // A live Jarvis trade (or the emergency exit): the owner's fingerprint approves it.
            BrassButton("$yes · fingerprint", tone = LocalPalette.current.oxblood) {
                com.optionslab.app.security.BiometricGate.verify(activity, "Approve the request", "Jarvis does it only after your fingerprint") { ok ->
                    if (ok) mine()?.let { mineId -> IraHub.confirmAsync(mineId, fingerprint = true) }
                }
            }
        } else BrassButton(yes, tone = LocalPalette.current.oxblood) { mine()?.let { mineId -> IraHub.confirmAsync(mineId) } }
        BrassButton(no, tone = LocalPalette.current.inkSoft) { mine()?.let { mineId -> IraHub.cancelAction(mineId, by = com.optionslab.ira.Requests.By.TAP) } }
    }
}

/**
 * Yes / No under Jarvis's newest message when it ends offering a question ("BankNifty's levels next, Boss?", the morning
 * "say yes for it"): Yes only asks that question, No ends the offer - nothing acts. Gone once superseded, ended or out of
 * time, and never on a locked phone.
 */
@Composable
internal fun OfferButtons(msgId: Long) {
    val newestId by iraSlice { s -> s.messages.lastOrNull()?.id }
    var tick by remember { mutableIntStateOf(0) }
    if (newestId != msgId) return
    // The offer's time runs out by itself: looked at again every 15 seconds while it is the newest message.
    LaunchedEffect(msgId) { while (true) { delay(15_000); tick++ } }
    val open = remember(newestId, tick) { newestId == msgId && runCatching { IraHub.offerOpen(msgId) }.getOrDefault(false) }
    if (!open) return
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrassButton("Yes") { if (IraHub.answerOffer(msgId, true)) tick++ }
        BrassButton("No", tone = LocalPalette.current.inkSoft) { if (IraHub.answerOffer(msgId, false)) tick++ }
    }
}

/** The Requests badge: "Requests 2" (a count only, nothing of what waits); none in IraGoldAlgo, where Jarvis only talks. */
@Composable
internal fun RequestsBadge(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val count by iraSlice { s -> IraHub.requestsOf(s).size }
    val p = LocalPalette.current
    Text(com.optionslab.ira.Requests.badge(count), style = Type.label.copy(color = if (count > 0) Color.White else p.inkSoft, fontSize = 13.sp),
        modifier = modifier.background(if (count > 0) p.oxblood else p.card, RoundedCornerShape(12.dp)).clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/**
 * Ira's own record, facts only: what it has learned, how the patterns it saw went in each reviewed session (the
 * evening review), the patterns that went their way most often so far, and what became of the strategies it offered.
 */
/** What [HowIraIsDoing] shows, picked from [IraHub.State] (speed round 3): the card recomposes only when one of these changes. */
@androidx.compose.runtime.Immutable
internal data class IraRecord(
    val journal: List<IraHub.DayScore>, val learned: Int, val days: Int, val nightlyAt: java.time.Instant?,
    val best: List<com.optionslab.ira.PatternBook.Entry>, val proposals: List<IraHub.Proposal>,
) {
    companion object {
        fun of(s: IraHub.State) = IraRecord(s.journal, s.learned, s.days, s.nightlyAt, s.best, s.proposals)
    }
}

@Composable
internal fun HowIraIsDoing(st: IraRecord) {
    val p = LocalPalette.current
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val j = st.journal
    val pct = { x: Double -> "${Math.round(x * 100)}%" }
    val dm = { d: java.time.LocalDate -> "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}" }
    LedgerCard(title = "How Ira is doing") {
        val last = j.lastOrNull()
        val recent = j.takeLast(20)
        val rs = recent.sumOf { it.seen }; val rw = recent.sumOf { it.worked }
        Note(when {
            last == null -> "No session reviewed yet: Ira reviews each session after the close (15:35)."
            else -> "Last session (${dm(last.day)}): ${last.worked} of ${last.seen} patterns went their way" +
                (if (last.seen > 0) " (${pct(last.rate)})" else "") + ". " +
                "Last ${recent.size} sessions: $rw of $rs" + (if (rs > 0) " (${pct(rw.toDouble() / rs)})." else ".")
        })
        if (recent.isNotEmpty()) SessionBars(recent)
        Text(if (open) "Less" else "More", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp))
        if (open) {
            Note("Learned ${st.learned} pattern outcomes from ${st.days} days of candles." +
                (st.nightlyAt?.let { " Last review ${it.atZone(java.time.ZoneId.of("Asia/Kolkata")).let { z -> "${dm(z.toLocalDate())}, %02d:%02d".format(z.hour, z.minute) }} IST." } ?: ""))
            if (st.best.isEmpty()) Note("No pattern seen ${com.optionslab.ira.PatternBook.ENOUGH} times yet on one chart.")
            else {
                Note("Went their way most often so far (at least ${com.optionslab.ira.PatternBook.ENOUGH} cases):")
                st.best.forEach { e -> Text("• ${e.kind.label} on ${e.market.label} ${if (e.minutes == 60) "1-hour" else "${e.minutes}-minute"}: " +
                    "${pct(e.stat.rate)} of ${e.stat.seen}", style = Type.label.copy(color = p.ink, fontSize = 13.sp)) }
            }
            val ps = st.proposals
            Note("Strategies offered: ${ps.size}; approved ${ps.count { it.status == IraHub.Proposal.APPROVED }}, " +
                "dismissed ${ps.count { it.status == IraHub.Proposal.DISMISSED }}, waiting ${ps.count { it.status == IraHub.Proposal.NEW }}.")
            Note("This is a record of what happened, not a forecast.")
        }
    }
}

/**
 * "Only Boss's voice can trade": teach Jarvis the owner's voice (five phrases), or forget it. Until it is taught, voice
 * can ask but not trade.
 */
@Composable
private fun VoiceTeach() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val t by com.optionslab.app.ira.VoiceGuard.teach.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var enrolled by remember { mutableStateOf(com.optionslab.app.ira.VoiceGuard.enrolled) }
    LaunchedEffect(t) { enrolled = com.optionslab.app.ira.VoiceGuard.enrolled }
    val g = com.optionslab.app.ira.VoiceGuard
    Text(if (enrolled) "Your voice: taught. Only your voice can trade by voice." else "Your voice: not taught yet. Voice can ask, not trade.",
        style = Type.label.copy(color = p.ink, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp))
    if (!g.supported) Note("This phone needs Android 13 or later to check your voice: approve trades with the buttons.")
    else {
        if (!enrolled || t.step > 0) Note("Say: \"${g.PHRASES[t.step % g.PHRASES.size]}\" (${t.step + 1} of ${com.optionslab.ira.VoicePrint.SAMPLES_NEEDED})")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton(if (t.busy) "Listening..." else if (enrolled && t.step == 0) "Teach again" else "Record phrase") {
                if (!t.busy) scope.launch { g.teachNext(ctx) }
            }
            if (enrolled) BrassButton("Forget my voice", tone = p.inkSoft) { g.forget(); enrolled = false }
        }
    }
    t.message?.let { Note(it) }
}

/**
 * Jarvis's study (Jarvis): what two years of each index's candles say about today's setups, the findings that
 * held in both years, and the news that mattered overnight. History, not a forecast.
 */
@Composable
internal fun JarvisStudyCard() {
    val p = LocalPalette.current
    val k by com.optionslab.app.ira.IraStudy.state.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val lines = androidx.compose.runtime.remember(k) { com.optionslab.app.ira.IraStudy.lines() }
    LedgerCard(title = "Jarvis's study") {
        Note(lines.first())
        val rest = lines.drop(1)
        rest.take(if (open) rest.size else 3).forEach { Text("• $it", style = Type.label.copy(color = p.ink, fontSize = 13.sp)) }
        if (rest.size > 3) Text(if (open) "Less" else "More", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp))
    }
}

/** One bar a session: its height the share of patterns that went their way (half-way line at 50%). */
@Composable
private fun SessionBars(days: List<IraHub.DayScore>) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(vertical = 4.dp)) {
        val n = days.size
        val w = size.width / n
        drawLine(p.inkFaint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        days.forEachIndexed { i, d ->
            if (d.seen == 0) return@forEachIndexed
            val h = (d.rate * size.height).toFloat().coerceAtLeast(2f)
            drawRect(if (d.rate >= 0.5) p.verdigris else p.oxblood, Offset(i * w + w * 0.15f, size.height - h),
                androidx.compose.ui.geometry.Size(w * 0.7f, h))
        }
    }
}

/** Where the prices and the news stand: when they last came in, and what did not answer. */
private fun liveLine(st: IraHub.State): String {
    val hm = { i: java.time.Instant -> i.atZone(java.time.ZoneId.of("Asia/Kolkata")).let { "%02d:%02d".format(it.hour, it.minute) } }
    val live = st.liveAt?.let { "Live prices at ${hm(it)} IST" + (if (st.liveMissing.isNotEmpty()) " (no answer from ${st.liveMissing.joinToString { m -> m.label }})" else "") + "." }
        ?: "Live prices not reached yet."
    val news = st.newsAt?.let { " ${st.news.size} headlines at ${hm(it)}" + (if (st.newsMissing.isNotEmpty()) " (${st.newsMissing.joinToString()} not answering)" else "") + "." } ?: ""
    return live + news
}

/** Volatility for the orb from India VIX: 10 calm .. 28 wild. */
private fun orbVol(snaps: Map<IraMarket, Snapshot>): Float =
    snaps[IraMarket.VIX]?.price?.let { ((it - 10) / 18).coerceIn(0.0, 1.0).toFloat() } ?: 0.15f

/** Trend for the orb: the 1-hour tracker, softened by the day's change. */
private fun orbTrend(s: Snapshot?): Float {
    if (s == null) return 0f
    val tr = s.trend(60)?.let { if (it.up) 0.6 else -0.6 } ?: 0.0
    val day = ((s.changePct ?: 0.0) / 2.5).coerceIn(-0.4, 0.4)
    return (tr + day).coerceIn(-1.0, 1.0).toFloat()
}

/** The quick commands (a long press on the globe): one tap asks Jarvis; stopping or starting opens the chat for Confirm. */
@Composable
private fun QuickCommands(onDismiss: () -> Unit, pick: (String, Boolean) -> Unit) {
    val items = listOf(
        "Exit everything (emergency)" to ("exit everything" to true),
        "Status" to ("app status" to false), "My P&L" to ("my p&l today" to false), "Positions" to ("my positions" to false),
        "Should I trade now?" to ("should i trade now" to false), "Stop all strategies" to ("stop all strategies" to true),
        "Start all strategies" to ("start all strategies" to true),
        (if (com.optionslab.app.ira.JarvisVoice.muted) "Unmute" else "Mute") to ((if (com.optionslab.app.ira.JarvisVoice.muted) "unmute" else "mute") to false),
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Color(0xFF0B1220), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(vertical = 8.dp).width(260.dp)) {
            Text("QUICK COMMANDS", style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 11.sp, letterSpacing = 2.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            items.forEach { (label, a) ->
                Text(label, style = Type.label.copy(color = Color(0xFFE6ECFF), fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().clickable { pick(a.first, a.second) }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

private fun headline(s: Snapshot): String =
    "${s.market.label} ${s.market.price(s.price)}" + (s.changePct?.let { " %+.2f%%".format(Locale.ENGLISH, it) } ?: "")

/** The orb on the graphics chip; a still drawing where there is no GL (the JVM screen tests). */
@Composable
private fun Orb(vol: Float, trend: Float, mode: Int, onTap: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null) {
    if (Build.FINGERPRINT == "robolectric") {
        Canvas(Modifier.fillMaxSize().let { m -> if (onTap != null || onLongPress != null) m.pointerInput(onTap, onLongPress) { detectTapGestures(onTap = { onTap?.invoke() }, onLongPress = { onLongPress?.invoke() }) } else m }) {
            val r = size.minDimension * 0.32f
            for (i in 0 until 400) {
                val a = i * 2.39996; val y = 1 - (i / 399.0) * 2; val rr = kotlin.math.sqrt(1 - y * y)
                drawCircle(Color(0xFF4AA8FF), 1.5f, Offset(center.x + (kotlin.math.cos(a) * rr * r).toFloat(), center.y + (y * r).toFloat()))
            }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<OrbView?>(null) }
    AndroidView(factory = { ctx -> OrbView(ctx).also { view = it } }, modifier = Modifier.fillMaxSize()) { v ->
        v.vol = vol; v.trend = trend; v.mode = mode; v.onTap = onTap; v.onLongPress = onLongPress
        v.caution = IraHub.caution()
    }
    // The trade check's last word tints the globe amber (careful) or deeper amber (don't trade): read every few seconds.
    com.optionslab.app.ui.PollWhileStarted(view) { while (true) { view?.caution = IraHub.caution(); kotlinx.coroutines.delay(3_000) } }
    DisposableEffect(owner, view) {
        val v = view
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> v?.onResume(); Lifecycle.Event.ON_PAUSE -> v?.onPause(); else -> Unit }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); v?.onPause() }
    }
}

/**
 * The mic: tap and talk to Jarvis, no "Jarvis" needed - it says "Yes, Boss?" and answers aloud. With listening off it
 * listens for that one question only. Asks for the microphone the first time.
 */
@Composable
private fun MicButton(label: String) {
    if (JarvisVoice.deaf) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) JarvisVoice.talk(ctx) else note = "Jarvis needs the microphone to hear you."
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BrassButton(label) {
            note = null
            when {
                !JarvisVoice.available(ctx) -> note = "This phone has no on-device speech recognizer (Android 12 or later needed)."
                !JarvisVoice.permitted(ctx) -> ask.launch(android.Manifest.permission.RECORD_AUDIO)
                !JarvisVoice.talk(ctx) -> note = "Jarvis could not start listening; try again."
            }
        }
        note?.let { Text(it, style = Type.label.copy(color = Color(0xFFB8C0E8), fontSize = 11.sp)) }
    }
}

/**
 * "Don't listen" (Boss, 6 Oct: "like the other button on the globe, add a button 'Don't listen', so that he won't listen
 * to all the conversations"): one tap switches the microphone off altogether (wake word, follow-ups, Talk, cut-in, voice
 * teaching) and the service stops; one tap here - or the Settings switch - is the only way back, to listening as set before.
 */
@Composable
internal fun DontListenButton(deaf: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val desc = if (deaf) "Not listening: Jarvis's microphone is off. Double-tap to let Jarvis listen again."
        else "Don't listen: switch Jarvis's microphone off. He still speaks and you can still type."
    BrassButton(if (deaf) "Not listening · Listen again" else "Don't listen",
        modifier = Modifier.semantics {
            contentDescription = desc
            stateDescription = if (deaf) "Microphone off" else "Microphone on"
        },
        leading = if (deaf) ({ c -> MicGlyph(crossed = true, color = c) }) else null) {
        if (deaf) JarvisVoice.listenAgain(ctx) else JarvisVoice.dontListen(ctx)
    }
}

/** A small microphone, [crossed] out when Jarvis is not listening (decorative: its button or label says it in words). */
@Composable
internal fun MicGlyph(crossed: Boolean, color: Color) {
    Canvas(Modifier.width(16.dp).height(18.dp).clearAndSetSemantics { }) {
        val w = size.width; val h = size.height; val stroke = w * 0.11f
        // The capsule, its cradle and the stand.
        drawRoundRect(color, topLeft = Offset(w * 0.32f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.18f, w * 0.18f))
        drawArc(color, 0f, 180f, useCenter = false, topLeft = Offset(w * 0.16f, h * 0.22f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.52f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), stroke)
        drawLine(color, Offset(w * 0.3f, h * 0.95f), Offset(w * 0.7f, h * 0.95f), stroke)
        if (crossed) drawLine(color, Offset(w * 0.05f, h * 0.05f), Offset(w * 0.95f, h * 0.95f), stroke * 1.3f)
    }
}

/** Settings → Jarvis (Jarvis): the voice (listening, style, your voice print, spoken replies) and the AI model. */
@Composable
fun JarvisSettingsPage() {
    com.optionslab.app.ui.Page {
        item { PageTitle("Jarvis settings", "Voice and the on-device AI model. Nothing you say or type leaves the phone.") }
        item { com.optionslab.app.ui.SettingSpot("jarvis.voice") { VoiceSwitch() } }
        item { com.optionslab.app.ui.SettingSpot(com.optionslab.app.ira.IraNotes.SOLO_KEY) { SoloCard() } }
        item { com.optionslab.app.ui.SettingSpot("jarvis.automations") { AutomationsCard() } }
        item { com.optionslab.app.ui.SettingSpot("jarvis.model") { ModelCard() } }
    }
}

/**
 * Solo (the owner's wish, 2026-10-03; Solo (midday) since 06 Oct): Jarvis trades by himself on paper; the switch, the
 * "not proven" label, the forward test ("x of 60") and the research line.
 */
@Composable
private fun SoloCard() {
    val p = LocalPalette.current
    LedgerCard(title = "Solo: Jarvis trades by himself (paper)") {
        // Solo's own state, not a copy: it shows off once Solo has switched itself off (the forward test's bar).
        val on by com.optionslab.app.ira.IraSolo.onState.collectAsState()
        LaunchedEffect(Unit) { com.optionslab.app.ira.IraSolo.refreshOn() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (on) "Solo (midday) is on" else "Solo (midday) is off", style = Type.label.copy(color = p.ink, fontSize = 14.sp))
                Text("Paper account only - never real money, never Zerodha. At 12:00 it buys the index (NIFTY, BANKNIFTY or FINNIFTY) that has moved half its daily ATR or more from the open and closed in the outer quarter of the morning's range - the strongest one, 1 lot, 4 strikes in the money, one trade a day, never on that index's expiry day. Out on a 1-minute close 0.3 ATR against it, at breakeven once 75% of the way to 2R, or at 14:30.",
                    style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { v -> com.optionslab.app.ira.IraSolo.on = v })
        }
        // The forward test set in advance, and the "not proven" label with the research line.
        val lines = remember(on) { com.optionslab.app.ira.IraSolo.card() }
        lines.forEach { Note(it) }
        val rec = remember(on) { com.optionslab.app.ira.IraSolo.record() }
        Note(rec)
        com.optionslab.app.ira.IraSolo.paused?.let { Note(it) }
    }
}

/**
 * Jarvis's health (the owner's wish, 2026-10-03): everything it does by itself, each with its own switch and when it
 * last acted - one place to see and stop any of it.
 */
/** A behaviour with its own switch beneath its group (e.g. Market alerts → skipped Liquidity breaks): on only while the group is. */
@Composable
private fun AutomationSubRow(a: com.optionslab.app.ira.Automations.Auto, groupOn: Boolean) {
    val p = LocalPalette.current
    var own by remember { mutableStateOf(com.optionslab.app.ira.Automations.ownSwitch(a)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(a.label, style = Type.label.copy(color = if (groupOn) p.ink else p.inkSoft, fontSize = 13.sp))
            Text(a.what + (if (groupOn) "" else " (Market alerts is off: this stays quiet.)"), style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
        }
        androidx.compose.material3.Switch(checked = own, onCheckedChange = { v -> own = v; com.optionslab.app.ira.Automations.set(a, v) })
    }
}

@Composable
private fun AutomationsCard() {
    val p = LocalPalette.current
    LedgerCard(title = "What Jarvis does by itself") {
        // Boss, 4 Oct: one switch for the AI's trades going to Zerodha. Off: on paper even in Live. On (fingerprint):
        // in Live, once their record is proven, each trade still asks him first and needs the fingerprint.
        val act = androidx.compose.ui.platform.LocalContext.current as? androidx.fragment.app.FragmentActivity
        var aiLive by remember { mutableStateOf(!com.optionslab.app.ira.IraNewsTrades.paperFirst) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("AI trades go live", style = Type.label.copy(color = if (aiLive) p.oxblood else p.ink, fontSize = 14.sp))
                Text(if (aiLive) "On: in Live, Jarvis's trades go to Zerodha once their record is proven - each one asked first, approved with your fingerprint. Solo stays on paper always."
                    else "Off: Jarvis's trades go on PAPER, even when the app is in Live (Solo always does).",
                    style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            androidx.compose.material3.Switch(checked = aiLive, onCheckedChange = { v ->
                if (!v) { aiLive = false; com.optionslab.app.ira.IraNewsTrades.paperFirst = true }
                else if (act == null || !com.optionslab.app.security.BiometricGate.fingerprintOn(act))
                    com.optionslab.app.work.Alerts.error("AI trades go live is switched on with your fingerprint: set one up on the phone first.")
                else com.optionslab.app.security.BiometricGate.verify(act, "AI trades go live", "Jarvis's trades may go to Zerodha, each after your approval (Solo stays on paper)") { ok ->
                    if (ok) { aiLive = true; com.optionslab.app.ira.IraNewsTrades.paperFirst = false } }
            })
        }
        Note("Each runs on its own while IraAlgo watches the market. Nothing here opens a trade without asking you, except on paper (Solo, and Jarvis's own ideas when that switch is on); the guard and the trailing stop only add or raise stops.")
        com.optionslab.app.ira.Automations.Group.entries.forEach { g ->
            var on by remember { mutableStateOf(com.optionslab.app.ira.Automations.on(g)) }
            val last = remember(on) { com.optionslab.app.ira.Automations.last(g) }
            // Its row a key of its own, so Today's notes' "Turn these off" brings this very switch into view.
            com.optionslab.app.ui.SettingSpot(com.optionslab.app.ira.IraNotes.groupKey(g)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, style = Type.label.copy(color = p.ink, fontSize = 14.sp))
                        Text(g.what, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
                        Text(last?.let { (t, w) -> "Last: ${t.toLocalDate()} ${"%02d:%02d".format(t.hour, t.minute)} - $w" } ?: "Has not acted yet.",
                            style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp))
                    }
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
                        // The guard places real stop orders: switched on only with Boss's fingerprint (off needs nothing).
                        if (v && g.fingerprint) {
                            if (act == null || !com.optionslab.app.security.BiometricGate.fingerprintOn(act))
                                com.optionslab.app.work.Alerts.error("${g.label} is switched on with your fingerprint: set one up on the phone first.")
                            else com.optionslab.app.security.BiometricGate.verify(act, g.label, "Jarvis may place stop orders on your positions") { ok ->
                                if (ok) { on = true; com.optionslab.app.ira.Automations.set(g, true) } }
                        } else { on = v; com.optionslab.app.ira.Automations.set(g, v) }
                    })
                }
            }
            g.subs.forEach { a -> com.optionslab.app.ui.SettingSpot(com.optionslab.app.ira.IraNotes.subKey(a)) { AutomationSubRow(a, groupOn = on) } }
        }
        Note("Always on, with no switch: live prices stopped, the expiry-day heads-up, the cool-off after two losses, the backup reminder and the self-healing voice.")
    }
}
