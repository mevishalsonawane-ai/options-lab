package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The owner's Pine scripts: code, whether each is drawn on the chart, its input values,
 * and its auto-trade settings. Kept in one encrypted vault file.
 */
object PineScripts {
    /**
     * Auto-trade on a script's signals. [buy] and [sell] name the signal that means go long
     * and go short: "strategy" follows a strategy's own entries and exits; otherwise the
     * title of a plotshape or alertcondition.
     */
    data class Auto(
        val on: Boolean = false,
        val symbol: String = "BANKNIFTY",
        val interval: String = "5m",
        val lots: Int = 1,
        val buy: String = "strategy",
        val sell: String = "strategy",
        /** Exit the open option at 15:15 whatever the script says. */
        val squareOff: Boolean = true,
        /** On a sell signal: "put" buys a PUT, "exit" only sells what is held. */
        val shortWith: String = "put",
        /** "trade" places orders; "alert" only notifies on each signal change. */
        val mode: String = "trade",
        /**
         * Sell the option when it falls this many points below its buy price. Compulsory (Boss's 06 Oct rule,
         * [com.optionslab.ira.PineProtection]): 30 to start, never 0 - a 0 is never saved ([protect]).
         */
        val stopPts: Double = com.optionslab.ira.PineProtection.STOP,
        /** Sell the option when it rises this many points above its buy price. Compulsory as [stopPts]: 60 to start. */
        val targetPts: Double = com.optionslab.ira.PineProtection.TARGET,
        /** Sell and stop for the day once this script has lost this many rupees today (0 = none). */
        val maxDayLoss: Double = 0.0,
        /**
         * The profit-lock ladder (com.optionslab.engine.orb.ProfitLock, as the ORB arms): at 25% of the way to the target
         * the stop moves to the buy price, at 50% it locks 25% of the target, at 75% 50%. The target is [targetPts]; with
         * none, twice [stopPts]; with neither there is no ladder. Beside it the percentage trail on the gain ([trail]),
         * which needs no stop or target; the higher of the two levels counts. It only ever sells sooner, never buys or
         * widens a stop. On for every script, the owner's own included (2026-10-06: "my profit went from 10 percent to
         * 2"), and compulsory since Boss's 06 Oct rule: it can no longer be switched off ([protect] turns it on).
         */
        val profitLock: Boolean = true,
        /** Written by Jarvis's Strategy Lab and armed on the owner's approval (set by IraHub when it arms one). */
        val byJarvis: Boolean = false,
        /** The owner once switched [profitLock] himself on the Pine screen: kept for older saves, no longer counts (the lock is always on). */
        val profitLockChosen: Boolean = false,
        /** The profit lock's percentage trail (start % and keep %), the owner's numbers for this script. */
        val trail: com.optionslab.engine.orb.ProfitLock.Trail = com.optionslab.engine.orb.ProfitLock.Trail(),
    )

    /**
     * [a] under Boss's 06 Oct rule ([com.optionslab.ira.PineProtection]): a stop and a target above 0 (a 0 or an unusable
     * number keeps [before]'s, else 30 / 60) and the profit lock on. A nonzero stop is never changed.
     */
    fun protect(a: Auto, before: Auto? = null): Auto {
        val e = com.optionslab.ira.PineProtection.Exits.of(a.stopPts, a.targetPts, a.profitLock, before?.stopPts, before?.targetPts)
        return if (e.stopPts == a.stopPts && e.targetPts == a.targetPts && a.profitLock) a
            else a.copy(stopPts = e.stopPts, targetPts = e.targetPts, profitLock = true)
    }

    data class Item(
        val id: Long, val name: String, val code: String, val onChart: Boolean = false,
        val inputs: Map<String, String> = emptyMap(), val auto: Auto = Auto(), val updated: Long = System.currentTimeMillis(),
    )

    private lateinit var file: File
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    /** Bumps when anything the chart draws changes (code, on-chart, inputs). */
    private val _chartRev = MutableStateFlow(0)
    val chartRev: StateFlow<Int> = _chartRev

    @Volatile private var loaded = false

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "pine.vault")
        // Keystore work stays off the main thread.
        Thread { ensure() }.start()
    }

    @Synchronized private fun ensure() {
        if (loaded) return
        val (list, notes) = load()
        _items.value = list; loaded = true
        // Boss's 06 Oct rule filled in a stop, a target or the lock: written back (so it is said once) and told in the log.
        if (notes.isNotEmpty() || needsRewrite) runCatching { save(list) }
        needsRewrite = false
        synchronized(ruleNotes) { ruleNotes += notes }
    }

    /** The saved file held a script without a stop, a target or the lock (rewritten once read). */
    @Volatile private var needsRewrite = false

    /** Armed scripts the rule protected on load: (script, the line for its activity log), taken by [PineAuto] once. */
    private val ruleNotes = ArrayList<Pair<Long, String>>()

    /** The lines [ensure] left for the auto-trader's log (each given once). */
    fun takeRuleNotes(): List<Pair<Long, String>> = synchronized(ruleNotes) { ruleNotes.toList().also { ruleNotes.clear() } }

    private fun load(): Pair<List<Item>, List<Pair<Long, String>>> = runCatching {
        val r = parseNoting(String(Vault.readFileSteady(file) ?: return emptyList<Item>() to emptyList<Pair<Long, String>>(), Charsets.UTF_8))
        needsRewrite = r.third
        r.first to r.second
    }.getOrElse { if (file.exists()) Vault.setAside(file); emptyList<Item>() to emptyList<Pair<Long, String>>() }

    /**
     * The saved scripts from the vault's text. Boss's 06 Oct rule ([protect]): every script has a stop-loss and a target
     * (a saved 0 or none becomes 30 / 60; a nonzero stop is kept as it is) and the profit lock ON - a saved false, even
     * one he chose ("profitLockChosen"), no longer counts. An armed script stays armed, now protected. The trail's numbers ("trail": {"be", "steps": [[start, keep]]})
     * are cleaned ([com.optionslab.engine.orb.ProfitLock.Trail.clean]); none saved: the defaults.
     * A save from before [Auto.byJarvis] is known as Jarvis's by what the Strategy
     * Lab writes into every script it makes: its first comment ("// Written by Jarvis from the ... pattern") or its
     * name ("Jarvis: <pattern> <market> <chart>", StrategyLab.name).
     */
    internal fun parse(text: String): List<Item> = parseNoting(text).first

    /**
     * [parse], with the activity-log lines for armed scripts the rule protected (once each: the file is rewritten) and
     * whether anything was changed at all.
     */
    internal fun parseNoting(text: String): Triple<List<Item>, List<Pair<Long, String>>, Boolean> {
        val a = JSONArray(text)
        val notes = ArrayList<Pair<Long, String>>()
        var changed = false
        val list = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ins = o.optJSONObject("inputs")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
            val au = o.optJSONObject("auto")?.let { x ->
                val jarvis = if (x.has("byJarvis")) x.optBoolean("byJarvis") else jarvisWrote(o.getString("name"), o.getString("code"))
                val e = com.optionslab.ira.PineProtection.normalise(x.optDouble("stopPts", 0.0), x.optDouble("targetPts", 0.0),
                    x.optBoolean("profitLock", true))
                val stop = e.stopPts
                val target = e.targetPts
                val chosen = x.optBoolean("profitLockChosen")
                if (e.stopAdded || e.targetAdded || e.lockForced) {
                    changed = true
                    if (x.optBoolean("on")) com.optionslab.ira.PineProtection.addedNote(o.getString("name"), e)?.let { notes += o.getLong("id") to it }
                }
                Auto(x.optBoolean("on"), x.optString("symbol", "BANKNIFTY"), x.optString("interval", "5m"), x.optInt("lots", 1).coerceIn(1, 50),
                    x.optString("buy", "strategy"), x.optString("sell", "strategy"), x.optBoolean("squareOff", true),
                    x.optString("shortWith", "put"), x.optString("mode", "trade").takeIf { it == "alert" } ?: "trade",
                    stop, target,
                    x.optDouble("maxDayLoss", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    profitLock = true, byJarvis = jarvis,
                    profitLockChosen = chosen, trail = trailOf(x.optJSONObject("trail")))
            } ?: Auto()
            Item(o.getLong("id"), o.getString("name"), o.getString("code"), o.optBoolean("onChart"), ins, au, o.optLong("updated"))
        }
        return Triple(list, notes, changed)
    }

    @Synchronized private fun save(list: List<Item>) {
        val a = JSONArray()
        for (it in list) a.put(JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("onChart", it.onChart)
            .put("updated", it.updated)
            .put("inputs", JSONObject().apply { it.inputs.forEach { (k, v) -> put(k, v) } })
            .put("auto", JSONObject().put("on", it.auto.on).put("symbol", it.auto.symbol).put("interval", it.auto.interval)
                .put("lots", it.auto.lots).put("buy", it.auto.buy).put("sell", it.auto.sell).put("squareOff", it.auto.squareOff).put("shortWith", it.auto.shortWith)
                .put("mode", it.auto.mode).put("stopPts", it.auto.stopPts).put("targetPts", it.auto.targetPts).put("maxDayLoss", it.auto.maxDayLoss)
                .put("profitLock", it.auto.profitLock).put("byJarvis", it.auto.byJarvis).put("profitLockChosen", it.auto.profitLockChosen)
                .put("trail", JSONObject().put("be", it.auto.trail.breakevenPct)
                    .put("steps", JSONArray().apply { it.auto.trail.steps.forEach { s -> put(JSONArray().put(s.startPct).put(s.keepPct)) } }))))
        Vault.writeFile(file, a.toString().toByteArray(Charsets.UTF_8))
        _items.value = list
    }

    /** The trail's numbers as saved (cleaned); none saved, or unreadable: the defaults. */
    private fun trailOf(t: JSONObject?): com.optionslab.engine.orb.ProfitLock.Trail {
        val d = com.optionslab.engine.orb.ProfitLock.Trail()
        if (t == null) return d
        val steps = t.optJSONArray("steps")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { p ->
                com.optionslab.engine.orb.ProfitLock.Trail.Step(p.optDouble(0, 0.0), p.optDouble(1, 0.0)) } }
        } ?: d.steps
        return com.optionslab.engine.orb.ProfitLock.Trail(t.optDouble("be", d.breakevenPct), steps).clean()
    }

    /** A script saved before [Auto.byJarvis] existed that the Strategy Lab wrote: its own first comment, or its name. */
    internal fun jarvisWrote(name: String, code: String): Boolean = code.contains("// Written by Jarvis from the ") || name.startsWith("Jarvis: ")

    @Synchronized fun wipe() { if (::file.isInitialized) file.delete(); _items.value = emptyList(); loaded = true; _chartRev.value++ }

    fun get(id: Long): Item? = _items.value.firstOrNull { it.id == id }

    /**
     * Every saved script, read from the vault first if the loader has not yet (never on the main thread); null before
     * [init] (nothing can be read yet).
     */
    fun loadNow(): List<Item>? {
        if (!::file.isInitialized) return null
        ensure()
        return _items.value
    }

    /**
     * Save (a new script when [item] has id 0); returns the saved script. The auto-trade
     * settings are never taken from [item]: a screen holding an older copy must not switch
     * auto-trading back on after the kill switch or a restore turned it off. Only
     * [setAuto] changes them, and a new script starts with auto-trade off. Whatever the path (the Pine screen, an import,
     * a template, Jarvis's Strategy Lab), what is written has a stop, a target and the profit lock ([protect]).
     */
    @Synchronized fun put(item: Item): Item = write(item, keepAuto = true)

    @Synchronized private fun write(item: Item, keepAuto: Boolean): Item {
        ensure()
        val list = _items.value.toMutableList()
        val drawn = get(item.id)
        val saved = if (item.id == 0L || drawn == null) item.copy(id = if (item.id == 0L) (list.maxOfOrNull { it.id } ?: 0) + 1 else item.id,
                auto = protect(if (keepAuto) item.auto.copy(on = false) else item.auto), updated = System.currentTimeMillis())
            else item.copy(auto = if (keepAuto) protect(drawn.auto) else protect(item.auto, drawn.auto), updated = System.currentTimeMillis())
        val i = list.indexOfFirst { it.id == saved.id }
        if (i >= 0) list[i] = saved else list += saved
        save(list)
        if (saved.onChart || drawn?.onChart == true) _chartRev.value++
        return saved
    }

    @Synchronized fun delete(id: Long) {
        ensure()
        val was = get(id)
        save(_items.value.filter { it.id != id })
        if (was?.onChart == true) _chartRev.value++
    }

    fun setOnChart(id: Long, on: Boolean) { get(id)?.let { put(it.copy(onChart = on)); _chartRev.value++ } }

    @Synchronized fun setAuto(id: Long, auto: Auto) { get(id)?.let { write(it.copy(auto = auto), keepAuto = false) } }

    /** Turn every auto-trading script off (the kill switch, a restore). */
    @Synchronized fun disarmAll() {
        ensure()
        val list = _items.value
        if (list.none { it.auto.on }) return
        save(list.map { it.copy(auto = it.auto.copy(on = false)) })
    }

    // ---- compiled scripts, cached by code ---------------------------------------------------

    private val compiled = object : LinkedHashMap<String, Pine.Compiled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pine.Compiled>?) = size > 24
    }

    @Synchronized fun compile(code: String): Pine.Compiled = compiled.getOrPut(code) { Pine.compile(code) }

    fun script(item: Item): Pine.Script? = (compile(item.code) as? Pine.Compiled.Ok)?.script

    /** The saved input values, typed for Pine.run. */
    fun inputValues(item: Item, s: Pine.Script): Map<String, Any?> = s.inputs.mapNotNull { d ->
        val v = item.inputs[d.key] ?: return@mapNotNull null
        d.key to when (d.kind) {
            "int", "float", "price" -> v.toDoubleOrNull() ?: return@mapNotNull null
            "bool" -> v == "true"
            else -> v
        }
    }.toMap()

    fun toPine(b: com.optionslab.engine.Upstox.Bar) = Pine.Bar(b.epochSecond, b.open, b.high, b.low, b.close, b.volume.toDouble())

    // ---- starters ---------------------------------------------------------------------------

    data class Sample(val name: String, val code: String)

    val SAMPLES = listOf(
        Sample("Blank indicator", """
            //@version=5
            indicator("My indicator", overlay = true)
            len = input.int(20, "Length", minval = 1)
            plot(ta.sma(close, len), "SMA", color = color.blue)
        """.trimIndent()),
        Sample("Blank strategy", """
            //@version=5
            strategy("My strategy", overlay = true, initial_capital = 100000, default_qty_value = 1)
            if ta.crossover(close, ta.sma(close, 20))
                strategy.entry("Long", strategy.long)
            if ta.crossunder(close, ta.sma(close, 20))
                strategy.close("Long")
        """.trimIndent()),
        Sample("EMA crossover (strategy)", """
            //@version=5
            strategy("EMA crossover", overlay = true, initial_capital = 100000,
                 default_qty_type = strategy.fixed, default_qty_value = 1)

            fastLen = input.int(9, "Fast EMA", minval = 1)
            slowLen = input.int(21, "Slow EMA", minval = 1)
            stopPts = input.float(60, "Stop loss (points)")
            targetPts = input.float(120, "Target (points)")

            fast = ta.ema(close, fastLen)
            slow = ta.ema(close, slowLen)

            if ta.crossover(fast, slow)
                strategy.entry("Long", strategy.long)
            if ta.crossunder(fast, slow)
                strategy.entry("Short", strategy.short)

            strategy.exit("Exit L", "Long", stop = strategy.position_avg_price - stopPts, limit = strategy.position_avg_price + targetPts)
            strategy.exit("Exit S", "Short", stop = strategy.position_avg_price + stopPts, limit = strategy.position_avg_price - targetPts)

            plot(fast, "Fast", color = color.blue, linewidth = 2)
            plot(slow, "Slow", color = color.orange, linewidth = 2)
        """.trimIndent()),
        Sample("Supertrend buy/sell (indicator)", """
            //@version=5
            indicator("Supertrend signals", overlay = true)
            factor = input.float(3.0, "Factor")
            atrLen = input.int(10, "ATR length")
            [st, dir] = ta.supertrend(factor, atrLen)
            plot(dir < 0 ? st : na, "Up trend", color = color.green, style = plot.style_linebr)
            plot(dir > 0 ? st : na, "Down trend", color = color.red, style = plot.style_linebr)
            buy = ta.change(dir) < 0
            sell = ta.change(dir) > 0
            plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green, text = "BUY")
            plotshape(sell, "Sell", shape.labeldown, location.abovebar, color.red, text = "SELL")
            alertcondition(buy, "Buy", "Supertrend turned up")
            alertcondition(sell, "Sell", "Supertrend turned down")
        """.trimIndent()),
        Sample("RSI (indicator)", """
            //@version=5
            indicator("RSI", overlay = false)
            len = input.int(14, "Length")
            r = ta.rsi(close, len)
            plot(r, "RSI", color = color.purple)
            hline(70, "Overbought", color = color.red)
            hline(30, "Oversold", color = color.green)
        """.trimIndent()),
    )
}
