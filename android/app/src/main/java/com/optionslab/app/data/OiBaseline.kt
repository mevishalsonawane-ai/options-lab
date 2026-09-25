package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.OptLeg
import org.json.JSONObject

/**
 * "OI change today" needs where each contract's open interest started the day.
 * The Upstox chain carries it (its first minute bar); a Zerodha chain is one
 * snapshot, so the first reading seen today is kept here and used as the base
 * for the rest of the day. Private like everything else: in the vault.
 */
object OiBaseline {
    private const val KEY = "oi.baseline"

    @Synchronized
    fun apply(rows: List<ChainRow>): List<ChainRow> {
        val today = Market.today().toString()
        val o = runCatching { JSONObject(SecurePrefs.getString(KEY) ?: "{}") }.getOrDefault(JSONObject())
        val base = if (o.optString("day") == today) o.optJSONObject("m") ?: JSONObject() else JSONObject()
        var changed = false
        fun leg(l: OptLeg?): OptLeg? {
            if (l == null || l.symbol.isEmpty() || l.oi <= 0 || l.prevOi != null) return l
            if (!base.has(l.symbol)) { base.put(l.symbol, l.oi); changed = true }
            return l.copy(prevOi = base.getLong(l.symbol))
        }
        val out = rows.map { it.copy(ce = leg(it.ce), pe = leg(it.pe)) }
        if (changed) SecurePrefs.put(KEY, JSONObject().put("day", today).put("m", base).toString())
        return out
    }
}
