package com.optionslab.app.data

import com.optionslab.engine.pine.Pine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's Pine scripts as chart indicators: the list the chart page registers, and
 * each script run over the chart's own candles. Called by the chart page synchronously.
 */
object PineChart {
    fun chartId(item: PineScripts.Item) = "pine-${item.id}"

    /** Every script that compiles: its plots, inputs, and whether it is shown on the chart. */
    fun list(): String {
        val a = JSONArray()
        for (item in PineScripts.items.value) {
            val s = PineScripts.script(item) ?: continue
            val plots = JSONArray()
            s.plots.forEachIndexed { i, p -> plots.put(JSONObject().put("key", "p$i").put("title", p.title).put("color", p.color).put("style", p.style)) }
            val inputs = JSONArray()
            s.inputs.forEachIndexed { i, d ->
                if (d.kind !in setOf("int", "float", "price", "bool", "source")) return@forEachIndexed
                val saved = item.inputs[d.key]
                val def: Any? = when (d.kind) {
                    "bool" -> saved?.let { it == "true" } ?: d.default
                    "source" -> saved ?: d.default
                    else -> saved?.toDoubleOrNull() ?: d.default
                }
                inputs.put(JSONObject().put("key", "in$i").put("kind", d.kind).put("label", d.key).put("default", def ?: JSONObject.NULL)
                    .put("min", d.min ?: JSONObject.NULL).put("max", d.max ?: JSONObject.NULL))
            }
            a.put(JSONObject().put("id", chartId(item)).put("name", s.title.ifBlank { item.name }).put("overlay", s.overlay)
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
            val r = Pine.run(s, bars, values, symbol.uppercase(), interval)
            val vs = JSONObject()
            r.plots.forEachIndexed { i, arr -> vs.put("p$i", JSONArray().apply { arr.forEach { put(if (it.isNaN() || it.isInfinite()) JSONObject.NULL else it) } }) }
            out.put("values", vs)
            out.put("markers", JSONArray().apply {
                r.markers.takeLast(2000).forEach { m -> put(JSONArray().put(m.bar).put(if (m.above) 1 else 0).put(m.shape).put(m.color).put(m.text)) }
            })
            r.error?.let { out.put("error", it.toString()) }
        } catch (e: Exception) {
            out.put("error", e.message ?: "Could not run the script")
        }
        return out.toString()
    }
}
