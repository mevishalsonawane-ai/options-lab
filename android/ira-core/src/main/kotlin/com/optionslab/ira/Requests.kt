package com.optionslab.ira

/**
 * The Requests panel's own rules (Boss, 5 Oct): everything of Jarvis's that waits for Boss's yes - a trade idea (news,
 * a pattern, Solo), a "shall I stop / exit / close" command, a guard offer ("set a stop at ..."), a plan, a strategy to
 * approve - in one place, apart from the chat. Words, order and choice only: approving and declining stay the app's own
 * paths (the hub's confirm with its fingerprint, PIN and live gates; its decline), never anything here.
 */
object Requests {
    /**
     * Where an approved request would act. [NONE]: no order at all (a setting, a strategy stopped, a reminder).
     * [PAPER_ZERODHA]: both the paper account and Zerodha (an exit or a close-all with both open). [real]: it can send
     * Zerodha orders - never labelled Paper or No order.
     */
    enum class Venue(val label: String, val real: Boolean) {
        PAPER("Paper", false), ZERODHA("Zerodha · real money", true), PAPER_ZERODHA("Paper + Zerodha", true), NONE("No order", false);
    }

    /**
     * Where an exit, a close or a cancel would act, from what is open now: [zerodha] when Zerodha is logged in with live
     * positions or working orders (or could not be read while logged in), [paper] when the paper account has some.
     * Both: Paper + Zerodha; Zerodha only: Zerodha; otherwise Paper (nothing real to send).
     */
    fun venueFor(paper: Boolean, zerodha: Boolean): Venue = when {
        zerodha && paper -> Venue.PAPER_ZERODHA
        zerodha -> Venue.ZERODHA
        else -> Venue.PAPER
    }

    /** A plan's venue from its steps' (null or [Venue.NONE]: no order): any real step makes it real; none at all, No order. */
    fun venueOf(steps: List<Venue?>): Venue {
        val zerodha = steps.any { it?.real == true }
        val paper = steps.any { it == Venue.PAPER || it == Venue.PAPER_ZERODHA }
        return if (!zerodha && !paper) Venue.NONE else venueFor(paper, zerodha)
    }

    enum class Kind(val label: String) {
        TRADE("Trade idea"), SOLO("Solo's trade idea"), COMMAND("Command"), EXIT("Emergency exit"), GUARD("Guard offer"),
        CLOSE("Close offer"), PLAN("Plan"), OFFER("Jarvis asks"), STRATEGY("Strategy to approve");
    }

    /** What became of a request that is no longer waiting. */
    enum class Outcome(val label: String) {
        APPROVED("Approved"), DECLINED("Declined"), LAPSED("Lapsed"), FAILED("Failed");
    }

    /**
     * One request waiting. [title]: a few words naming it ("stop ORB"); [what]: what it would do, in plain words;
     * [why]: one line, or null; [askedAt] / [lapsesAt]: epoch milliseconds ([lapsesAt] null: it does not lapse by itself).
     * [symbol], [qty] and [price]: as known when asked (null when not an order or not known yet). [details]: the whole of
     * what Jarvis said with it (a news trade's risk, IV line, cautions, turn-downs and confidence), or null.
     * [fingerprint]: a yes on it asks for Boss's fingerprint - the hub's own gate (the emergency exit, or a news trade
     * going live, on a phone with a fingerprint). Words only here: the gate itself stays the hub's.
     */
    data class RequestView(
        val id: Long, val kind: Kind, val title: String, val what: String, val why: String?, val venue: Venue,
        val askedAt: Long, val lapsesAt: Long?, val symbol: String? = null, val qty: String? = null, val price: String? = null,
        val details: String? = null, val fingerprint: Boolean = false,
    ) {
        /** Can a spoken yes or no answer it? (A strategy is approved on the screen only.) */
        val voiced: Boolean get() = kind != Kind.STRATEGY
    }

    /** A request no longer waiting, with what became of it and when. */
    data class Recent(val view: RequestView, val outcome: Outcome, val at: Long)

    /** How many recent ones are kept for the panel's "Recent" list. */
    const val RECENT_KEEP = 10
    const val EMPTY = "No requests waiting, Boss."
    const val GOLD = "In IraGoldAlgo I only talk, Boss: nothing here waits for your approval."

    private val TAIL = Regex("\\s*(Tap Confirm\\b[^.]*\\.?|Approve or reject\\.?|Yes or no\\?)\\s*$", RegexOption.IGNORE_CASE)

    /** The few words naming a request: its plain [what], cut at a comma or "with" and kept short. */
    fun title(what: String, max: Int = 60): String {
        val w = what.trim().trimEnd('.')
        val cut = Regex(",| with ").find(w)?.range?.first?.takeIf { it >= 8 }?.let { w.substring(0, it) } ?: w
        return if (cut.length <= max) cut else cut.take(max - 1).trimEnd() + "…"
    }

    /** The one line of why: the request's message without its "Tap Confirm ..." / "Approve or reject." tail; null if none. */
    fun why(text: String?, max: Int = 160): String? {
        var t = text?.trim() ?: return null
        repeat(3) { t = t.replace(TAIL, "").trim() }
        if (t.isEmpty()) return null
        val first = Regex("(?<=[.!?])\\s").split(t).firstOrNull()?.trim().orEmpty().ifEmpty { t }
        return if (first.length <= max) first else first.take(max - 1).trimEnd() + "…"
    }

    private fun plain(s: String) = s.trim().trimEnd('.').trim()

    /** [title] adds nothing to [what] (the same words): the heading is left out rather than said twice. */
    private fun same(title: String, what: String) = plain(title).equals(plain(what), ignoreCase = true)

    /**
     * The chat's line when a request is made: its short heading ([title]), then the full [what] - never shortened, so an
     * exit or a plan is shown whole. The buttons and the rest are under it and in the panel.
     */
    fun chatLine(title: String, what: String): String =
        if (same(title, what)) "New request: ${plain(what)} — see Requests." else "New request: ${plain(title)} — ${plain(what)}. See Requests."

    /** The plain spoken ask: "Request: <title>. Shall I <full what>? Yes or no?" - the what never shortened. */
    fun ask(title: String, what: String): String =
        if (same(title, what)) "Request: Shall I ${plain(what)}? Yes or no?" else "Request: ${plain(title)}. Shall I ${plain(what)}? Yes or no?"

    /** The card's mark for a request whose yes asks for the fingerprint. */
    const val FINGERPRINT_MARK = "Fingerprint needed"

    private val CANCELS = Regex("\\bcancel", RegexOption.IGNORE_CASE)
    private val CLOSES = Regex("\\b(close|closes|closing|exit|exits|square)", RegexOption.IGNORE_CASE)

    private fun trade(v: RequestView) = v.kind == Kind.TRADE || v.kind == Kind.SOLO

    /**
     * What a yes on [v] needs, said plainly: the fingerprint when the hub asks for it ([RequestView.fingerprint]); real
     * money without it is one tap, said as such ("one tap closes real positions"); nothing real, null.
     */
    fun yesLine(v: RequestView): String? = when {
        v.fingerprint -> "A yes on it needs your fingerprint."
        !v.venue.real -> null
        trade(v) -> "Real money: no fingerprint is asked for it on this phone, so one tap approves it."
        v.kind == Kind.GUARD -> "Real money: one tap places the stop at Zerodha - no fingerprint asked."
        CANCELS.containsMatchIn(v.what) && !CLOSES.containsMatchIn(v.what) -> "Real money: one tap cancels real orders - no fingerprint asked."
        else -> "Real money: one tap closes real positions - no fingerprint asked."
    }

    /**
     * The panel's note over the waiting requests [shown]: every yes goes through the same checks; the fingerprint is named
     * only when one of them asks for it, and a real-money close or protect without it is said plainly to be one tap.
     */
    fun panelNote(shown: List<RequestView>): String {
        val out = ArrayList<String>()
        out += "Yes goes through the same checks as ever."
        if (shown.any { it.fingerprint }) out += "A card marked \"$FINGERPRINT_MARK\" asks for your fingerprint on a yes."
        if (shown.any { !it.fingerprint && it.venue.real && !trade(it) })
            out += "Closing and protecting are one tap by design: on Zerodha, one tap closes real positions."
        if (shown.any { !it.fingerprint && it.venue.real && trade(it) })
            out += "No fingerprint is asked on this phone for a real-money trade; the app's lock covers it."
        out += "A request lapses by itself, and nothing is done."
        return out.joinToString(" ")
    }

    /** Details longer than this are folded in the panel (a tap shows them whole). */
    const val DETAILS_FOLD = 160

    /** A second yes (a tap, or the voice) on a request already answered elsewhere. */
    const val ANSWERED = "That was already answered, Boss."

    /**
     * The words for a confirm that found nothing waiting: [ANSWERED] when it was answered - being done right now
     * ([inFlight]) or Recent shows an outcome other than lapsed; else [lapsed], the caller's own words.
     */
    fun alreadyLine(outcome: Outcome?, inFlight: Boolean, lapsed: String): String =
        if (inFlight || (outcome != null && outcome != Outcome.LAPSED)) ANSWERED else lapsed

    /** Jarvis's spoken ask, naming the request first. [said]: what he would have said, or null for the plain ask. */
    fun spoken(title: String, said: String? = null): String =
        if (said.isNullOrBlank()) "Request: $title. Yes or no?" else "Request: $title. ${said.trim()}"

    /** The badge: "Requests 2", or "Requests" with none. */
    fun badge(count: Int): String = if (count > 0) "Requests $count" else "Requests"

    /** On a locked phone a notification says only how many wait - never what, never a symbol or an amount. */
    fun lockedLine(count: Int): String = when {
        count <= 0 -> "Jarvis"
        count == 1 -> "Jarvis: 1 request waiting"
        else -> "Jarvis: $count requests waiting"
    }

    /** "Asked just now", "Asked 4 min ago", "Asked 2 h ago". */
    fun askedText(askedAt: Long, now: Long): String {
        val s = ((now - askedAt) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "Asked just now"
            s < 3600 -> "Asked ${s / 60} min ago"
            else -> "Asked ${s / 3600} h ago"
        }
    }

    /** The countdown: "Lapses in 9:05", "Lapses in 0:40", "Lapsed", or "Does not lapse". */
    fun lapseText(lapsesAt: Long?, now: Long): String {
        if (lapsesAt == null) return "Does not lapse"
        val ms = lapsesAt - now
        if (ms <= 0) return "Lapsed"
        val s = (ms + 999) / 1000
        return "Lapses in ${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /**
     * The panel's clock (Battery, round 15): how long until anything the panel shows at [now] can change - null when
     * nothing can (none waiting, or IraGoldAlgo). A countdown ("Lapses in 9:05") changes each second; with none lapsing
     * only "Asked ..." moves: at 1 min, then each minute, then each hour. So no once-a-second redraw of an empty panel or
     * of asks that do not lapse. A new or answered request recomposes the panel on its own (the hub's state).
     */
    fun tickIn(list: List<RequestView>, now: Long, gold: Boolean): Long? {
        if (gold) return null
        val open = list.filter { open(it, now) }
        if (open.isEmpty()) return null
        if (open.any { it.lapsesAt != null }) return 1_000L
        return open.minOf { v ->
            val e = now - v.askedAt
            when {
                e < 60_000L -> 60_000L - e
                e < 3_600_000L -> 60_000L - e % 60_000L
                else -> 3_600_000L - e % 3_600_000L
            }
        }
    }

    /** Still waiting at [now]: not past its lapse time. */
    fun open(v: RequestView, now: Long): Boolean = v.lapsesAt == null || v.lapsesAt > now

    /** Soonest lapse first; those that do not lapse last; then the older ask first, then the id. */
    fun sorted(list: List<RequestView>): List<RequestView> =
        list.sortedWith(compareBy<RequestView>({ it.lapsesAt ?: Long.MAX_VALUE }, { it.askedAt }, { it.id }))

    /** What the panel lists at [now]: the open ones, sorted - none at all in IraGoldAlgo ([gold]: only talk there). */
    fun shown(list: List<RequestView>, now: Long, gold: Boolean): List<RequestView> =
        if (gold) emptyList() else sorted(list.filter { open(it, now) })

    /** [r] added to the recent list (newest first, at most [RECENT_KEEP]; a request is listed once). */
    fun keep(recent: List<Recent>, r: Recent): List<Recent> =
        (listOf(r) + recent.filter { it.view.id != r.view.id }).take(RECENT_KEEP)

    /** An approved request's outcome by its result: failed when the result says it was not done. */
    fun outcomeOf(result: String?): Outcome = if (result == null || Plan.failed(result)) Outcome.FAILED else Outcome.APPROVED

    /**
     * A Yes or No button under a chat message (or in the panel) answers ITS OWN request: the id it was drawn for, while
     * that one still waits - else nothing (answered, lapsed or gone). Never another pending request.
     */
    fun tapTarget(buttonId: Long, waiting: Collection<Long>): Long? = buttonId.takeIf { it in waiting }

    /**
     * Yes / No buttons for an offer of words ("BankNifty's levels next, Boss?", the morning "say yes for it"): only under
     * Jarvis's newest message, only when it ends with that very offer, and never on a locked phone. Superseded (a newer
     * message, the offer ended or taken), no buttons.
     */
    fun offerButtons(msgText: String, newest: Boolean, offerLine: String?, locked: Boolean): Boolean =
        newest && !locked && !offerLine.isNullOrBlank() && msgText.trimEnd().endsWith(offerLine.trim())

    // ---- a spoken yes or no with more than one request waiting -------------------------------------------------------

    /** What to do with a spoken yes or no while Jarvis waits on one request. */
    sealed class Pick {
        /** As before: the yes or no is for the request just asked about. */
        object Pass : Pick()
        /** More than one waits and the words did not say which: nothing is picked; [line] is said. */
        data class Ambiguous(val count: Int, val line: String) : Pick()
        /** Boss named one: Jarvis asks about that one (and only its next yes or no answers it). */
        data class Reask(val id: Long, val line: String) : Pick()
    }

    private val FILLER = setOf("a", "an", "the", "on", "at", "of", "to", "in", "for", "and", "it", "its", "my", "your", "this", "that",
        "yes", "no", "ok", "okay", "please", "boss", "jarvis", "one", "lot", "lots", "set", "stop", "start", "close", "buy", "sell",
        "do", "go", "ahead", "approve", "reject", "cancel", "confirm", "turn", "switch", "with", "nearest", "expiry", "money")

    private fun words(s: String): Set<String> = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
        .filter { it.length > 1 && it !in FILLER }.toSet()

    /**
     * The one waiting request [said] names by a word of its own (one no other waiting request has), or null when none or
     * more than one is named.
     */
    fun named(said: String, waiting: List<RequestView>): RequestView? {
        val heard = words(said)
        if (heard.isEmpty()) return null
        val own = waiting.associateWith { v -> words(v.title + " " + (v.symbol ?: "")) }
        val hits = waiting.filter { v ->
            val others = waiting.filter { it.id != v.id }.flatMap { own[it].orEmpty() }.toSet()
            own[v].orEmpty().any { it in heard && it !in others }
        }
        return hits.singleOrNull()
    }

    /** "You have 2 requests, Boss — open Requests, or say which one." */
    fun ambiguousLine(count: Int): String = "You have $count requests, Boss — open Requests, or say which one."

    /** Asked again by name, for one plain yes or no: the full [what], never shortened. */
    fun reaskLine(title: String, what: String): String =
        if (same(title, what)) "Request: Shall I ${plain(what)}? Just yes or no?" else "Request: ${plain(title)}. Shall I ${plain(what)}? Just yes or no?"

    /**
     * [said] is little more than [v]'s name: at most one word beyond the request's own words ("the nifty one", "orb"),
     * so "what is nifty doing" is a question of its own, never a pick of the Nifty trade.
     */
    fun mostlyName(said: String, v: RequestView): Boolean {
        val own = words(v.title + " " + v.what + " " + (v.symbol ?: ""))
        return words(said).count { it !in own } <= 1
    }

    /**
     * Boss's words [said] (read as a yes or no: [yesNo], null when neither) while Jarvis waits on [asked] - its window open.
     * [focused]: the request Jarvis last asked again by name. With one request waiting, as before ([Pick.Pass]). With two
     * or more: a request named is asked again by itself ([Pick.Reask]) - only when a yes or no was heard with the name, or
     * the words are little more than the name ([mostlyName]); a question that merely names one ("what is nifty doing")
     * is left as before. A bare yes or no is for the one asked again by name only; otherwise nothing is picked
     * ([Pick.Ambiguous]). A strategy (approved on the screen) is not counted.
     */
    fun pick(said: String, yesNo: Boolean?, asked: Long, focused: Long?, pending: List<RequestView>): Pick {
        val voiced = pending.filter { it.voiced }
        if (voiced.size < 2) return Pick.Pass
        val name = named(said, voiced)
        if (name != null && (yesNo != null || mostlyName(said, name))) return Pick.Reask(name.id, reaskLine(name.title, name.what))
        if (yesNo == null) return Pick.Pass
        if (focused != null && focused == asked && voiced.any { it.id == asked }) return Pick.Pass
        return Pick.Ambiguous(voiced.size, ambiguousLine(voiced.size))
    }

    // ---- asked: what waits (understanding round 25) -------------------------------------------------------------------

    private const val ASK_LEAD = "^ (hey |ok |okay )?(jarvis |boss )?(please )?"
    private const val ASK_TAIL = "( please| boss| jarvis| now| abhi| right now)* $"

    /**
     * "Open requests", "kya pending hai", "what's waiting for my approval", "how many requests": what waits for Boss's yes,
     * read out ([listSay]). Whole phrases only, never with an order, a position, a strategy or a reminder named ("pending
     * orders" stays his orders); it reads only - approving and declining stay the app's own paths.
     */
    private val LIST = rx(ASK_LEAD + "(any |show |show me |list |list my |my |the |all |all the |open |pending |waiting )*(requests?|approvals?|pending approvals?)" +
        "( pending| waiting| open| dikhao| batao| list| panel| tab)?( hai| hain| he)?( kya)?" + ASK_TAIL + "|" +
        ASK_LEAD + "how many (requests?|approvals?)( are| do i have| have i got)?( pending| waiting| open)?( are there| for me)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(what|which) (requests?|approvals?) (are|is) (pending|waiting|open)( for me)?" + ASK_TAIL + "|" +
        // ("Any thing", "some thing": the recognizer's split words, round 27.)
        ASK_LEAD + "(whats|what s|what is|is anything|is there anything|anything|is any thing|is there any thing|any thing|some thing|kya|kuch|koi cheez|kya kuch|kya koi cheez) " +
        "(pending|waiting( for (me|my (approval|yes|ok|okay|confirm|confirmation|answer))| on me| on my (yes|approval))?)( hai| he| h)?( kya)?" + ASK_TAIL + "|" +
        ASK_LEAD + "((kya|kuch|koi) approve (karna|karne ko) (hai|he)( kya)?|what (do i|should i) (need to |have to )?approve|anything (to|for me to) approve|" +
        "what needs my (approval|yes|ok|okay|confirm))" + ASK_TAIL)

    /** Does [text] ask what waits for Boss's yes? */
    fun listAsked(text: String): Boolean =
        // RequestBook (usefulness 33) answers the same asks more fully (and what was answered): it goes first.
        RequestBook.asked(text) == null &&
            LIST.containsMatchIn(" " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " ")

    /**
     * What waits, said: each request's heading, where it would act and its countdown, at [now] ([shown]). On a locked phone
     * ([locked]) only how many - never what, as [lockedLine]; none at all in IraGoldAlgo ([gold]). Words only.
     */
    fun listSay(list: List<RequestView>, now: Long, locked: Boolean, gold: Boolean): String {
        if (gold) return GOLD
        val open = shown(list, now, false)
        if (open.isEmpty()) return EMPTY
        val n = open.size
        val count = if (n == 1) "1 request waiting" else "$n requests waiting"
        if (locked) return "$count, Boss. Unlock the phone to see what in Requests."
        val each = open.joinToString("; ") { v ->
            "${plain(v.title)} (${v.venue.label}, ${lapseText(v.lapsesAt, now).replaceFirstChar { it.lowercase() }})"
        }
        return "$count, Boss: $each. Each waits for your yes in Requests - nothing goes ahead without it."
    }
}
