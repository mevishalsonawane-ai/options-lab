package com.optionslab.app.ira

import com.optionslab.app.data.FlowGate
import com.optionslab.app.data.Market
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.ira.BotReplay
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/**
 * A closed bot trade's replay (S21; the commentary is ira-core's [BotReplay]): the trade manager's record of the trade
 * ([TradeManagerHost.records]: every specialist's vote and the original rules followed on as the counterfactual), the
 * order flow's log at the entry ([FlowGate.signalsForLearning]) and the option's minute candles over the hold - kept on the
 * phone after the close ([keepToday], noBackupFilesDir/self/replay, the newest [KEEP] trades) so a replay works on later
 * days too; today's are read from the paper feed when not kept yet. Reads only: it changes, places and closes nothing.
 */
object BotReplayStore {
    private const val SUB = "replay"
    const val KEEP = 80

    private val _open = MutableStateFlow<String?>(null)
    /** The trade whose replay sheet is open ([SelfSheets] shows it), by its trade id. */
    val open: StateFlow<String?> = _open

    /** TEST ONLY: the bars instead of the files and the feed. */
    @Volatile internal var testBars: ((TradeManager.Record) -> List<TradeManager.Bar>)? = null

    private fun dir(): File? = SelfJobs.dir()?.let { File(it, SUB) }
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    private fun encode(bars: List<TradeManager.Bar>) = bars.joinToString("\n") { "${it.startMs},${it.open},${it.high},${it.low},${it.close}" }
    private fun decode(text: String): List<TradeManager.Bar> = text.lineSequence().mapNotNull { l ->
        val p = l.split(','); if (p.size < 5) null else runCatching { TradeManager.Bar(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble()) }.getOrNull()
    }.toList()

    /** The option's minutes of today from the paper feed (null: the contract is not known to the paper book, or no read). */
    private suspend fun feedBars(r: TradeManager.Record): List<TradeManager.Bar>? {
        val c = runCatching { com.optionslab.app.data.Paper.contractOf(r.trade.instrument) }.getOrNull() ?: return null
        val bars = runCatching { com.optionslab.app.data.Net.intraday(c.feedKey) }.getOrNull() ?: return null
        return bars.map { TradeManager.Bar(it.epochSecond * 1000, it.open, it.high, it.low, it.close) }.takeIf { it.isNotEmpty() }
    }

    /** Each bot trade closed [today] gets its option's minutes kept (after the close; a worker thread). */
    suspend fun keepToday(today: LocalDate) {
        val d = dir() ?: return
        d.mkdirs()
        for (r in TradeManagerHost.records.value.filter { it.actual?.let { a -> com.optionslab.ira.AgentLessons.dayOf(a.atMs) == today } == true }) {
            val f = File(d, safe(r.trade.tradeId) + ".txt")
            if (f.isFile) continue
            val bars = feedBars(r) ?: continue
            runCatching { f.writeText(encode(bars)) }
        }
        val all = d.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        all.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** [r]'s bars: kept ones, else today's from the feed, else none (the replay then shows its timeline only). */
    private suspend fun bars(r: TradeManager.Record): List<TradeManager.Bar> {
        testBars?.let { return it(r) }
        val f = dir()?.let { File(it, safe(r.trade.tradeId) + ".txt") }
        if (f != null && f.isFile) return runCatching { decode(f.readText()) }.getOrDefault(emptyList())
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        if (r.actual?.let { com.optionslab.ira.AgentLessons.dayOf(it.atMs) } != today) return emptyList()
        return feedBars(r).orEmpty()
    }

    /** The order flow at [r]'s entry, from the flow's own signal log (the signal nearest the entry on its family). */
    private fun flowAt(r: TradeManager.Record): String? = runCatching {
        val t = r.trade
        val s = FlowGate.signalsForLearning().filter { kotlin.math.abs(it.epochSec * 1000 - t.entryMs) <= 3 * 60_000L &&
            (it.key.equals(t.family, ignoreCase = true) || it.key.startsWith(t.family, ignoreCase = true)) }
            .minByOrNull { kotlin.math.abs(it.epochSec * 1000 - t.entryMs) } ?: return@runCatching null
        BotReplay.flowWords(s.agreement.name, s.buyers, s.flags)
    }.getOrNull()

    /** The replay of the trade with id [tradeId] (a worker thread); null when the record is gone. */
    suspend fun load(tradeId: String): BotReplay.Replay? {
        val r = TradeManagerHost.records.value.firstOrNull { it.trade.tradeId == tradeId } ?: return null
        return BotReplay.of(r, bars(r), flowAt(r))
    }

    /** Opens [tradeId]'s replay sheet over the app. */
    fun show(tradeId: String) { _open.value = tradeId }
    fun close() { _open.value = null }

    /** "Replay my last Liquidity trade": the sheet opened on it, and what to say. */
    fun answer(words: String?): String {
        val r = BotReplay.pick(TradeManagerHost.records.value, words)
        if (r == null) return BotReplay.say(null, words)
        show(r.trade.tradeId)
        val cf = r.original?.let { o -> " The original rules would have exited at ${TradeManager.num(o.price)} for ${TradeManager.rs((o.price - r.trade.entry) * r.trade.qty)}." } ?: ""
        return "Opening the replay of ${r.trade.label} (${r.trade.instrument}): exited on ${r.actual?.why?.lowercase(java.util.Locale.ENGLISH) ?: "-"}.$cf"
    }

    /** TEST ONLY. */
    internal fun resetForTest() { _open.value = null; testBars = null }
}
