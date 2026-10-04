package com.optionslab.ira

import com.optionslab.engine.options.ChainAnalytics
import com.optionslab.engine.options.ChainSnapshot
import java.util.Locale

/**
 * The option chain in plain words, from the same analytics as the app's Options tab: put-call ratio, max pain, where
 * the biggest call and put open interest sits (the strikes option writers defend), where OI was added today, ATM IV
 * and skew. Facts; what writers "defend" is how traders read OI walls, said as such. Pure.
 */
object ChainRead {
    private fun n(x: Long) = "%,d".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)

    fun lines(c: ChainSnapshot): List<String> {
        val u = c.underlying
        val out = ArrayList<String>()
        out += "$u ${c.expiry} chain (spot ${k(c.spot)}): put-call ratio ${"%.2f".format(Locale.ENGLISH, c.pcr.pcrOi)} on open interest, " +
            "${"%.2f".format(Locale.ENGLISH, c.pcr.pcrVolume)} on volume."
        c.maxPain?.let { out += "$u max pain ${k(it.maxPainStrike)}: where option writers lose least at expiry." }
        val ce = c.rows.filter { (it.ce?.oi ?: 0) > 0 }.maxByOrNull { it.ce!!.oi }
        val pe = c.rows.filter { (it.pe?.oi ?: 0) > 0 }.maxByOrNull { it.pe!!.oi }
        if (ce != null && pe != null) out += "$u biggest call OI at ${k(ce.strike)} (${n(ce.ce!!.oi)}), read as resistance; biggest put OI at ${k(pe.strike)} (${n(pe.pe!!.oi)}), read as support."
        val ch = runCatching { ChainAnalytics.oiChange(c.rows) }.getOrDefault(emptyList())
        val ceAdd = ch.maxByOrNull { it.ceOiChange }?.takeIf { it.ceOiChange > 0 }
        val peAdd = ch.maxByOrNull { it.peOiChange }?.takeIf { it.peOiChange > 0 }
        if (ceAdd != null || peAdd != null) out += "$u OI added today: " + listOfNotNull(
            ceAdd?.let { "calls at ${k(it.strike)} (+${n(it.ceOiChange.toLong())})" }, peAdd?.let { "puts at ${k(it.strike)} (+${n(it.peOiChange.toLong())})" }).joinToString(", ") +
            (if (ceAdd != null && peAdd != null) when {
                peAdd.peOiChange > 1.5 * ceAdd.ceOiChange -> "; put writing leads (traders read that as support building)."
                ceAdd.ceOiChange > 1.5 * peAdd.peOiChange -> "; call writing leads (traders read that as a ceiling building)."
                else -> "; both sides about even."
            } else ".")
        c.atmIv?.let { iv -> out += "$u ATM implied volatility ${"%.1f".format(Locale.ENGLISH, iv)}%" + (c.ivSmile?.skew?.let { s -> ", put skew ${"%+.1f".format(Locale.ENGLISH, s)} points." } ?: ".") }
        return out
    }
}
