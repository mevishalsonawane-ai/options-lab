package com.optionslab.engine.mcx

import com.optionslab.engine.strategy.Json
import java.util.Locale

/**
 * What an MCX order blocks at Zerodha (research/MCX_GUIDE.md 1a). Zerodha's intraday (MIS) margin equals the overnight
 * (NRML) margin on MCX: there is no extra intraday leverage, so the product never changes the figure here.
 *
 *  - a future, bought or sold: the margin per lot x lots;
 *  - an option bought: the premium (price x multiplier x lots);
 *  - an option sold: about a future's margin per lot (Zerodha's SPAN figure differs a little; the live check asks Zerodha).
 *
 * The margin per lot is Zerodha's public commodity margin list when read ([parseKite]: api.kite.trade/margins/commodity),
 * else the fallback table ([Mcx.COMMODITIES], as of [Mcx.TABLE_AS_OF]). A live order is always checked with Zerodha's own
 * basket margin call; this is for paper and for a figure on a screen. Pure.
 */
object McxMargin {
    /** Rs per lot of the near future of [name]: [feed] (Zerodha's list) first, else the fallback table; null when neither knows it. */
    fun perLot(name: String, feed: Map<String, Double> = emptyMap()): Double? =
        feed[name.uppercase(Locale.ROOT)]?.takeIf { it > 0 && it.isFinite() } ?: Mcx.commodity(name)?.marginPerLot

    /** Rupees blocked by [lots] of [c] at [price] ([buy]: a buy order). Null when the margin per lot is not known for a future or a sold option. */
    fun required(c: McxContract, buy: Boolean, lots: Int, price: Double, feed: Map<String, Double> = emptyMap()): Double? {
        val n = kotlin.math.abs(lots)
        if (c.isOption && buy) return price * c.multiplier * n
        return perLot(c.name, feed)?.let { it * n }
    }

    /**
     * Zerodha's public commodity margin list: each commodity's rows (near month first), keeping the near month's NRML
     * margin per lot. Rows that do not read are skipped.
     */
    fun parseKite(body: String): Map<String, Double> {
        val arr = runCatching { Json.parse(body) }.getOrNull() as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (r in arr) {
            val m = r as? Map<*, *> ?: continue
            val name = (m["tradingsymbol"] as? String)?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: continue
            val nrml = (m["nrml_margin"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: continue
            out.putIfAbsent(name, nrml)
        }
        return out
    }

    /** "1 lot of CRUDEOIL needs about Rs 2,67,290 (Zerodha's margin; MIS is the same on MCX)". */
    fun say(name: String, lots: Int, rupees: Double): String =
        "$lots lot${if (lots == 1) "" else "s"} of $name needs about Rs ${"%,.0f".format(Locale.ENGLISH, rupees)} (Zerodha's margin; MIS is the same on MCX)"
}
