package com.optionslab.engine.signals

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Signal tester's two-stage understanding of a message put together (15 Oct 2026): the rules first ([CallParser]); when
 * they are unsure, miss a field, or half-recognise something ([needsModel]), the language model's reply ([CallModel]) merged in;
 * then the follow-ups linked to the channel's open calls ([link]) and a follow-up's claim checked against the prices
 * ([claimTrue]). The app runs the model itself (off the main thread, with a timeout) and passes its text here. Pure.
 */
object CallReader {
    /** Stage (b) is asked: a single reading that is an unsure call, or a not-a-call that looks partly like one. */
    fun needsModel(rules: List<Reading>): Boolean {
        val r = rules.singleOrNull() ?: return false
        return (r is Reading.NewCall && !r.sure) || (r is Reading.NotACall && r.maybe)
    }

    /**
     * [text] (sent on [on]) understood: the rules' readings, each merged with the model's [modelReply] when [needsModel] (null:
     * none; [modelMissing]: the model is not on the phone). [known]: the phone's stock symbols.
     */
    fun understand(text: String, on: LocalDate, known: Set<String> = emptySet(), modelReply: String? = null, modelMissing: Boolean = false): List<CallModel.Merged> {
        val rules = CallParser.read(text, on, known)
        if (!needsModel(rules)) return rules.map { r ->
            when (r) {
                is Reading.NewCall -> CallModel.Merged(r, r.confidence, r.unsure, "rules")
                is Reading.FollowUp -> CallModel.Merged(r, 0.9, emptyList(), "rules")
                is Reading.NotACall -> CallModel.Merged(r, 0.9, emptyList(), "rules")
            }
        }
        return listOf(CallModel.merge(rules.single(), CallModel.reply(modelReply, text, on), modelMissing && modelReply == null))
    }

    /**
     * One of the channel's calls still open (not finished), for linking a status message: [msgId] its Telegram message id (a
     * reply names it), [text] its first text (for the text similarity and its own ticks).
     */
    data class Open(val id: String, val draft: CallParser.Draft, val at: LocalDateTime, val msgId: Long? = null, val text: String? = null)

    /** How far back "the channel's latest open call" reaches (a "T2 done" the next morning still finds yesterday's call). */
    const val LINK_DAYS = 3L
    private fun words(t: String): Set<String> = CallParser.normalize(t).split(' ').filter { it.isNotEmpty() && it != "done" }.toSet()

    /** The texts' likeness, 0-1 (the share of words they have in common, ticks and emojis left out). */
    fun similarity(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.0
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    private fun sameContract(a: CallParser.Draft, b: CallParser.Draft): Boolean = a.strike != null && a.strike == b.strike &&
        (a.right == null || a.right == b.right) && (a.underlying == null || b.underlying == null || a.underlying == b.underlying)

    /**
     * The open call a status message [f] (sent at [at]) is about (15 Oct, Boss): the call it replies to ([replyTo], a Telegram
     * message id) first; else the call it names (the same strike and CE / PE, and the index when it says one) of the same day,
     * the one whose text is most like [text] (then the latest); else, when it names none, the channel's latest open call of the
     * last [LINK_DAYS] days. Null: an unknown call (the message is ignored, never a trade) - also a reply to a message the tester
     * holds ([knownIds]) that is not an open call.
     */
    fun link(f: Reading.FollowUp, at: LocalDateTime, open: List<Open>, replyTo: Long? = null, text: String? = null, knownIds: Set<Long> = emptySet()): String? {
        val earlier = open.filter { !it.at.isAfter(at) }.sortedBy { it.at }
        if (replyTo != null) {
            earlier.lastOrNull { it.msgId == replyTo }?.let { return it.id }
            // A reply to another message the tester holds (a finished call, chatter): about that one, not the latest call.
            if (replyTo in knownIds) return null
        }
        val ref = f.ref
        if (ref?.strike != null) {
            return earlier.filter { it.at.toLocalDate() == at.toLocalDate() && sameContract(ref, it.draft) }
                .maxWithOrNull(compareBy<Open> { similarity(text, it.text) }.thenBy { it.at })?.id
        }
        return earlier.lastOrNull { !it.at.toLocalDate().isBefore(at.toLocalDate().minusDays(LINK_DAYS)) }?.id
    }

    /** A status message read: the call it is about and what it says. Never a new call, never a trade. */
    data class Status(val callId: String, val follow: Reading.FollowUp)

    /**
     * [readings] of [text] that read as ONE new call, checked for being a re-post of an open call (15 Oct, Boss: channels re-post
     * the same call with ✅ / ✔️ on the levels reached): the call replied to, else the same contract the same day with the same
     * levels (each entry, stop and target it writes is the call's; a line may be left out) - a call on the same strike with other
     * levels is a new call (a re-entry). What
     * it says: the stop ticked (or "sl hit") - the stop; a target ticked - the highest one ticked; the entry ticked - triggered;
     * else its words' follow-up; else "re-posted". Null: not a re-post.
     */
    fun repost(readings: List<Reading>, text: String, at: LocalDateTime, open: List<Open>, replyTo: Long? = null): Status? {
        val call = readings.singleOrNull() as? Reading.NewCall ?: return null
        val d = call.draft
        val earlier = open.filter { !it.at.isAfter(at) }
        val replied = replyTo?.let { r -> earlier.lastOrNull { it.msgId == r } }?.takeIf { d.strike == null || sameContract(d, it.draft) }
        val o = replied ?: earlier.filter { it.at.toLocalDate() == at.toLocalDate() && sameContract(d, it.draft) }
            .maxWithOrNull(compareBy<Open> { similarity(text, it.text) }.thenBy { it.at }) ?: return null
        val ticks = CallParser.ticked(text) - CallParser.ticked(o.text.orEmpty())
        val newWords = words(text) - words(o.text.orEmpty())
        val said = if (newWords.isEmpty()) null else CallParser.normalize(text).let { n -> CallParser.follow(n, CallParser.tokens(n)) }
            ?.takeIf { it.kind != Follow.HOLD || "hold" in newWords }
        val od = o.draft
        val consistent = (d.trigger == null || d.trigger == od.trigger) && (d.stopLevel == null || d.stopLevel == od.stopLevel) && d.targets.all { it in od.targets }
        if (!consistent && replied == null) return null
        val ref = CallParser.Draft(od.underlying, od.strike, od.right)
        val stop = od.stopLevel
        val hit = od.targets.indices.filter { od.targets[it] in ticks }
        val f = when {
            (stop != null && stop in ticks) || said?.kind == Follow.STOP -> Reading.FollowUp(Follow.STOP, ref = ref)
            hit.isNotEmpty() -> Reading.FollowUp(Follow.TARGET, targetNo = hit.max() + 1, level = od.targets[hit.max()], ref = ref)
            said != null -> said.copy(ref = ref)
            od.trigger != null && od.trigger in ticks -> Reading.FollowUp(Follow.TRIGGERED, level = od.trigger, ref = ref)
            else -> Reading.FollowUp(Follow.REPOST, ref = ref)
        }
        return Status(o.id, f)
    }

    /**
     * A message read as a new call that ticks ([CallParser.ticked]) a level its first post did not, on a contract the channel
     * called in the last [LINK_DAYS] days ([calls], finished ones too) - yet is not that call's re-post ([repost]: another day,
     * or a trailed stop "SL 290 ✅"): most likely a status, so never a call by itself - it waits for Boss's review (15 Oct, Boss:
     * status updates never create a trade).
     */
    fun suspectRepost(readings: List<Reading>, text: String, at: LocalDateTime, calls: List<Open>): Boolean {
        val ticks = CallParser.ticked(text)
        if (ticks.isEmpty()) return false
        return readings.filterIsInstance<Reading.NewCall>().any { c ->
            calls.any { o -> !o.at.isAfter(at) && !o.at.toLocalDate().isBefore(at.toLocalDate().minusDays(LINK_DAYS)) && sameContract(c.draft, o.draft) &&
                (ticks - CallParser.ticked(o.text.orEmpty())).isNotEmpty() }
        }
    }

    /**
     * The level a target claim names for [d]: the numbered target, the last for "all targets", the level it names, else (a bare
     * "done") the next target after the [prior] target claims already made for that call.
     */
    fun claimedLevel(f: Reading.FollowUp, d: CallParser.Draft, prior: Int = 0): Double? = when {
        f.kind != Follow.TARGET -> null
        f.targetNo == 99 -> d.targets.lastOrNull()
        f.targetNo != null -> d.targets.getOrNull(f.targetNo - 1)
        f.level != null -> f.level
        else -> d.targets.getOrNull(prior)
    }

    /** A channel's claim checked against the prices (the scorecard's "claims accuracy"). */
    enum class Verdict(val key: String, val words: String) {
        CONFIRMED("confirmed", "confirmed by the prices"), NOT_REACHED("not_reached", "NOT reached by the prices"),
        AFTER_STOP("after_stop", "reached only after our stop");
        companion object { fun of(k: String?): Verdict? = entries.firstOrNull { it.key == k } }
    }

    /**
     * A target or stop claim [f] about [d] (bought at [fillPrice] at [from]) checked on the option's minutes [bars] up to the
     * claim's time [to]: a target - reached ([Verdict.CONFIRMED]), never reached ([Verdict.NOT_REACHED]), or reached only after
     * our stop (the call's stop, else [CallEval.OUR_STOP] under the fill; in the same minute the stop counts first, as
     * [CallEval]) ([Verdict.AFTER_STOP]); a stop - its low reached the stop or not. [prior]: earlier target claims of that call.
     * Null: not a target or stop claim, no level, or no prices in between.
     */
    fun verdict(f: Reading.FollowUp, d: CallParser.Draft, bars: List<Bar>, from: LocalDateTime, fillPrice: Double, to: LocalDateTime, prior: Int = 0): Verdict? {
        val inside = bars.filter { !it.start.isBefore(from.withSecond(0)) && it.start.isBefore(to) }.sortedBy { it.start }
        if (inside.isEmpty()) return null
        val stop = d.stopLevel?.takeIf { it < fillPrice } ?: CallParser.round2(fillPrice * (1 - CallEval.OUR_STOP))
        val stopAt = inside.indexOfFirst { it.low <= stop }
        return when (f.kind) {
            Follow.TARGET -> {
                val lvl = claimedLevel(f, d, prior) ?: return null
                val hitAt = inside.indexOfFirst { it.high >= lvl }
                when {
                    hitAt < 0 -> Verdict.NOT_REACHED
                    stopAt in 0..hitAt -> Verdict.AFTER_STOP
                    else -> Verdict.CONFIRMED
                }
            }
            Follow.STOP -> if (stopAt >= 0) Verdict.CONFIRMED else Verdict.NOT_REACHED
            else -> null
        }
    }
}
