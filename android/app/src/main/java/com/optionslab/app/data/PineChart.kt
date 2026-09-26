package com.optionslab.app.data

import com.optionslab.engine.pine.Pine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's Pine scripts as chart indicators: the list the chart page registers, and
 * each script run over the chart's own candles. Called by the chart page synchronously.
 */
object PineChart {
    /** A script's run on the chart may take this long (it runs on every new candle). */
    private const val BUDGET_MS = 1_500L

    /**
     * Text from a script (titles, labels, marker text) as plain text for the chart page:
     * no markup characters, no control characters, bounded length. The chart already
     * writes these as text; this keeps it so even if that ever changed.
     */
    fun plain(s: String?, max: Int = 40): String =
        (s ?: "").filter { it >= ' ' && it !in "<>&\"'`\\" }.take(max)

    fun chartId(item: PineScripts.Item) = "pine-${item.id}"

    /** Every script that compiles: its plots, inputs, and whether it is shown on the chart. */
    fun list(): String {
        val a = JSONArray()
        for (item in PineScripts.items.value) {
            val s = PineScripts.script(item) ?: continue
            val plots = JSONArray()
            s.plots.forEachIndexed { i, p -> plots.put(JSONObject().put("key", "p$i").put("title", plain(p.title).ifBlank { "Plot ${i + 1}" })
                .put("color", Pine.safeColor(p.color) ?: "#2962FF").put("style", p.style)) }
            val inputs = JSONArray()
            s.inputs.forEachIndexed { i, d ->
                if (d.kind !in setOf("int", "float", "price", "bool", "source")) return@forEachIndexed
                val saved = item.inputs[d.key]
                val def: Any? = when (d.kind) {
                    "bool" -> saved?.let { it == "true" } ?: d.default
                    "source" -> saved ?: d.default
                    else -> saved?.toDoubleOrNull() ?: d.default
                }
                inputs.put(JSONObject().put("key", "in$i").put("kind", d.kind).put("label", plain(d.key).ifBlank { "Input ${i + 1}" }).put("default", (if (def is String) plain(def, 12) else def) ?: JSONObject.NULL)
                    .put("min", d.min ?: JSONObject.NULL).put("max", d.max ?: JSONObject.NULL))
            }
            a.put(JSONObject().put("id", chartId(item)).put("name", plain(s.title).ifBlank { plain(item.name) }.ifBlank { "Pine" }).put("overlay", s.overlay)
                .put("onChart", item.onChart).put("plots", plots).put("inputs", inputs))
        }
        return a.toString()
    }

    /** Run script [id] over [barsJson] ([[t, o, h, l, c, v], ...]) with the chart's input values. */
    fun calc(id: String, symbol: String, interval: String, barsJson: String, inputsJson: String): String {
        val out = JSONObject()
        try {
            val item = PineScripts.items.value.firstOrNull { chartId(it) == id } ?: return out.put("error", "Script not found").toString()
            val s = PineScripts.script(item) ?: return out.put("error", "The script has errors").toString()
            val a = JSONArray(barsJson)
            val bars = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                Pine.Bar(r.getLong(0), r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optDouble(5, 0.0).let { if (it.isNaN()) 0.0 else it })
            }
            val chartInputs = runCatching { JSONObject(inputsJson) }.getOrNull()
            val values = HashMap(PineScripts.inputValues(item, s))
            s.inputs.forEachIndexed { i, d ->
                val v = chartInputs?.opt("in$i") ?: return@forEachIndexed
                if (v != JSONObject.NULL) values[d.key] = when (v) { is Number -> v.toDouble(); is Boolean -> v; else -> v.toString() }
            }
            if (bars.size > 20_000) return out.put("error", "Too many candles").toString()
            val r = Pine.run(s, bars, values, plain(symbol, 30).uppercase(), plain(interval, 6), budgetMs = BUDGET_MS)
            val vs = JSONObject()
            r.plots.forEachIndexed { i, arr -> vs.put("p$i", JSONArray().apply { arr.forEach { put(if (it.isNaN() || it.isInfinite()) JSONObject.NULL else it) } }) }
            out.put("values", vs)
            out.put("markers", JSONArray().apply {
                r.markers.takeLast(2000).forEach { m -> put(JSONArray().put(m.bar).put(if (m.above) 1 else 0).put(m.shape).put(Pine.safeColor(m.color) ?: "#787B86").put(plain(m.text, 16))) }
            })
            r.error?.let { out.put("error", plain(it.toString(), 200)) }
        } catch (e: Throwable) {
            // Called from the chart page's thread: nothing may escape and take the app down.
            out.put("error", plain(e.message ?: "Could not run the script", 200))
        }
        return out.toString()
    }
}
