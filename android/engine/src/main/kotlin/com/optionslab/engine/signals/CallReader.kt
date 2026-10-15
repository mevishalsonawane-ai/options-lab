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

    /** One of the channel's calls still open (not finished), for linking a follow-up. */
    data class Open(val id: String, val draft: CallParser.Draft, val at: LocalDateTime)

    /**
     * The open call a follow-up [f] (sent at [at]) is about: the one it names (strike and CE / PE, and the index when it says
     * one), else the channel's latest call of that day still open; null when none.
     */
    fun link(f: Reading.FollowUp, at: LocalDateTime, open: List<Open>): String? {
        val same = open.filter { it.at.toLocalDate() == at.toLocalDate() && !it.at.isAfter(at) }.sortedBy { it.at }
        val ref = f.ref
        if (ref?.strike != null) {
            val named = same.filter { it.draft.strike == ref.strike && (ref.right == null || it.draft.right == ref.right) &&
                (ref.underlying == null || it.draft.underlying == ref.underlying) }
            return named.lastOrNull()?.id
        }
        return same.lastOrNull()?.id
    }

    /** The level a target follow-up claims for [d]: the numbered target, the last for "all targets", else the level it names. */
    fun claimedLevel(f: Reading.FollowUp, d: CallParser.Draft): Double? = when {
        f.kind != Follow.TARGET -> null
        f.targetNo == 99 -> d.targets.lastOrNull()
        f.targetNo != null -> d.targets.getOrNull(f.targetNo - 1)
        else -> f.level
    }

    /**
     * Was a claim true by the prices: a target follow-up - the option's high reached [level] between [from] (the call's fill)
     * and [to] (the follow-up's time); a stop follow-up - its low reached the call's stop. Null: no level, or no prices.
     */
    fun claimTrue(f: Reading.FollowUp, d: CallParser.Draft, bars: List<Bar>, from: LocalDateTime, to: LocalDateTime): Boolean? {
        val inside = bars.filter { !it.start.isBefore(from.withSecond(0)) && it.start.isBefore(to) }
        if (inside.isEmpty()) return null
        return when (f.kind) {
            Follow.TARGET -> claimedLevel(f, d)?.let { lvl -> inside.any { it.high >= lvl } }
            Follow.STOP -> d.stopLevel?.let { s -> inside.any { it.low <= s } }
            else -> null
        }
    }
}
