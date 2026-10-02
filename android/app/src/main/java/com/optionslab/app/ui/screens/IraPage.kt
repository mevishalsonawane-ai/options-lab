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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * JarvisAlgo's Home: Ira first, the usual dashboard (prices, P&L, strategies) behind the second switch. The choice is
 * kept while the app runs.
 */
@Composable
fun IraHome(orders: IraOrderPaths? = null, dashboard: @Composable () -> Unit) {
    val p = LocalPalette.current
    var showIra by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    // JarvisAlgo: listening was left on - start it again now the app is on screen (Android allows it only then).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { if (com.optionslab.app.BuildConfig.JARVIS && JarvisVoice.wanted) JarvisVoice.start(ctx) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).background(p.card, RoundedCornerShape(12.dp)).padding(4.dp)) {
            listOf("Ira" to true, "Dashboard" to false).forEach { (label, ira) ->
                val on = showIra == ira
                Text(label, style = Type.label.copy(color = if (on) p.onPrimary else p.inkSoft, fontSize = 14.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).background(if (on) p.brass else Color.Transparent, RoundedCornerShape(9.dp))
                        .clickable { showIra = ira }.padding(vertical = 8.dp))
            }
        }
        Box(Modifier.weight(1f)) { if (showIra) IraPage(orders) else dashboard() }
    }
}

/** Example questions shown before the first one is asked. */
private val EXAMPLES = listOf("What is BankNifty doing today?", "Nifty levels", "Is gold up today?", "Any news on banks?", "Backtest the breakout on BankNifty 15m", "Any pattern on FinNifty?", "Buy 1 lot Nifty ATM CE", "Should I trade now?", "What did you study last night?", "Nifty PCR and max pain", "What did FIIs do?", "My weekly review", "Analyze my orders", "Any events this week?", "What can you do?")

/**
 * Ira: the orb (the market at a glance) above the conversation. Answers come from IraAlgo's own data only; an order
 * request goes through [orders] (the app's own paths) only after the owner confirms it.
 */
@Composable
fun IraPage(orders: IraOrderPaths? = null) {
    val p = LocalPalette.current
    val st by IraHub.state.collectAsState()
    val voice by JarvisVoice.state.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var typed by remember { mutableIntStateOf(0) }
    // The orb shows the typed exchange first, else what the voice is doing (plain listening for the name is "idle").
    val mode = if (typed != 0) typed else when (voice.mode) {
        JarvisVoice.Mode.AWAKE -> 1; JarvisVoice.Mode.THINKING -> 2; JarvisVoice.Mode.SPEAKING -> 3; else -> 0 }
    var focus by remember { mutableStateOf(IraMarket.NIFTY) }
    // Live prices every minute while Ira is on screen (and news every ten minutes, inside the hub).
    com.optionslab.app.ui.PollWhileStarted { while (true) { IraHub.refresh(); delay(60_000) } }
    val list = rememberLazyListState()
    LaunchedEffect(st.messages.size) { if (st.messages.isNotEmpty()) list.animateScrollToItem(st.messages.size) }

    val ctxSpeak = androidx.compose.ui.platform.LocalContext.current
    fun send(q: String) {
        if (q.isBlank()) return
        IraMarket.mentioned(q).firstOrNull()?.let { focus = it }
        text = ""
        // The answer is ready at once (it is built from facts); the orb still shows a beat of thinking, then answers.
        IraHub.ask(q)
        // JarvisAlgo: the reply to a typed question is said aloud too (the owner's switch, on by default).
        scope.launch { com.optionslab.app.ira.JarvisSpeaker.replyTo(ctxSpeak, q) }
        typed = 2
        scope.launch { delay(600); typed = 3; delay(1_800); typed = 0 }
    }

    // JarvisAlgo: only the globe until the owner opens the chat (the owner's wish, 2026-10-02); voice works either way.
    var chat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!com.optionslab.app.BuildConfig.JARVIS) }
    if (!chat) {
        val writing by com.optionslab.app.ira.IraModel.state.collectAsState()
        // Analysing in the background (reading the market, a backtest, the model writing) shows as thinking too.
        val orbMode = when {
            mode == 0 && text.isNotEmpty() -> 1
            mode == 0 && (st.busy || st.loading || writing.writing) -> 2
            else -> mode
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(Alignment.Center)) {
                Orb(vol = orbVol(st.snaps), trend = orbTrend(st.snaps[focus]), mode = orbMode)
            }
            Text(listOf("Idle", "Listening", "Thinking", "Answering")[orbMode].uppercase(),
                style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 12.sp, letterSpacing = 3.sp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                st.snaps[focus]?.let { snap -> Text(headline(snap), style = Type.label.copy(color = Color(0xFFB8C0E8), fontSize = 12.sp)) }
                val waiting = st.pending.size
                BrassButton(if (waiting > 0) "Open chat · $waiting waiting for you" else "Open chat") { chat = true }
            }
        }
        if (com.optionslab.app.BuildConfig.JARVIS) ModelAsk()
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (com.optionslab.app.BuildConfig.JARVIS) Text("‹  Back to Jarvis", style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth().background(Color.Black).clickable { chat = false }.padding(horizontal = 14.dp, vertical = 8.dp))
        Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black)) {
            val s = st.snaps[focus]
            Orb(vol = orbVol(st.snaps), trend = orbTrend(s), mode = if (mode == 0 && text.isNotEmpty()) 1 else mode)
            Text(listOf("Idle", "Listening", "Thinking", "Answering")[if (mode == 0 && text.isNotEmpty()) 1 else mode].uppercase(),
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
                        st.loading -> "Reading the market data..."
                        st.problem != null -> st.problem!!
                        else -> "${st.days} days of candles read; ${st.learned} pattern outcomes learned.$day " + liveLine(st)
                    })
                }
            }
            item { HowIraIsDoing(st) }
            if (com.optionslab.app.BuildConfig.JARVIS) item { JarvisStudyCard() }
            if (st.messages.isEmpty()) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXAMPLES.forEach { q ->
                            Text(q, style = Type.label.copy(color = p.ink, fontSize = 14.sp),
                                modifier = Modifier.background(p.card, RoundedCornerShape(16.dp)).clickable { send(q) }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            items(st.messages) { m -> Bubble(m, orders) }
            if (st.messages.isNotEmpty()) item {
                Text("Forget this conversation", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp),
                    modifier = Modifier.clickable { IraHub.forgetConversation() }.padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text("Ask Ira about the market") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            BrassButton("Ask", enabled = text.isNotBlank()) { send(text) }
        }
    }
}

@Composable
private fun Bubble(m: IraHub.Msg, orders: IraOrderPaths?) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromIra) Alignment.Start else Alignment.End) {
        Text(if (m.fromIra) "IRA" else "YOU", style = Type.label.copy(color = if (m.fromIra) Color(0xFF4AA8FF) else p.inkSoft, fontSize = 10.sp, letterSpacing = 2.sp))
        Text(if (m.fromIra && com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.Address.boss(m.text) else m.text, style = Type.label.copy(color = p.ink, fontSize = 15.sp),
            modifier = Modifier.background(p.card, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        if (m.fromIra && com.optionslab.app.BuildConfig.JARVIS && !m.writing) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text("▶ Listen", style = Type.label.copy(color = Color(0xFF4AA8FF), fontSize = 13.sp),
                modifier = Modifier.clickable { com.optionslab.app.ira.JarvisSpeaker.speak(ctx, m.text) }.padding(top = 4.dp, bottom = 2.dp))
        }
        m.order?.let { o ->
            if (o.missing.isNotEmpty() || o.refusal != null) Note("Nothing was sent.")
            else if (orders == null) Note("Orders from Ira work on the Home screen; nothing was sent.")
            else OrderActions(o, orders)
        }
        m.proposal?.let { id -> ProposalActions(id) }
        m.action?.let { id -> ActionConfirm(id) }
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
private fun ProposalActions(id: Long) {
    val st by IraHub.state.collectAsState()
    val p = st.proposals.firstOrNull { it.id == id } ?: return
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
                    scope.launch {
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
 * JarvisAlgo: the switch for listening to "Jarvis" (the microphone permission is asked the first time). The phone's
 * on-device recognizer only; a phone without one is told so, and nothing is sent anywhere instead.
 */
@Composable
internal fun VoiceSwitch() {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vs by JarvisVoice.state.collectAsState()
    var on by remember { mutableStateOf(JarvisVoice.wanted) }
    var note by remember { mutableStateOf<String?>(null) }
    fun begin() { JarvisVoice.wanted = true; on = true; note = null; JarvisVoice.start(ctx) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else note = "Without the microphone permission Jarvis cannot hear its name. Typing works as before."
    }
    // The service stopped on its own ("Jarvis, stop listening", or the notification's Stop): the switch follows.
    LaunchedEffect(vs.mode) { if (vs.mode == JarvisVoice.Mode.OFF && !JarvisVoice.wanted) on = false }
    LedgerCard(title = "Voice") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Listen for \"Jarvis\"", style = Type.label.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { want ->
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
 * JarvisAlgo: the on-device model. When the Ira screen opens and the model is not on the phone, the owner is asked
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
        text = { Text("${M.NAME} (${mb(M.SIZE)}) from Hugging Face, over Wi-Fi only. It lets Jarvis write its answers in natural " +
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
    val ms by com.optionslab.app.ira.IraModel.state.collectAsState()
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
        when (ms.status) {
            ModelStatus.UNSUPPORTED -> Note(M.unsupportedWhy(ctx) + " Ira answers in its own words.")
            ModelStatus.ABSENT, ModelStatus.FAILED -> {
                Note(ms.message ?: ("Not on the phone. ${M.NAME}, ${mb(M.SIZE)}, Wi-Fi." +
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

/** Confirm / Cancel under something Jarvis will stop or close when the owner taps (one tap, no PIN: the owner's rule). */
@Composable
private fun ActionConfirm(id: Long) {
    val st by IraHub.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (id !in st.pending) return
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrassButton("Confirm", tone = LocalPalette.current.oxblood) { scope.launch { IraHub.confirm(id) } }
        BrassButton("Cancel", tone = LocalPalette.current.inkSoft) { IraHub.cancelAction(id) }
    }
}

/**
 * Ira's own record, facts only: what it has learned, how the patterns it saw went in each reviewed session (the
 * evening review), the patterns that went their way most often so far, and what became of the strategies it offered.
 */
@Composable
internal fun HowIraIsDoing(st: IraHub.State) {
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
    val t by com.optionslab.app.ira.VoiceGuard.teach.collectAsState()
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
 * Jarvis's study (JarvisAlgo): what two years of each index's candles say about today's setups, the findings that
 * held in both years, and the news that mattered overnight. History, not a forecast.
 */
@Composable
internal fun JarvisStudyCard() {
    val p = LocalPalette.current
    val k by com.optionslab.app.ira.IraStudy.state.collectAsState()
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

private fun headline(s: Snapshot): String =
    "${s.market.label} ${s.market.price(s.price)}" + (s.changePct?.let { " %+.2f%%".format(Locale.ENGLISH, it) } ?: "")

/** The orb on the graphics chip; a still drawing where there is no GL (the JVM screen tests). */
@Composable
private fun Orb(vol: Float, trend: Float, mode: Int) {
    if (Build.FINGERPRINT == "robolectric") {
        Canvas(Modifier.fillMaxSize()) {
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
        v.vol = vol; v.trend = trend; v.mode = mode
    }
    DisposableEffect(owner, view) {
        val v = view
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> v?.onResume(); Lifecycle.Event.ON_PAUSE -> v?.onPause(); else -> Unit }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); v?.onPause() }
    }
}

/** Settings → Jarvis (JarvisAlgo): the voice (listening, style, your voice print, spoken replies) and the AI model. */
@Composable
fun JarvisSettingsPage() {
    com.optionslab.app.ui.Page {
        item { PageTitle("Jarvis settings", "Voice and the on-device AI model. Nothing you say or type leaves the phone.") }
        item { VoiceSwitch() }
        item { ModelCard() }
    }
}
