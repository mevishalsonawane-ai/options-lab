package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * Today's changes across the app (S13, "Today's changes"): every automatic change any agent made today - Autopilot, the
 * specialists' own nightly tuning (logged in Autopilot's book), the strategy lab's paper promotions, the health tuner's
 * cadence changes and the other agents' change findings - with the time, the agent, what moved, why, and whether and how
 * it can be undone.
 *
 * "Undo everything today" ([undoPlan]) undoes each change through that change's own undo path, newest first. An undo that
 * would raise live risk is never done here: it is listed as "needs your PIN" ([UndoState.NEEDS_PIN]) and done only when
 * Boss confirms with his PIN. A change with no undo of its own (a finding from another agent, a live lab rule that moves
 * back only through its own PIN flow) is listed and left. Pure: the app reads the agents' books and does the undos.
 */
object ChangeTimeline {
    enum class Source(val agent: String) {
        AUTOPILOT("Autopilot"), SPECIALISTS("Specialists' tuning"), LAB("Strategy lab"), HEALTH("Health tuner"), FINDING("Agent"),
    }

    enum class UndoState(val words: String) {
        UNDOABLE("can be undone"),
        NEEDS_PIN("undo needs your PIN (it adds live risk)"),
        UNDONE("undone"),
        REVERTED("reverted by itself"),
        NO_UNDO("no undo here"),
        /** Asked of Boss, not applied (nothing to undo). */
        ASKED("asked, not applied"),
    }

    /**
     * One change: [id] is the source's own id ("ap:2026-10-12#3", "lab:C4", "health:brain.consensus", "find:12"); [key]
     * what the change touched (for the undo); [atMs] when it was made.
     */
    data class Item(
        val id: String, val source: Source, val atMs: Long, val agent: String, val what: String, val why: String,
        val undo: UndoState, val scope: String = "", val key: String = "",
        /** Boss's own change, only logged here (13 Oct's park): undone by its own tap, never by "undo everything today". */
        val own: Boolean = false,
    ) {
        val pending: Boolean get() = undo == UndoState.UNDOABLE || undo == UndoState.NEEDS_PIN
    }

    /** A strategy-lab change, as the app hands it over (decoupled from the lab's own book). */
    data class LabChange(val id: String, val label: String, val moved: String, val why: String, val auto: Boolean, val live: Boolean,
                         val status: String, val day: LocalDate, val atMs: Long)

    /** A tuned cadence of the health tuner (lengthened today) or a restore, as the app hands it over. */
    data class HealthChange(val key: String, val words: String, val atMs: Long, val restore: Boolean, val active: Boolean)

    /** A finding on the bus ([Findings.Finding]) that reports a change. */
    private val CHANGE_WORDS = Regex("(?i)(→|->|\\bmuted\\b|\\bparked\\b|\\bpaused\\b|\\bslowed\\b|\\bchanged\\b|\\bswitched\\b|\\bdemoted\\b|\\bresumed\\b)")

    /** Agents whose changes are read from their own books (their findings would be counted twice). */
    private val OWN_BOOKS = listOf("autopilot", "health tuner", "strategy lab", "trade manager")

    // ---- from the books ----------------------------------------------------------------------------------------------------

    /**
     * [book]'s automatic changes on [day] (asked ones too, as "asked"), Autopilot's and the specialists' tuning. [undoNeedsPin]:
     * the app's own check of what an undo would raise now (an un-mute where the manager acts on live, the order flow's CONFIRM
     * in Live), beside the change's scope (review G5-2).
     */
    fun fromAutopilot(book: AutoChange.Book, day: LocalDate, undoNeedsPin: (AutoChange.Entry) -> Boolean = { false }): List<Item> = book.entries
        .filter { it.day == day && it.status != AutoChange.Status.SKIPPED && it.status != AutoChange.Status.DECLINED }
        .map { e ->
            val c = e.change
            val tuning = e.by.startsWith("specialists")
            val state = when (e.status) {
                AutoChange.Status.UNDONE -> UndoState.UNDONE
                AutoChange.Status.REVERTED -> UndoState.REVERTED
                AutoChange.Status.OFFERED -> UndoState.ASKED
                AutoChange.Status.REVERT_ASKED -> UndoState.NEEDS_PIN
                // An older flip of Boss's Liquidity paper trail switch: only the newest one undoes (13 Oct review).
                else -> if (AutoChange.superseded(book, e)) UndoState.NO_UNDO
                    else if (AutoChange.undoNeedsPin(c) || undoNeedsPin(e)) UndoState.NEEDS_PIN else UndoState.UNDOABLE
            }
            Item("ap:${e.id}", if (tuning) Source.SPECIALISTS else Source.AUTOPILOT, e.atMs,
                // Boss's own changes (13 Oct: the park, the Liquidity paper trail), logged here so each can be undone in one tap:
                // said as Boss's, not Autopilot's.
                if (tuning) Source.SPECIALISTS.agent else if (AutoChange.bossOwn(c.kind)) "You (logged by Autopilot)" else "Autopilot · ${c.kind.agent}",
                "${c.what}: ${c.from} → ${c.to}", c.reason, state, c.scope.words, e.id, own = AutoChange.bossOwn(c.kind))
        }

    /** The lab's automatic paper promotions on [day] (a live rule moves back only through its own PIN flow). */
    fun fromLab(changes: List<LabChange>, day: LocalDate): List<Item> = changes.filter { it.day == day && it.auto }.map { c ->
        val state = when {
            c.status.equals("UNDONE", true) -> UndoState.UNDONE
            c.live -> UndoState.NO_UNDO
            c.status.equals("APPLIED", true) -> UndoState.UNDOABLE
            else -> UndoState.NO_UNDO
        }
        Item("lab:${c.id}", Source.LAB, c.atMs, Source.LAB.agent, "${c.label}: ${c.moved}", c.why, state, if (c.live) "live" else "paper", c.id)
    }

    /** The health tuner's changes today: a lengthened cadence still in force can be put back; a restore is already back. */
    fun fromHealth(changes: List<HealthChange>): List<Item> = changes.map { h ->
        Item("health:${h.key}:${h.atMs}", Source.HEALTH, h.atMs, Source.HEALTH.agent, h.words,
            if (h.restore) "healthy for three days" else "screen stalls clustered there (only how often something non-critical refreshes)",
            if (h.restore || !h.active) UndoState.NO_UNDO else UndoState.UNDOABLE, "app speed", h.key)
    }

    /** Other agents' change findings today (no undo here: undone where they were made). */
    fun fromFindings(fs: List<Findings.Finding>, day: LocalDate): List<Item> = fs
        .filter { f -> AgentLessons.dayOf(f.atMs) == day && (f.kind.name == "CHANGE" || (f.kind == Findings.Kind.OTHER && CHANGE_WORDS.containsMatchIn(f.words))) }
        .filter { f -> OWN_BOOKS.none { f.who.lowercase(Locale.ENGLISH).startsWith(it) } }
        .distinctBy { it.who + "|" + it.words }
        .map { f -> Item("find:${f.seq}:${f.atMs}", Source.FINDING, f.atMs, f.who, f.words.take(160), "its own finding", UndoState.NO_UNDO, key = f.who) }

    /** All of them, oldest first (the screen shows newest first). */
    fun merge(vararg parts: List<Item>): List<Item> = parts.flatMap { it }.sortedWith(compareBy<Item>({ it.atMs }, { it.id }))

    // ---- undo everything today ---------------------------------------------------------------------------------------------

    /** What "undo everything today" does: [now] (newest first, each by its own path), [needPin] (left for the PIN), [left]. */
    data class Plan(val now: List<Item>, val needPin: List<Item>, val left: List<Item>)

    /**
     * The plan over [items]: newest first; an undo that raises live risk goes to [Plan.needPin] unless [pinConfirmed]; a
     * change with no undo of its own, or already undone or reverted, is left.
     */
    fun undoPlan(items: List<Item>, pinConfirmed: Boolean = false): Plan {
        val newest = items.sortedWith(compareByDescending<Item> { it.atMs }.thenByDescending { it.id })
        val now = ArrayList<Item>(); val pin = ArrayList<Item>(); val left = ArrayList<Item>()
        // Boss's own changes (13 Oct's park) are not today's automatic changes: only their own Undo puts them back.
        for (i in newest.filter { !it.own }) when (i.undo) {
            UndoState.UNDOABLE -> now += i
            UndoState.NEEDS_PIN -> if (pinConfirmed) now += i else pin += i
            UndoState.NO_UNDO -> left += i
            else -> {}
        }
        return Plan(now, pin, left)
    }

    /** One undo's outcome: what, and what its path said (null: it could not be undone just now). */
    data class Done(val item: Item, val said: String?)

    // ---- words -------------------------------------------------------------------------------------------------------------

    private fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"

    /** "What changed today across the app?" */
    fun say(items: List<Item>, hhmm: (Long) -> String): String {
        if (items.isEmpty()) return "Nothing changed by itself today, Boss: no agent made an automatic change."
        val made = items.filter { it.undo != UndoState.ASKED }
        val asked = items.size - made.size
        val bySource = made.groupBy { it.source }.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.value.size} from ${it.key.agent}" }
        val head = "Today ${plural(made.size, "automatic change")}" + (if (made.isNotEmpty()) " ($bySource)" else "") +
            (if (asked > 0) ", and ${plural(asked, "change")} asked of you" else "") + "."
        val last = made.sortedByDescending { it.atMs }.take(4).joinToString(" ") { "${hhmm(it.atMs)} ${it.agent}: ${it.what} (${it.undo.words})." }
        val pending = made.count { it.pending }
        val pin = made.count { it.undo == UndoState.NEEDS_PIN }
        val tail = if (pending == 0) "" else " \"Undo everything today\" would put back $pending" + (if (pin > 0) ", $pin of them only with your PIN." else ".")
        return "$head $last$tail".trim()
    }

    /** What "undo everything today" did. */
    fun sayUndone(done: List<Done>, needPin: List<Item>, left: List<Item>): String {
        val ok = done.filter { it.said != null }
        val failed = done.size - ok.size
        if (done.isEmpty() && needPin.isEmpty()) return "There was nothing of today's to undo, Boss."
        val parts = ArrayList<String>()
        if (ok.isNotEmpty()) parts += "Undone ${plural(ok.size, "change")}, newest first: " + ok.joinToString("; ") { it.item.what } + "."
        if (failed > 0) parts += "${plural(failed, "change")} could not be put back just now."
        if (needPin.isNotEmpty()) parts += "Needs your PIN (it adds live risk): " + needPin.joinToString("; ") { it.what } + ". Do those in Today's changes with one PIN."
        if (left.isNotEmpty()) parts += "Left as they are (no undo here): ${plural(left.size, "change")}."
        return parts.joinToString(" ")
    }

    /** The request's words before "undo everything today" is done. */
    fun askText(plan: Plan): String {
        if (plan.now.isEmpty() && plan.needPin.isEmpty()) return "There is nothing of today's to undo, Boss."
        return "Undo ${plural(plan.now.size, "automatic change")} made today, newest first, each through its own undo?" +
            (if (plan.needPin.isNotEmpty()) " ${plural(plan.needPin.size, "more")} would add live risk: those only with your PIN in Home → Automation card → Autopilot → Today's changes." else "")
    }
}
