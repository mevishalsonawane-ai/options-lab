package com.optionslab.ira

import java.util.Locale

/**
 * Institutional money: NSE's daily FII/FPI and DII cash-market figures (Rs crore), from NSE's own page. Facts: who
 * bought and sold how much, and the net. Pure (the app fetches the text).
 */
object Flows {
    data class Flow(val who: String, val date: String, val buy: Double, val sell: Double, val net: Double)

    /** NSE's JSON list ([{"category":"DII","date":"01-Oct-2026","buyValue":"..","sellValue":"..","netValue":".."}, ...]). */
    fun parse(json: String): List<Flow> {
        val out = ArrayList<Flow>()
        for (m in rx("\\{[^{}]*\\}").findAll(json)) {
            val o = m.value
            fun f(k: String) = rx("\"$k\"\\s*:\\s*\"?([^\",}]*)\"?").find(o)?.groupValues?.get(1)?.trim()
            val who = f("category") ?: continue
            val net = f("netValue")?.replace(",", "")?.toDoubleOrNull() ?: continue
            out += Flow(if (who.contains("FII", true) || who.contains("FPI", true)) "FII" else if (who.contains("DII", true)) "DII" else who.uppercase(), f("date") ?: "",
                f("buyValue")?.replace(",", "")?.toDoubleOrNull() ?: 0.0, f("sellValue")?.replace(",", "")?.toDoubleOrNull() ?: 0.0, net)
        }
        return out
    }

    private fun cr(x: Double) = (if (x < 0) "-" else "+") + "Rs " + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x)) + " crore"

    fun lines(f: List<Flow>): List<String> {
        if (f.isEmpty()) return listOf("No FII/DII figures from NSE just now.")
        val fii = f.firstOrNull { it.who == "FII" }; val dii = f.firstOrNull { it.who == "DII" }
        if (fii == null && dii == null) return listOf("No FII/DII figures from NSE just now.")
        val out = ArrayList<String>()
        out += "Institutional flows (${(fii ?: dii)!!.date}, NSE): " + listOfNotNull(fii?.let { "FIIs net ${cr(it.net)}" }, dii?.let { "DIIs net ${cr(it.net)}" }).joinToString(", ") + "."
        if (fii != null && dii != null) out += when {
            fii.net < 0 && dii.net > 0 -> "Foreign funds sold and domestic funds bought" + if (dii.net > -fii.net) ": domestic buying more than covered it." else ": domestic buying did not cover it."
            fii.net > 0 && dii.net > 0 -> "Both foreign and domestic funds were net buyers."
            fii.net < 0 && dii.net < 0 -> "Both foreign and domestic funds were net sellers."
            else -> "Foreign funds bought and domestic funds sold."
        }
        return out
    }
}
