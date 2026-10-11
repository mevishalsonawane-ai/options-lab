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
    /**
     * A research committee (R12, research/HUNT_R12_TRADE_MANAGER.md): only these specialists count - their EXIT votes and
     * their locks - each running the research's own rule with its parameters ([R12Rules]); every other specialist still
     * looks and is recorded (SHADOW) but decides nothing, its hard veto included. Empty: the original committee, as before.
     */
    val members: Set<String> = emptySet(),
    /** A research committee's extension votes (all must agree, and the premium 90% of the way to the target). */
    val extendMembers: Set<String> = emptySet(),
    /** False: this committee never extends a target (the research's `"extend": null`). */
    val extend: Boolean = true,
    /** The default mode on each account when nothing is stored (null: the app's own default). LIVE is never ACT by default. */
    val paperMode: TradeManager.Mode? = null,
    val liveMode: TradeManager.Mode? = null,
    /** The research's verdict in one plain sentence (the record and Jarvis say it). */
    val verdict: String? = null,
) {
    /** A research committee ([members]): its members alone decide. */
    val isResearch: Boolean get() = members.isNotEmpty() || extendMembers.isNotEmpty()

    fun spec(id: String): SpecConfig = specialists[id] ?: ManagerTeam.DEFAULT_SPECS[id] ?: SpecConfig(0.0, enabled = false)

    /** The specialist's weight when it counts (enabled, weight above 0, a member of a research committee); 0 otherwise. */
    fun weight(id: String): Double {
        if (isResearch && id !in members) return 0.0
        return spec(id).let { if (it.enabled && it.weight > 0) it.weight else 0.0 }
    }

    /** A research parameter of [id]'s ([R12Rules]): set by the block, never a default; null: not set. */
    fun param(id: String, key: String): Double? = specialists[id]?.thresholds?.get(key)?.takeIf { it.isFinite() }

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
        members = members.filter { it in ManagerTeam.IDS }.toSet(),
        extendMembers = extendMembers.filter { it in ManagerTeam.IDS }.toSet(),
        // LIVE to ACT always takes Boss's PIN: never a default.
        liveMode = liveMode?.let { if (it == TradeManager.Mode.ACT) TradeManager.Mode.SHADOW else it },
    )

    /** The default mode on [account] this block sets (null: none). */
    fun mode(account: TradeManager.Account): TradeManager.Mode? = if (account == TradeManager.Account.PAPER) paperMode else liveMode

    companion object {
        const val MIN_QUORUM = 0.25
        const val MAX_QUORUM = 10.0
        const val MAX_WEIGHT = 5.0
        /** The extension's agreement lasts at least as long as the original rule's (30 s). */
        const val MIN_EXTEND_HOLD_SEC = 30

        private fun Double.finiteOr(d: Double) = if (isFinite()) this else d

        /** The block's words that are not a number or a specialist ([apply]). */
        private val META = setOf("mode", "paper_mode", "papermode", "live_mode", "livemode", "members", "member", "extend_members",
            "extend_member", "extend", "verdict")

        private fun mode(v: String): TradeManager.Mode? = runCatching { TradeManager.Mode.valueOf(v.trim().uppercase(Locale.ENGLISH)) }.getOrNull()

        private fun ids(v: String): Set<String> = v.split(',', ' ', ';').map { it.trim().lowercase(Locale.ENGLISH) }.filter { it in ManagerTeam.IDS }.toSet()

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
            when (k.lowercase(Locale.ENGLISH)) {
                // The research's one mode (R12): ACT acts on PAPER only (LIVE stays SHADOW: LIVE to ACT takes the PIN).
                "mode" -> {
                    val m = mode(v) ?: return null
                    return c.copy(paperMode = m, liveMode = if (m == TradeManager.Mode.ACT) TradeManager.Mode.SHADOW else m)
                }
                "paper_mode", "papermode" -> return mode(v)?.let { c.copy(paperMode = it) }
                "live_mode", "livemode" -> return mode(v)?.let { c.copy(liveMode = it) }
                "members" -> return c.copy(members = ids(v))
                "member" -> return ids(v).takeIf { it.isNotEmpty() }?.let { c.copy(members = c.members + it) }
                "extend_members" -> return c.copy(extendMembers = ids(v))
                "extend_member" -> return ids(v).takeIf { it.isNotEmpty() }?.let { c.copy(extendMembers = c.extendMembers + it) }
                "extend" -> return if (v.trim() == "null") c.copy(extend = false) else bool(v)?.let { c.copy(extend = it) }
                "verdict" -> return v.trim().takeIf { it.isNotEmpty() }?.let { c.copy(verdict = it.replace('\n', ' ').take(300)) }
            }
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
                if (first in ManagerTeam.IDS || TOP.containsKey(key.lowercase(Locale.ENGLISH)) || key.lowercase(Locale.ENGLISH) in META)
                    out += Triple(section, key, value)
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
                        // The research's rules (R12): each one onto the specialist that covers its family, as its parameters.
                        (key == "exit" || key == "lock") && v is List<*> -> for (x in v) if (x is Map<*, *>) out += research(sid, x)
                        key == "extend" && v == null -> out += Triple(sid, "extend", "false")
                        key == "extend" && v is Map<*, *> -> out += researchExtend(sid, (v["votes"] as? List<*>).orEmpty())
                        key == "extend" && v is List<*> -> out += researchExtend(sid, (v.firstOrNull() as? List<*>).orEmpty())
                        // Per index (R12's VIX divergence: "NIFTY", "BANKNIFTY"): its own committee, "vixdiv/nifty".
                        v is Map<*, *> && key.any { it.isLetter() } && key == key.uppercase(Locale.ENGLISH) ->
                            one("$sid/${key.lowercase(Locale.ENGLISH)}", v)
                        v !is Map<*, *> && v !is List<*> -> out += Triple(sid, key, v.toString())
                    }
                }
            }
            fun strategyObject(o: Map<*, *>): Boolean = o["strategy"] is String || o["strategyId"] is String
            when (root) {
                is List<*> -> for (o in root) if (o is Map<*, *> && strategyObject(o)) one((o["strategy"] ?: o["strategyId"]).toString(), o)
                is Map<*, *> -> when {
                    strategyObject(root) -> one((root["strategy"] ?: root["strategyId"]).toString(), root)
                    // The research's settings block (R12 Part D): {"version": ..., "strategies": {"orb_fresh": {...}, ...}}.
                    root["strategies"] is Map<*, *> -> for ((sid, o) in root["strategies"] as Map<*, *>) if (o is Map<*, *>) one(sid.toString(), o)
                    root["strategies"] is List<*> -> for (o in root["strategies"] as List<*>)
                        if (o is Map<*, *> && strategyObject(o)) one((o["strategy"] ?: o["strategyId"]).toString(), o)
                    else -> for ((sid, o) in root) if (o is Map<*, *>) one(sid.toString(), o)
                }
            }
            return out
        }

        private fun numOf(m: Map<*, *>, vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
            when (val x = m[k]) { is Number -> x.toDouble(); is String -> x.toDoubleOrNull(); else -> null }
        }

        /**
         * One research rule ({"spec": "PA", "T_min": 20, "m": 0.03, "weight": 1}) as the parameters of the specialist that
         * covers its family: PA, PB, VC and BE on Price action; VW on VWAP; MP on Profile (the prior day's value area); OI on
         * OI; DP (the delta proxy) on Delta. The specialist becomes a member of the committee. An unknown or incomplete rule
         * adds nothing.
         */
        private fun research(sid: String, m: Map<*, *>): List<Triple<String, String, String>> {
            val spec = m["spec"]?.toString()?.trim()?.uppercase(Locale.ENGLISH) ?: return emptyList()
            val (id, ths) = when (spec) {
                "PA" -> "price" to listOf("paT" to numOf(m, "T_min", "T"), "paM" to numOf(m, "m"))
                "PB" -> "price" to listOf("pbA" to numOf(m, "a"))
                "VC" -> "price" to listOf("vcK" to numOf(m, "k_atr", "k"), "vcArm" to numOf(m, "arm_atr", "a"))
                "BE" -> "price" to listOf("beB" to numOf(m, "b"))
                "OI" -> "oi" to listOf("oiZ0" to numOf(m, "z0"))
                "VW" -> "vwap" to listOf("vwZ" to numOf(m, "z_sd", "z"), "vwP" to numOf(m, "persist_min", "p"))
                // Only the prior day's value area was a frozen rule (R12); the developing one is not this parameter.
                "MP" -> if ((m["value_area"] ?: m["which"])?.toString()?.lowercase(Locale.ENGLISH)?.startsWith("dev") == true) return emptyList()
                    else "profile" to listOf("mpP" to numOf(m, "persist_min", "p"))
                "DP" -> "delta" to listOf("dpZ0" to numOf(m, "z0"))
                else -> return emptyList()
            }
            if (ths.any { it.second == null }) return emptyList()
            return listOf(Triple(sid, "member", id), Triple(sid, "$id.weight", (numOf(m, "weight") ?: 1.0).toString())) +
                ths.map { Triple(sid, "$id.${it.first}", it.second.toString()) }
        }

        private val NUMBER = Regex("[-+]?[0-9]*\\.?[0-9]+")

        /** The research's extension votes ("VWAP>=0.5SD" or "xVW", "flow>=+0.5SD" or "xDP") as the committee's extension members. */
        private fun researchExtend(sid: String, votes: List<*>): List<Triple<String, String, String>> {
            val out = arrayListOf(Triple(sid, "extend", "true"))
            for (x in votes) {
                val w = x.toString().trim()
                val low = w.lowercase(Locale.ENGLISH)
                val n = NUMBER.find(w.substringAfter(">=", ""))?.value?.toDoubleOrNull() ?: 0.5
                when {
                    low.startsWith("vwap") || low == "xvw" -> { out += Triple(sid, "extend_member", "vwap"); out += Triple(sid, "vwap.xvw", n.toString()) }
                    low.startsWith("flow") || low == "xdp" -> { out += Triple(sid, "extend_member", "delta"); out += Triple(sid, "delta.xdp", n.toString()) }
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
            c.paperMode?.let { lines += "paper_mode=${it.name}" }
            c.liveMode?.let { lines += "live_mode=${it.name}" }
            if (c.members.isNotEmpty()) lines += "members=${c.members.sorted().joinToString(",")}"
            if (c.extendMembers.isNotEmpty()) lines += "extend_members=${c.extendMembers.sorted().joinToString(",")}"
            if (!c.extend) lines += "extend=false"
            c.verdict?.let { lines += "verdict=${it.replace('#', ' ').replace('\n', ' ')}" }
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
