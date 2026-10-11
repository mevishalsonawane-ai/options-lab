package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home's Automation card (11 Oct, Boss: trading first, less clutter): one line each for the trade manager, Autopilot, the
 * strategy lab, the order flow and the Brain, and a warning while learning is frozen. Each line is worked out from the
 * books already in memory (never a file, never the settings vault) and only when one of them changes - never per price
 * tick. A tap on a line opens that section in full. Pure.
 */
object AutomationSummary {
    /** The sections the card opens, in the card's order, with their titles. */
    enum class Section(val key: String, val title: String) {
        MANAGER("manager", "Trade manager"),
        AUTOPILOT("autopilot", "Autopilot"),
        LAB("lab", "Strategy lab"),
        ORDER_FLOW("orderflow", "Order flow"),
        BRAIN("brain", "Brain"),
    }

    /** The card's lines (null: that section is not in this build); [frozen] the warning while learning is frozen. */
    data class Lines(
        val manager: String? = null,
        val autopilot: String? = null,
        val lab: String? = null,
        val orderFlow: String? = null,
        val brain: String? = null,
        val frozen: String? = null,
    ) {
        /** The tappable lines in the card's order. */
        fun rows(): List<Pair<Section, String>> = listOfNotNull(
            manager?.let { Section.MANAGER to it }, autopilot?.let { Section.AUTOPILOT to it }, lab?.let { Section.LAB to it },
            orderFlow?.let { Section.ORDER_FLOW to it }, brain?.let { Section.BRAIN to it },
        )
    }

    private fun n(x: Int, one: String, many: String = one + "s") = "$x ${if (x == 1) one else many}"

    /** Names in a short run: "ORB Fresh", "Solo and Pine", "Solo, Pine +2". */
    internal fun names(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        2 -> "${xs[0]} and ${xs[1]}"
        else -> "${xs[0]}, ${xs[1]} +${xs.size - 2}"
    }

    /**
     * "Trade manager: 2 managed today · ORB Fresh acting on paper": the trades it followed that started [today] (in [zone]) and
     * the strategies it acts on, paper and live apart; "records only" when it acts on none.
     */
    fun managerLine(records: List<TradeManager.Record>, policy: TradeManager.Policy, today: LocalDate, zone: ZoneId): String {
        val todays = records.count { Instant.ofEpochMilli(it.trade.entryMs).atZone(zone).toLocalDate() == today }
        val open = records.count { !it.closed }
        val paper = TradeManager.FAMILIES.filter { policy.mode(it.key, TradeManager.Account.PAPER) == TradeManager.Mode.ACT }.map { it.name }
        val live = TradeManager.FAMILIES.filter { !it.paperOnly && policy.mode(it.key, TradeManager.Account.LIVE) == TradeManager.Mode.ACT }.map { it.name }
        val parts = ArrayList<String>()
        parts += "${n(todays, "trade")} managed today" + if (open > 0) " ($open open)" else ""
        if (paper.isNotEmpty()) parts += "${names(paper)} acting on paper"
        if (live.isNotEmpty()) parts += "${names(live)} acting live"
        if (paper.isEmpty() && live.isEmpty()) parts += "records only"
        return "Trade manager: " + parts.joinToString(" · ")
    }

    /** "Autopilot: 1 change today, 0 reverted" (and how many it asked for today, when any). */
    fun autopilotLine(book: AutoChange.Book, today: LocalDate): String {
        // Made today counts a change reverted or undone since (else "0 changes today, 1 reverted" on the same day).
        val made = book.entries.count {
            it.day == today && (it.applied || it.status == AutoChange.Status.REVERTED || it.status == AutoChange.Status.UNDONE)
        }
        val reverted = book.entries.count { it.status == AutoChange.Status.REVERTED && (it.closedOn ?: it.day) == today }
        val asked = book.entries.count { it.status == AutoChange.Status.OFFERED && it.day == today }
        return "Autopilot: ${n(made, "change")} today, $reverted reverted" + if (asked > 0) " · $asked asked" else ""
    }

    /** "Lab: 3 variants · 1 idea on paper" (and the ideas in forward test, and what waits for Boss). */
    fun labLine(variants: VariantLab.Book, forge: StrategyForge.Book): String {
        val alive = variants.alive().size
        val c = StrategyForge.counts(forge)
        val waiting = variants.changes.count { it.status == VariantLab.ChangeStatus.OFFERED } + forge.waiting.size
        val parts = ArrayList<String>()
        parts += n(alive, "variant")
        parts += "${n(c.paper, "idea")} on paper"
        if (c.forward > 0) parts += "${c.forward} in forward test"
        if (waiting > 0) parts += "$waiting waiting for you"
        return "Lab: " + parts.joinToString(" · ")
    }

    /** "Order flow: shadow only" or "Order flow: 1 strategy confirms entries". Its modes only: never the live reads (they move each tick). */
    fun orderFlowLine(modes: Map<String, FlowShadow.Setting>): String {
        val confirming = modes.count { it.value.mode == OrderFlow.Mode.CONFIRM }
        return "Order flow: " + if (confirming == 0) "shadow only, no entry skipped" else if (confirming == 1) "1 strategy confirms entries" else "$confirming strategies confirm entries"
    }

    /**
     * "Brain: busy day · 1 drifting · 1 paused": the market's regime (any index busy: a busy day), the strategies drifting from
     * their backtest, those whose new paper entries are paused and those parked to paper.
     */
    fun brainLine(ctx: MarketBrain.Context, reviews: List<DriftReview.Review>, paused: Set<String>, parked: Set<String>): String {
        val day = when {
            ctx.regimes.isEmpty() -> "regime not known yet"
            ctx.anyBusy() -> "busy day"
            ctx.allQuiet() -> "quiet day"
            else -> "normal day"
        }
        val drifting = reviews.count { r -> DriftReview.chip(r, if (r.live) r.key in parked else r.key in paused) == DriftReview.Verdict.DRIFTING.words }
        val parts = arrayListOf(day)
        if (drifting > 0) parts += "$drifting drifting"
        if (paused.isNotEmpty()) parts += "${n(paused.size, "strategy", "strategies")} paused on paper"
        if (parked.isNotEmpty()) parts += "${parked.size} parked to paper"
        return "Brain: " + parts.joinToString(" · ")
    }

    /** The warning while every automatic change is frozen; null when it is not. */
    fun frozenLine(state: FreezeRule.State): String? = if (!state.frozen) null else
        "Learning frozen" + (if (state.accounts.isNotEmpty()) " (" + state.accounts.sortedBy { it.ordinal }.joinToString(", ") { it.words } + ")" else "") +
            ": nothing is applied until you unfreeze it."
}
