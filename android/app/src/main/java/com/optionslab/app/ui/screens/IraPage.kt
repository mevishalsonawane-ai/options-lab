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
fun IraHome(dashboard: @Composable () -> Unit) {
    val p = LocalPalette.current
    var showIra by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
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
        Box(Modifier.weight(1f)) { if (showIra) IraPage() else dashboard() }
    }
}

/** Example questions shown before the first one is asked. */
private val EXAMPLES = listOf("What is BankNifty doing today?", "Nifty levels", "Is gold up today?", "Any news on banks?", "Is India VIX high?", "Any pattern on FinNifty?")

/**
 * Ira: the orb (the market at a glance) above the conversation. Answers come from IraAlgo's own data only; an order
 * request is shown for review and never sent from here.
 */
@Composable
fun IraPage() {
    val p = LocalPalette.current
    val st by IraHub.state.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf(IraMarket.NIFTY) }
    // Live prices every minute while Ira is on screen (and news every ten minutes, inside the hub).
    com.optionslab.app.ui.PollWhileStarted { while (true) { IraHub.refresh(); delay(60_000) } }
    val list = rememberLazyListState()
    LaunchedEffect(st.messages.size) { if (st.messages.isNotEmpty()) list.animateScrollToItem(st.messages.size) }

    fun send(q: String) {
        if (q.isBlank()) return
        IraMarket.mentioned(q).firstOrNull()?.let { focus = it }
        text = ""
        // The answer is ready at once (it is built from facts); the orb still shows a beat of thinking, then answers.
        IraHub.ask(q)
        mode = 2
        scope.launch { delay(600); mode = 3; delay(1_800); mode = 0 }
    }

    Column(Modifier.fillMaxSize()) {
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
                        "and says when it does not know. It never gives buy or sell advice, and never sends an order.")
                    val day = st.lastDay?.let { " Latest data: ${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} ${it.year}." } ?: ""
                    Note(when {
                        st.loading -> "Reading the market data..."
                        st.problem != null -> st.problem!!
                        else -> "${st.days} days of candles read; ${st.learned} pattern outcomes learned.$day " + liveLine(st)
                    })
                }
            }
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
            items(st.messages) { m -> Bubble(m) }
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
private fun Bubble(m: IraHub.Msg) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromIra) Alignment.Start else Alignment.End) {
        Text(if (m.fromIra) "IRA" else "YOU", style = Type.label.copy(color = if (m.fromIra) Color(0xFF4AA8FF) else p.inkSoft, fontSize = 10.sp, letterSpacing = 2.sp))
        Text(m.text, style = Type.label.copy(color = p.ink, fontSize = 15.sp),
            modifier = Modifier.background(p.card, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        m.order?.let {
            Note(if (it.missing.isEmpty()) "Order review from Ira comes in a later build; nothing was sent." else "Nothing was sent.")
        }
        if (m.fromIra && m.facts.isNotEmpty()) {
            Text(if (open) "Hide the facts used" else "Facts used (${m.facts.size})", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp),
                modifier = Modifier.clickable { open = !open }.padding(top = 4.dp))
            if (open) m.facts.forEach { f -> Text("• $f", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp)) }
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
