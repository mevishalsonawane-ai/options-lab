package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.orb.MiniJson
import com.optionslab.engine.signals.CallParser.Draft
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import java.time.LocalDate

/**
 * The Signal tester's reader, stage (b) (15 Oct 2026, Boss: "we have to read the message and understand it"): when the rules
 * ([CallParser]) are unsure or miss a field, the app's own on-device language model (Jarvis's Qwen 2.5, run by the app off the
 * main thread with a timeout - never on the order or tick path) reads the message and answers in ONE fixed JSON shape:
 *
 *   {"action":"new|followup|exit|not_a_call","underlying":"SENSEX","strike":73100,"right":"CE","expiry":"2026-10-16",
 *    "trigger":280,"stop":null,"targets":[310,345,400],"followup":"target|stop|book|exit|trail|hold","target_no":1,
 *    "confidence":0.9}
 *
 * The prompt ([prompt]) carries a few fixed examples and up to [MAX_EXAMPLES] of the channel's own messages Boss confirmed in
 * "Needs review", so the model learns each channel's style. Its reply is never trusted as it stands ([reply], [merge]):
 *  - every number it gives must be written in the message ([CallParser.numbers]); one that is not is dropped, never used;
 *  - the index or stock must be named in the message (an index guessed from a strike's size is not named: dropped);
 *  - a field the rules read and the model reads differently stays as the rules read it, and the call goes to review;
 *  - a field missing in both stays missing (a stop not in the message is "none given").
 * A reply that is not that JSON counts as no reply. Pure: the app passes the model's text in.
 */
object CallModel {
    const val MAX_EXAMPLES = 4
    const val MAX_TOKENS = 160
    const val TIMEOUT_MS = 20_000L

    /** One example message and the JSON it should give (Boss's confirmed reading, or a fixed one). */
    data class Example(val text: String, val json: String)

    /** The fixed examples every prompt starts with: a call, a follow-up, an advert. */
    val FIXED: List<Example> = listOf(
        Example("#banknifty 52000 PE\nBuy above 310\nSL 280\nTgt 340/370/420+",
            """{"action":"new","underlying":"BANKNIFTY","strike":52000,"right":"PE","expiry":null,"trigger":310,"stop":280,"targets":[340,370,420],"followup":null,"target_no":null,"confidence":0.95}"""),
        Example("T1 done ✅ book half, trail sl to cost",
            """{"action":"followup","underlying":null,"strike":null,"right":null,"expiry":null,"trigger":null,"stop":null,"targets":[],"followup":"target","target_no":1,"confidence":0.9}"""),
        Example("Join our VIP channel for 95% accurate calls!!",
            """{"action":"not_a_call","underlying":null,"strike":null,"right":null,"expiry":null,"trigger":null,"stop":null,"targets":[],"followup":null,"target_no":null,"confidence":0.95}"""),
    )

    private const val SYSTEM = "You read one Telegram message from an Indian stock-options tips channel and reply with ONE JSON object only, no other words. " +
        "Keys: action (new = a new option buy call, followup = news about an earlier call such as a target or stop hit, exit = exit or book now, " +
        "not_a_call = anything else), underlying (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX or a stock symbol, only if the message names it), " +
        "strike, right (CE or PE), expiry (YYYY-MM-DD, only if written), trigger (the entry price), stop (the stop-loss price), targets (a list), " +
        "followup (target, stop, book, exit, trail or hold), target_no, confidence (0 to 1). Use ONLY numbers written in the message. " +
        "If something is not in the message, use null (targets: []). Never guess a stop-loss."

    /** The channel's open calls given to the model with a message, at most. */
    const val MAX_OPEN = 5

    /**
     * The Qwen chat prompt for [text]: the fixed examples, then the channel's own ([examples], the last [MAX_EXAMPLES]); [open]:
     * the channel's open calls (their [CallParser.Draft.line], newest last, the last [MAX_OPEN]) put before the message, so a
     * status update ("T2 done", a re-post with ticks) is read as news about one of them, not as a new call (15 Oct, Boss).
     */
    fun prompt(text: String, examples: List<Example> = emptyList(), open: List<String> = emptyList()): String {
        val sb = StringBuilder("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        for (e in FIXED + examples.takeLast(MAX_EXAMPLES)) {
            sb.append("<|im_start|>user\n").append(e.text.take(600)).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n").append(e.json).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n")
        if (open.isNotEmpty()) sb.append("Open calls of this channel (for context; a message about one of them is a followup, not new):\n")
            .append(open.takeLast(MAX_OPEN).joinToString("\n") { "- " + it.take(160) }).append("\nMessage:\n")
        sb.append(text.take(600)).append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    /** The JSON [example] stores for a confirmed reading (what the model should have said). */
    fun exampleJson(r: Reading): String {
        fun n(x: Double?) = x?.let { CallParser.num(it) } ?: "null"
        fun s(x: String?) = x?.let { "\"$it\"" } ?: "null"
        return when (r) {
            is Reading.NewCall -> r.draft.let { d ->
                """{"action":"new","underlying":${s(d.underlying)},"strike":${n(d.strike)},"right":${s(d.right?.name)},"expiry":${s(d.expiry?.toString())},""" +
                    """"trigger":${n(d.trigger)},"stop":${n(d.stopLevel)},"targets":[${d.targets.joinToString(",") { CallParser.num(it) }}],"followup":null,"target_no":null,"confidence":1}"""
            }
            is Reading.FollowUp -> """{"action":"${if (r.kind == Follow.EXIT || r.kind == Follow.BOOK) "exit" else "followup"}","underlying":${s(r.ref?.underlying)},""" +
                """"strike":${n(r.ref?.strike)},"right":${s(r.ref?.right?.name)},"expiry":null,"trigger":null,"stop":null,"targets":[],"followup":"${r.kind.key}",""" +
                """"target_no":${r.targetNo ?: "null"},"confidence":1}"""
            is Reading.NotACall -> """{"action":"not_a_call","underlying":null,"strike":null,"right":null,"expiry":null,"trigger":null,"stop":null,"targets":[],"followup":null,"target_no":null,"confidence":1}"""
        }
    }

    /** The model's reading, after the checks: [dropped] says what it gave that the message does not hold. */
    data class Reply(val action: String, val draft: Draft, val follow: Follow?, val targetNo: Int?, val confidence: Double, val dropped: List<String>)

    private fun d(x: Any?): Double? = (x as? Double) ?: (x as? String)?.trim()?.removePrefix("₹")?.toDoubleOrNull()

    /**
     * The model's [raw] reply to [text] (sent on [on]) read and checked, or null when it is not the JSON asked for. Numbers not
     * written in [text] are dropped; so is an index or stock the message does not name.
     */
    fun reply(raw: String?, text: String, on: LocalDate): Reply? {
        val body = raw?.let { r -> val a = r.indexOf('{'); val b = r.lastIndexOf('}'); if (a >= 0 && b > a) r.substring(a, b + 1) else null } ?: return null
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { MiniJson.parse(body) as? Map<String, Any?> }.getOrNull() ?: return null
        val action = (o["action"] as? String)?.lowercase()?.trim() ?: return null
        if (action !in setOf("new", "followup", "exit", "not_a_call")) return null
        val nums = CallParser.numbers(text)
        val dropped = ArrayList<String>()
        fun keep(name: String, x: Double?): Double? = when {
            x == null -> null
            nums.any { kotlin.math.abs(it - x) < 1e-9 } -> x
            else -> { dropped += "$name ${CallParser.num(x)} (not in the message)"; null }
        }
        val named = CallParser.indexIn(text)
        val words = CallParser.normalize(text).split(' ').toSet()
        val u = (o["underlying"] as? String)?.trim()?.uppercase()?.ifEmpty { null }?.let { raw0 ->
            val alias = CallParser.ALIASES[raw0.lowercase()] ?: raw0.replace(" ", "")
            when {
                alias in CallParser.INDICES -> if (alias == named) alias else { dropped += "underlying $alias (not named in the message)"; null }
                alias.lowercase() in words -> alias
                else -> { dropped += "underlying $alias (not named in the message)"; null }
            }
        }
        val right = when ((o["right"] as? String)?.trim()?.uppercase()) { "CE", "CALL" -> Right.CE; "PE", "PUT" -> Right.PE; else -> null }
        val exp = (o["expiry"] as? String)?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        val targets = ((o["targets"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { keep("target", d(it)) }
        val draft = Draft(u, keep("strike", d(o["strike"])), right, exp?.takeIf { !it.isBefore(on.minusDays(1)) }, CallParser.Trig.ABOVE,
            keep("entry", d(o["trigger"])), null, keep("stop", d(o["stop"])), null, targets)
        val conf = (d(o["confidence"]) ?: 0.5).coerceIn(0.0, 1.0)
        val tn = d(o["target_no"])?.toInt()
        return Reply(action, draft, Follow.of((o["followup"] as? String)?.lowercase()?.trim()), tn, conf, dropped)
    }

    /** What the two stages agreed on: the reading, its confidence, the reasons it is unsure, and who read it. */
    data class Merged(val reading: Reading, val confidence: Double, val unsure: List<String>, val by: String) {
        val sure: Boolean get() = confidence >= CallParser.SURE && unsure.isEmpty()
    }

    /**
     * Stage (a)'s [rules] reading of one call-sized message merged with the model's [m] (null: no model, or no usable reply).
     * The rules' fields stand; the model fills only what they missed; a disagreement is listed and sends the call to review.
     */
    fun merge(rules: Reading, m: Reply?, modelMissing: Boolean = false): Merged {
        val rConf = when (rules) { is Reading.NewCall -> rules.confidence; is Reading.FollowUp -> 0.9; is Reading.NotACall -> if (rules.maybe) 0.5 else 0.9 }
        val rUnsure = (rules as? Reading.NewCall)?.unsure.orEmpty() + if (rules is Reading.NotACall && rules.maybe) listOf(rules.reason) else emptyList()
        if (m == null) {
            val why = if (modelMissing) "the language model is not on the phone" else "the language model gave no usable answer"
            return Merged(rules, rConf, if (rUnsure.isEmpty()) emptyList() else rUnsure + why, "rules")
        }
        val dropped = m.dropped.map { "the model said $it" }
        return when (m.action) {
            "not_a_call" -> when (rules) {
                is Reading.NewCall -> Merged(rules, minOf(rConf, 0.5), rUnsure + "the model read it as not a call" + dropped, "rules+model")
                is Reading.FollowUp -> Merged(rules, minOf(rConf, 0.6), listOf("the model read it as not a call") + dropped, "rules+model")
                is Reading.NotACall -> Merged(Reading.NotACall(rules.reason), m.confidence, if (m.confidence >= CallParser.SURE) emptyList() else listOf("the model is unsure"), "rules+model")
            }
            "followup", "exit" -> {
                val kind = m.follow ?: if (m.action == "exit") Follow.EXIT else null
                when {
                    rules is Reading.FollowUp -> Merged(rules, maxOf(rConf, m.confidence), dropped, "rules+model")
                    rules is Reading.NewCall -> Merged(rules, minOf(rConf, 0.5), rUnsure + "the model read it as a follow-up" + dropped, "rules+model")
                    kind == null -> Merged(rules, 0.4, listOf("the model read it as a follow-up but not which kind") + dropped, "rules+model")
                    else -> {
                        val ref = m.draft.takeIf { it.strike != null || it.underlying != null }?.let { Draft(it.underlying, it.strike, it.right) }
                        Merged(Reading.FollowUp(kind, m.targetNo, null, false, ref), m.confidence,
                            (if (m.confidence >= CallParser.SURE) emptyList() else listOf("the model is unsure")) + dropped, "model")
                    }
                }
            }
            else -> {
                val r = rules as? Reading.NewCall
                if (r == null) {
                    // The rules found no option; the model did. Never counted without Boss: the rules could not confirm a field.
                    val why = missing(m.draft) + "only the model read it as a call" + dropped
                    return Merged(Reading.NewCall(m.draft, minOf(m.confidence, 0.7), why), minOf(m.confidence, 0.7), why, "model")
                }
                val conflicts = ArrayList<String>()
                fun <T> pick(name: String, a: T?, b: T?): T? {
                    if (a != null && b != null && a != b) conflicts += "the rules and the model differ on the $name"
                    return a ?: b
                }
                val a = r.draft; val b = m.draft
                val tg = if (a.targets.isNotEmpty()) { if (b.targets.isNotEmpty() && b.targets != a.targets) conflicts += "the rules and the model differ on the targets"; a.targets } else b.targets
                val d = a.copy(underlying = pick("index or stock", a.underlying, b.underlying), strike = pick("strike", a.strike, b.strike),
                    right = pick("CE / PE", a.right, b.right), expiry = pick("expiry", a.expiry, b.expiry), trigger = pick("entry", a.trigger, b.trigger),
                    stop = if (a.stop == null && a.stopPct != null) null else pick("stop", a.stop, b.stop), targets = tg)
                // The rules' reasons that the model's agreeing fields now answer are gone; the rest stay.
                val full = b.complete && b.trigger != null && conflicts.isEmpty()
                val left = r.unsure.filter { u -> when {
                    u.startsWith("no index") -> d.underlying == null
                    u.startsWith("no entry") -> d.trigger == null
                    u.startsWith("the entry was read") -> !(b.trigger != null && b.trigger == a.trigger)
                    u.startsWith("numbers not understood") -> !full
                    else -> true
                } }
                val why = (left + conflicts + missing(d) + dropped).distinct()
                val conf = minOf(m.confidence, if (why.isEmpty()) 1.0 else r.confidence)
                Merged(Reading.NewCall(d, conf, why), conf, why, "rules+model")
            }
        }
    }

    /** The fields a new call must have to be tested (the rest is [CallCheck]'s). */
    fun missing(d: Draft): List<String> = listOfNotNull(
        if (d.underlying == null) "no index or stock named" else null,
        if (d.strike == null) "no strike" else null,
        if (d.right == null) "no CE / PE" else null,
    )
}
