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
    )

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
        _items.value = load(); loaded = true
    }

    private fun load(): List<Item> = runCatching {
        val a = JSONArray(String(Vault.readFileSteady(file) ?: return emptyList(), Charsets.UTF_8))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ins = o.optJSONObject("inputs")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
            val au = o.optJSONObject("auto")?.let { x ->
                Auto(x.optBoolean("on"), x.optString("symbol", "BANKNIFTY"), x.optString("interval", "5m"), x.optInt("lots", 1).coerceIn(1, 50),
                    x.optString("buy", "strategy"), x.optString("sell", "strategy"), x.optBoolean("squareOff", true),
                    x.optString("shortWith", "put"))
            } ?: Auto()
            Item(o.getLong("id"), o.getString("name"), o.getString("code"), o.optBoolean("onChart"), ins, au, o.optLong("updated"))
        }
    }.getOrElse { if (file.exists()) Vault.setAside(file); emptyList() }

    @Synchronized private fun save(list: List<Item>) {
        val a = JSONArray()
        for (it in list) a.put(JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("onChart", it.onChart)
            .put("updated", it.updated)
            .put("inputs", JSONObject().apply { it.inputs.forEach { (k, v) -> put(k, v) } })
            .put("auto", JSONObject().put("on", it.auto.on).put("symbol", it.auto.symbol).put("interval", it.auto.interval)
                .put("lots", it.auto.lots).put("buy", it.auto.buy).put("sell", it.auto.sell).put("squareOff", it.auto.squareOff).put("shortWith", it.auto.shortWith)))
        Vault.writeFile(file, a.toString().toByteArray(Charsets.UTF_8))
        _items.value = list
    }

    @Synchronized fun wipe() { if (::file.isInitialized) file.delete(); _items.value = emptyList(); loaded = true; _chartRev.value++ }

    fun get(id: Long): Item? = _items.value.firstOrNull { it.id == id }

    /** Save (a new script when [item] has id 0); returns the saved script. */
    @Synchronized fun put(item: Item): Item {
        ensure()
        val list = _items.value.toMutableList()
        val drawn = get(item.id)
        val saved = if (item.id == 0L) item.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1, updated = System.currentTimeMillis())
            else item.copy(updated = System.currentTimeMillis())
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

    fun setAuto(id: Long, auto: Auto) { get(id)?.let { put(it.copy(auto = auto)) } }

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
