package com.optionslab.ira

import java.util.Locale

/**
 * One specialist's settings inside a [ManagerConfig]: its [weight] in the Chair's quorum, whether it [enabled] counts (a
 * muted or switched-off one still looks, so its record goes on, and its hard veto, if it has one, always stands), whether
 * an extension needs its agreement ([gate]), and its own [thresholds] by name (each specialist's defaults are in
 * [ManagerTeam.DEFAULT_SPECS]).
 */
data class SpecConfig(
    val weight: Double = 1.0,
    val enabled: Boolean = true,
    val gate: Boolean = false,
    val thresholds: Map<String, Double> = emptyMap(),
)

/**
 * The trade manager's committee for one strategy ([strategyId]: its family key, "pine", "solo", ...): each specialist's
 * [SpecConfig] and the Chair's rules ([ManagerTeam.decide]) - the weighted EXIT [quorum], the EXTEND [superMajority], the
 * [exitFloor] (an EXIT vote this strong or more holds every extension back and counts as "against" for the cooldown), how
 * long the extension's agreement must last ([extendHoldSec], never under the original 30 s), and how strong a vote must
 * be to go on the findings bus ([postAt]).
 *
 * A plain data structure: the research's recommended numbers drop in as a key=value or JSON block ([parse]), and a
 * block can never touch the safety: the lock and its invariants, the hard vetoes, the hold times of the original rules,
 * the cooldown, the extension cap and the square-off are the Chair's own, not settings. Out-of-range values are kept in
 * range ([bounded]). Pure.
 */
data class ManagerConfig(
    val strategyId: String,
    val quorum: Double = 1.0,
    val superMajority: Double = 0.75,
    val exitFloor: Int = 50,
    val extendHoldSec: Int = 30,
    val postAt: Int = 70,
    val specialists: Map<String, SpecConfig> = ManagerTeam.DEFAULT_SPECS,
) {
    fun spec(id: String): SpecConfig = specialists[id] ?: ManagerTeam.DEFAULT_SPECS[id] ?: SpecConfig(0.0, enabled = false)

    /** The specialist's weight when it counts (enabled, weight above 0); 0 otherwise. */
    fun weight(id: String): Double = spec(id).let { if (it.enabled && it.weight > 0) it.weight else 0.0 }

    fun counts(id: String): Boolean = weight(id) > 0

    /** A threshold of [id]'s: this config's, else the default's, else [default]. */
    fun th(id: String, key: String, default: Double): Double =
        specialists[id]?.thresholds?.get(key) ?: ManagerTeam.DEFAULT_SPECS[id]?.thresholds?.get(key) ?: default

    /** [id]'s settings replaced by [f] of them. */
    fun with(id: String, f: (SpecConfig) -> SpecConfig): ManagerConfig = copy(specialists = specialists + (id to f(spec(id))))

    /** Every number kept in its range (the parser's and the tuner's last word). */
    fun bounded(): ManagerConfig = copy(
        quorum = quorum.finiteOr(1.0).coerceIn(MIN_QUORUM, MAX_QUORUM),
        superMajority = superMajority.finiteOr(0.75).coerceIn(0.5, 1.0),
        exitFloor = exitFloor.coerceIn(1, 100),
        extendHoldSec = extendHoldSec.coerceIn(MIN_EXTEND_HOLD_SEC, 600),
        postAt = postAt.coerceIn(1, 101),
        specialists = specialists.filterKeys { it in ManagerTeam.IDS }.mapValues { (_, s) ->
            s.copy(weight = s.weight.finiteOr(0.0).coerceIn(0.0, MAX_WEIGHT), thresholds = s.thresholds.filterValues { it.isFinite() })
        },
    )

    companion object {
        const val MIN_QUORUM = 0.25
        const val MAX_QUORUM = 10.0
        const val MAX_WEIGHT = 5.0
        /** The extension's agreement lasts at least as long as the original rule's (30 s). */
        const val MIN_EXTEND_HOLD_SEC = 30

        private fun Double.finiteOr(d: Double) = if (isFinite()) this else d

        private val TOP = mapOf("quorum" to "quorum", "supermajority" to "supermajority", "super_majority" to "supermajority",
            "exitfloor" to "exitfloor", "exit_floor" to "exitfloor", "extendholdsec" to "extendholdsec", "extend_hold_sec" to "extendholdsec",
            "postat" to "postat", "post_at" to "postat")

        /**
         * The research's block, as key=value lines or as JSON, into one config per strategy (each starting from the defaults).
         *
         * key=value: `[pine]` starts a strategy's section (or `strategy=pine`, or a `pine.` prefix on each key); then
         * `quorum=1.2`, `supermajority=0.8`, `exit_floor=50`, `extend_hold_sec=45`, `post_at=70`, and per specialist
         * `vwap.weight=1.4`, `vwap.enabled=false`, `vwap.gate=true`, `vwap.stretchSd=2.5` (any other name is a threshold).
         * `#` starts a comment. JSON: `{"pine": {"quorum": 1.2, "specialists": {"vwap": {"weight": 1.4, "stretchSd": 2.5}}}}`,
         * or one strategy's object with `"strategy": "pine"`, or `{"strategies": [ ... ]}`. Unknown keys and specialists are
         * left out; nothing in a block can reach the safety rules.
         */
        fun parse(text: String): Map<String, ManagerConfig> {
            val trimmed = text.trim()
            val json = trimmed.startsWith("{") || (trimmed.startsWith("[") && trimmed.drop(1).trimStart().startsWith("{"))
            val pairs = if (json) jsonPairs(trimmed) else kvPairs(trimmed)
            val out = LinkedHashMap<String, ManagerConfig>()
            for ((strategy, key, value) in pairs) {
                val sid = strategy.trim().lowercase(Locale.ENGLISH)
                if (sid.isEmpty()) continue
                apply(out[sid] ?: ManagerConfig(sid), key, value)?.let { out[sid] = it }
            }
            return out.mapValues { it.value.bounded() }
        }

        private fun bool(v: String): Boolean? = when (v.trim().lowercase(Locale.ENGLISH)) {
            "true", "yes", "on", "1" -> true; "false", "no", "off", "0" -> false; else -> null
        }

        /** [key] ("quorum", "vwap.weight", "vwap.stretchSd") set to [v] in [c]; null: not understood. */
        private fun apply(c: ManagerConfig, key: String, v: String): ManagerConfig? {
            val k = key.trim()
            val num = v.trim().toDoubleOrNull()
            TOP[k.lowercase(Locale.ENGLISH)]?.let { top ->
                val n = num ?: return null
                return when (top) {
                    "quorum" -> c.copy(quorum = n)
                    "supermajority" -> c.copy(superMajority = n)
                    "exitfloor" -> c.copy(exitFloor = n.toInt())
                    "extendholdsec" -> c.copy(extendHoldSec = n.toInt())
                    else -> c.copy(postAt = n.toInt())
                }
            }
            val id = k.substringBefore('.').lowercase(Locale.ENGLISH)
            val field = k.substringAfter('.', "")
            if (id !in ManagerTeam.IDS || field.isEmpty()) return null
            return when (field.lowercase(Locale.ENGLISH)) {
                "weight" -> { val n = num ?: return null; c.with(id) { it.copy(weight = n) } }
                "enabled" -> { val b = bool(v) ?: return null; c.with(id) { it.copy(enabled = b) } }
                "gate" -> { val b = bool(v) ?: return null; c.with(id) { it.copy(gate = b) } }
                else -> { val n = num ?: return null; c.with(id) { it.copy(thresholds = it.thresholds + (field to n)) } }
            }
        }

        /** (strategy, key, value) from key=value lines. */
        private fun kvPairs(text: String): List<Triple<String, String, String>> {
            val out = ArrayList<Triple<String, String, String>>()
            var section = ""
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) { section = line.removeSurrounding("[", "]").trim(); continue }
                if ('=' !in line) continue
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().removeSurrounding("\"")
                if (key.equals("strategy", ignoreCase = true) || key.equals("strategyId", ignoreCase = true)) { section = value; continue }
                val first = key.substringBefore('.').lowercase(Locale.ENGLISH)
                if (first in ManagerTeam.IDS || TOP.containsKey(key.lowercase(Locale.ENGLISH))) out += Triple(section, key, value)
                else if ('.' in key) out += Triple(first, key.substringAfter('.'), value)
            }
            return out
        }

        /** (strategy, key, value) from a JSON block (see [parse]). */
        private fun jsonPairs(text: String): List<Triple<String, String, String>> {
            val root = runCatching { Json(text).read() }.getOrNull() ?: return emptyList()
            val out = ArrayList<Triple<String, String, String>>()
            fun one(sid: String, o: Map<*, *>) {
                for ((k, v) in o) {
                    val key = k.toString()
                    when {
                        key == "strategy" || key == "strategyId" -> {}
                        key == "specialists" && v is Map<*, *> -> for ((id, sv) in v) if (sv is Map<*, *>) {
                            for ((f, fv) in sv) if (f.toString() == "thresholds" && fv is Map<*, *>) {
                                for ((tk, tv) in fv) out += Triple(sid, "$id.$tk", tv.toString())
                            } else out += Triple(sid, "$id.$f", fv.toString())
                        }
                        v !is Map<*, *> && v !is List<*> -> out += Triple(sid, key, v.toString())
                    }
                }
            }
            fun strategyObject(o: Map<*, *>): Boolean = o["strategy"] is String || o["strategyId"] is String
            when (root) {
                is List<*> -> for (o in root) if (o is Map<*, *> && strategyObject(o)) one((o["strategy"] ?: o["strategyId"]).toString(), o)
                is Map<*, *> -> when {
                    strategyObject(root) -> one((root["strategy"] ?: root["strategyId"]).toString(), root)
                    root["strategies"] is List<*> -> for (o in root["strategies"] as List<*>)
                        if (o is Map<*, *> && strategyObject(o)) one((o["strategy"] ?: o["strategyId"]).toString(), o)
                    else -> for ((sid, o) in root) if (o is Map<*, *>) one(sid.toString(), o)
                }
            }
            return out
        }

        /** [c] as key=value lines (what [parse] reads back), only what differs from the defaults. */
        fun encode(c: ManagerConfig): String {
            val d = ManagerConfig(c.strategyId)
            val lines = ArrayList<String>()
            lines += "[${c.strategyId}]"
            if (c.quorum != d.quorum) lines += "quorum=${c.quorum}"
            if (c.superMajority != d.superMajority) lines += "supermajority=${c.superMajority}"
            if (c.exitFloor != d.exitFloor) lines += "exit_floor=${c.exitFloor}"
            if (c.extendHoldSec != d.extendHoldSec) lines += "extend_hold_sec=${c.extendHoldSec}"
            if (c.postAt != d.postAt) lines += "post_at=${c.postAt}"
            for (id in ManagerTeam.IDS) {
                val s = c.spec(id); val ds = d.spec(id)
                if (s.weight != ds.weight) lines += "$id.weight=${s.weight}"
                if (s.enabled != ds.enabled) lines += "$id.enabled=${s.enabled}"
                if (s.gate != ds.gate) lines += "$id.gate=${s.gate}"
                for ((k, v) in s.thresholds.toSortedMap()) if (ds.thresholds[k] != v) lines += "$id.$k=$v"
            }
            return lines.joinToString("\n")
        }
    }
}

/** A small JSON reader (objects, arrays, strings, numbers, true / false / null) for the research's block. Pure. */
internal class Json(private val s: String) {
    private var i = 0

    fun read(): Any? { val v = value(); ws(); require(i == s.length) { "trailing text" }; return v }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "end of text" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c'")
        }
    }

    private fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad word" }; i += w.length; return v }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(st, i).toDouble()
    }

    private fun str(): String {
        i++
        val b = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) {
                i++
                when (s[i]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(s[i])
                }
            } else b.append(s[i])
            i++
        }
        require(i < s.length) { "unterminated string" }
        i++
        return b.toString()
    }

    private fun obj(): Map<String, Any?> {
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws(); require(i < s.length && s[i] == ':') { "':' expected" }; i++
            m[k] = value()
            ws()
            require(i < s.length) { "end of text" }
            if (s[i] == ',') { i++; continue }
            require(s[i] == '}') { "'}' expected" }; i++
            return m
        }
    }

    private fun arr(): List<Any?> {
        i++
        val l = ArrayList<Any?>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            l += value()
            ws()
            require(i < s.length) { "end of text" }
            if (s[i] == ',') { i++; continue }
            require(s[i] == ']') { "']' expected" }; i++
            return l
        }
    }
}
