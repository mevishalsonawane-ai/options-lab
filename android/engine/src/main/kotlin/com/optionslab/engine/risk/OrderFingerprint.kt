package com.optionslab.engine.risk

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * The order fingerprint (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT): a risk verdict is only good
 * for the EXACT order it judged. The checks that clear an order (the account guard, Zerodha's limits, the arms' gates, Boss's
 * review) stamp their verdict with a hash of what they looked at; the sender computes the same hash of what it is about to
 * send and refuses when they differ, or when the verdict is not from today. A refusal only ever lowers risk, so it applies
 * on paper and live alike; an exit is never refused by it (closing lowers risk).
 *
 * Pure and memory only: a string hash, no clock, no I/O, so it sits on the order path. The hash is SHA-256 of the fields in
 * one fixed order, with prices on the paise, shortened to 16 hex characters (a mismatch is what matters, not secrecy).
 */
object OrderFingerprint {
    /** What a verdict is about: every field whose change would make it another order. */
    data class Fields(
        val symbol: String,
        val side: String,
        val qty: Int,
        val product: String,
        val orderType: String,
        /** The limit price (LIMIT / SL); null for a MARKET order. */
        val price: Double?,
        /** The trigger (SL / SL-M); null otherwise. */
        val trigger: Double?,
        /** The stop and target the sender will rest or watch, when it knows them at the check. */
        val stop: Double?,
        val target: Double?,
        val day: LocalDate,
        val live: Boolean,
        /** Who is sending: an arm's key, "manual", a strategy's label. */
        val source: String,
    ) {
        fun canonical(): String = listOf(symbol.trim().uppercase(Locale.ENGLISH), side.trim().uppercase(Locale.ENGLISH), qty.toString(),
            product.trim().uppercase(Locale.ENGLISH), orderType.trim().uppercase(Locale.ENGLISH), paise(price), paise(trigger), paise(stop),
            paise(target), day.toString(), if (live) "live" else "paper", source.trim()).joinToString("|")

        fun fingerprint(): String = hash(canonical())
    }

    /** A verdict stamped with the fields it judged: [passed] with no [reasons], or refused with them. */
    data class Verdict(val fields: Fields, val passed: Boolean, val reasons: List<String> = emptyList(), val by: String = "") {
        val fingerprint: String get() = fields.fingerprint()
    }

    /** Null when [v] clears [now] today; else the plain reason it does not (which field changed, the wrong day, no pass). */
    fun refusal(v: Verdict?, now: Fields): String? {
        if (v == null) return "no check cleared this order (nothing was stamped for it)"
        if (!v.passed) return "the check refused this order" + (v.reasons.takeIf { it.isNotEmpty() }?.let { ": " + it.joinToString(" ") } ?: "")
        if (v.fields.day != now.day) return "the check is from ${v.fields.day}, not today (${now.day}): it must be run again"
        if (v.fingerprint == now.fingerprint()) return null
        val changed = diff(v.fields, now)
        return "the order changed after its check: " + (changed.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "a field differs")
    }

    /** Which fields of [a] differ in [b], in plain words ("quantity 75 → 150"). */
    fun diff(a: Fields, b: Fields): List<String> {
        val out = ArrayList<String>()
        fun <T> f(name: String, x: T?, y: T?, show: (T?) -> String = { it?.toString() ?: "none" }) { if (x != y) out += "$name ${show(x)} → ${show(y)}" }
        f("symbol", a.symbol, b.symbol)
        f("side", a.side, b.side)
        f("quantity", a.qty, b.qty)
        f("product", a.product, b.product)
        f("order type", a.orderType, b.orderType)
        f("price", paise(a.price), paise(b.price), ::money)
        f("trigger", paise(a.trigger), paise(b.trigger), ::money)
        f("stop", paise(a.stop), paise(b.stop), ::money)
        f("target", paise(a.target), paise(b.target), ::money)
        f("day", a.day, b.day)
        f("account", a.live, b.live) { if (it == true) "live" else "paper" }
        f("source", a.source, b.source)
        return out
    }

    /** A price on the paise ("123.45"); "-" for none; a NaN or infinite price is "?" (never equal to a real one). */
    private fun paise(x: Double?): String = when {
        x == null -> "-"
        !x.isFinite() -> "?"
        else -> String.format(Locale.ENGLISH, "%.2f", x)
    }

    private fun money(p: String?): String = when (p) { null, "-" -> "none"; else -> p }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 8) sb.append(String.format(Locale.ENGLISH, "%02x", d[i].toInt() and 0xff))
        return sb.toString()
    }
}
